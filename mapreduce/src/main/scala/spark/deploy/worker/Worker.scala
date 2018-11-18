package spark.deploy.worker

import java.io.File
import java.text.SimpleDateFormat
import java.util.{Date, Locale, UUID}
import java.util.concurrent.TimeUnit
import java.util.concurrent.{Future => JFuture, ScheduledFuture => JScheduledFuture}

import spark.deploy.DeployMessages._
import spark.deploy.ExecutorDescription
import spark.deploy.master.Master
import spark.deploy.worker.ui.WorkerWebUI
import spark.{SecurityManager, SparkConf}
import spark.internal.{Logging, config}
import spark.rpc.{RpcAddress, RpcEndpointRef, RpcEnv, ThreadSafeRpcEndpoint}
import spark.util.{SparkUncaughtExceptionHandler, ThreadUtils, Utils}

import scala.util.Random
import scala.util.control.NonFatal

class Worker(
  override val rpcEnv: RpcEnv,
  webUiPort: Int,
  cores: Int,
  memory: Int,
  masterRpcAddresses: Array[RpcAddress],
  endpointName: String,
  workDirPath: String = null,
  val conf: SparkConf,
  val securityMgr: SecurityManager) extends ThreadSafeRpcEndpoint with Logging {
  private val host = rpcEnv.address.host
  private val port = rpcEnv.address.port

  private var master: Option[RpcEndpointRef] = None
  private val preferConfiguredMasterAddress =
    conf.getBoolean("spark.worker.preferConfiguredMasterAddress", false)
  private var masterAddressToConnect: Option[RpcAddress] = None

  private val INITIAL_REGISTRATION_RETRIES = 6
  private val TOTAL_REGISTRATION_RETRIES = INITIAL_REGISTRATION_RETRIES + 10
  private val FUZZ_MULTIPLIER_INTERVAL_LOWER_BOUND = 0.500
  private val REGISTRATION_RETRY_FUZZ_MULTIPLIER = {
    val randomNumberGenerator = new Random(UUID.randomUUID.getMostSignificantBits)
    randomNumberGenerator.nextDouble + FUZZ_MULTIPLIER_INTERVAL_LOWER_BOUND
  }
  private val INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS = (math.round(10 * REGISTRATION_RETRY_FUZZ_MULTIPLIER))
  private val PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS = (math.round(60 * REGISTRATION_RETRY_FUZZ_MULTIPLIER))

  private val CLEANUP_ENABLED = conf.getBoolean("spark.worker.cleanup.enabled", false)
  private val CLEANUP_INTERVAL_MILLIS = conf.getLong("spark.worker.cleanup.interval", 60 * 30) * 1000

  private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
  private val HEARTBEAT_MILLIS = conf.getLong("spark.worker.timeout", 60) * 1000 / 4

  private var registerMasterFutures: Array[JFuture[_]] = null
  private var registrationRetryTimer: Option[JScheduledFuture[_]] = None
  private val workerId = generateWorkerId()

  private var connectionAttemptCount = 0

  private def generateWorkerId(): String = {
    "worker-%s-%s-%d".format(createDateFormat.format(new Date), host, port)
  }

  // A scheduled executor used to send messages at the specified time.
  private val forwordMessageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("worker-forward-message-scheduler")

  // A thread pool for registering with masters. Because registering with a master is a blocking
  // action, this thread pool must be able to create "masterRpcAddresses.size" threads at the same
  // time so that we can register with all masters.
  private val registerMasterThreadPool = ThreadUtils.newDaemonCachedThreadPool(
    "worker-register-master-threadpool",
    masterRpcAddresses.length // Make sure we can register with all masters at the same time
  )

  private val testing: Boolean = sys.props.contains("spark.testing")
  private val sparkHome =
    if (testing) {
      assert(sys.props.contains("spark.test.home"), "spark.test.home is not set!")
      new File(sys.props("spark.test.home"))
    } else {
      new File(sys.env.get("SPARK_HOME").getOrElse("."))
    }

  private val publicAddress = {
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else host
  }

  var workDir: File = null
  private var webUi: WorkerWebUI = null

  private var registered = false
  private var connected = false
  private var workerWebUiUrl: String = ""

  private def createWorkDir() {
    workDir = Option(workDirPath).map(new File(_)).getOrElse(new File(sparkHome, "work"))
    try {
      // This sporadically fails - not sure why ... !workDir.exists() && !workDir.mkdirs()
      // So attempting to create and then check if directory was created or not.
      workDir.mkdirs()
      if ( !workDir.exists() || !workDir.isDirectory) {
        logError("Failed to create work directory " + workDir)
        System.exit(1)
      }
      assert (workDir.isDirectory)
    } catch {
      case e: Exception =>
        logError("Failed to create work directory " + workDir, e)
        System.exit(1)
    }
  }

  override def onStart() {
    assert(!registered)
    logInfo("Starting Spark worker %s:%d with %d cores, %s RAM".format(
      host, port, cores, Utils.megabytesToString(memory)))
    logInfo(s"Running Spark version ${spark.SPARK_VERSION}")
    logInfo("Spark home: " + sparkHome)
    createWorkDir()
    webUi = new WorkerWebUI(this, workDir, webUiPort)
    webUi.bind()

    workerWebUiUrl = s"http://$publicAddress:${webUi.boundPort}"
    registerWithMaster()
  }


  private def registerWithMaster() {
    // onDisconnected may be triggered multiple times, so don't attempt registration
    // if there are outstanding registration attempts scheduled.
    registrationRetryTimer match {
      case None =>
        registered = false
        registerMasterFutures = tryRegisterAllMasters()
        connectionAttemptCount = 0
        registrationRetryTimer = Some(forwordMessageScheduler.scheduleAtFixedRate(
          new Runnable {
            override def run(): Unit = Utils.tryLogNonFatalError {
              Option(self).foreach(_.send(ReregisterWithMaster))
            }
          },
          INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS,
          INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS,
          TimeUnit.SECONDS))
      case Some(_) =>
        logInfo("Not spawning another attempt to register with the master, since there is an" +
          " attempt scheduled already.")
    }
  }


  private def tryRegisterAllMasters(): Array[JFuture[_]] = {
    masterRpcAddresses.map { masterAddress =>
      registerMasterThreadPool.submit(new Runnable {
        override def run(): Unit = {
          try {
            logInfo("Connecting to master " + masterAddress + "...")
            val masterEndpoint = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
            sendRegisterMessageToMaster(masterEndpoint)
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
          }
        }
      })
    }
  }

  private def sendRegisterMessageToMaster(masterEndpoint: RpcEndpointRef): Unit = {
    masterEndpoint.send(RegisterWorker(
      workerId,
      host,
      port,
      self,
      cores,
      memory,
      workerWebUiUrl,
      masterEndpoint.address))
  }


  override def receive: PartialFunction[Any, Unit] = synchronized {
    case msg: RegisterWorkerResponse =>
      handleRegisterResponse(msg)

    case SendHeartbeat =>
      if (connected) { sendToMaster(Heartbeat(workerId, self)) }

    case ReregisterWithMaster =>
      reregisterWithMaster()
    case e =>
      println("----------------:" + e)
  }

  private def handleRegisterResponse(msg: RegisterWorkerResponse): Unit = synchronized {
    msg match {
      case RegisteredWorker(masterRef, masterWebUiUrl, masterAddress) =>
        if (preferConfiguredMasterAddress) {
          logInfo("Successfully registered with master " + masterAddress.toSparkURL)
        } else {
          logInfo("Successfully registered with master " + masterRef.address.toSparkURL)
        }
        registered = true
        changeMaster(masterRef, masterWebUiUrl, masterAddress)
        forwordMessageScheduler.scheduleAtFixedRate(new Runnable {
          override def run(): Unit = Utils.tryLogNonFatalError {
            self.send(SendHeartbeat)
          }
        }, 0, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS)
        if (CLEANUP_ENABLED) {
          logInfo(
            s"Worker cleanup enabled; old application directories will be deleted in: $workDir")
          forwordMessageScheduler.scheduleAtFixedRate(new Runnable {
            override def run(): Unit = Utils.tryLogNonFatalError {
              self.send(WorkDirCleanup)
            }
          }, CLEANUP_INTERVAL_MILLIS, CLEANUP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }

//        val execs = executors.values.map { e =>
//          new ExecutorDescription(e.appId, e.execId, e.cores, e.state)
//        }
//        masterRef.send(WorkerLatestState(workerId, execs.toList, drivers.keys.toSeq))

      case RegisterWorkerFailed(message) =>
        if (!registered) {
          logError("Worker registration failed: " + message)
          System.exit(1)
        }

      case MasterInStandby =>
      // Ignore. Master not yet ready.
    }
  }

  private def sendToMaster(message: Any): Unit = {
    master match {
      case Some(masterRef) => masterRef.send(message)
      case None =>
        logWarning(
          s"Dropping $message because the connection to master has not yet been established")
    }
  }

  private def changeMaster(masterRef: RpcEndpointRef, uiUrl: String, masterAddress: RpcAddress) {
    // activeMasterUrl it's a valid Spark url since we receive it from master.
//    activeMasterUrl = masterRef.address.toSparkURL
//    activeMasterWebUiUrl = uiUrl
    masterAddressToConnect = Some(masterAddress)
    master = Some(masterRef)
    connected = true
//    if (reverseProxy) {
//      logInfo(s"WorkerWebUI is available at $activeMasterWebUiUrl/proxy/$workerId")
//    }
    // Cancel any outstanding re-registration attempts because we found a new master
    cancelLastRegistrationRetry()
  }

  /**
    * Re-register with the master because a network failure or a master failure has occurred.
    * If the re-registration attempt threshold is exceeded, the worker exits with error.
    * Note that for thread-safety this should only be called from the rpcEndpoint.
    */
  private def reregisterWithMaster(): Unit = {
    Utils.tryOrExit {
      connectionAttemptCount += 1
      if (registered) {
        cancelLastRegistrationRetry()
      } else if (connectionAttemptCount <= TOTAL_REGISTRATION_RETRIES) {
        logInfo(s"Retrying connection to master (attempt # $connectionAttemptCount)")
        /**
          * Re-register with the active master this worker has been communicating with. If there
          * is none, then it means this worker is still bootstrapping and hasn't established a
          * connection with a master yet, in which case we should re-register with all masters.
          *
          * It is important to re-register only with the active master during failures. Otherwise,
          * if the worker unconditionally attempts to re-register with all masters, the following
          * race condition may arise and cause a "duplicate worker" error detailed in SPARK-4592:
          *
          *   (1) Master A fails and Worker attempts to reconnect to all masters
          *   (2) Master B takes over and notifies Worker
          *   (3) Worker responds by registering with Master B
          *   (4) Meanwhile, Worker's previous reconnection attempt reaches Master B,
          *       causing the same Worker to register with Master B twice
          *
          * Instead, if we only register with the known active master, we can assume that the
          * old master must have died because another master has taken over. Note that this is
          * still not safe if the old master recovers within this interval, but this is a much
          * less likely scenario.
          */
        master match {
          case Some(masterRef) =>
            // registered == false && master != None means we lost the connection to master, so
            // masterRef cannot be used and we need to recreate it again. Note: we must not set
            // master to None due to the above comments.
            if (registerMasterFutures != null) {
              registerMasterFutures.foreach(_.cancel(true))
            }
            val masterAddress =
              if (preferConfiguredMasterAddress) masterAddressToConnect.get else masterRef.address
            registerMasterFutures = Array(registerMasterThreadPool.submit(new Runnable {
              override def run(): Unit = {
                try {
                  logInfo("Connecting to master " + masterAddress + "...")
                  val masterEndpoint = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
                  sendRegisterMessageToMaster(masterEndpoint)
                } catch {
                  case ie: InterruptedException => // Cancelled
                  case NonFatal(e) => logWarning(s"Failed to connect to master $masterAddress", e)
                }
              }
            }))
          case None =>
            if (registerMasterFutures != null) {
              registerMasterFutures.foreach(_.cancel(true))
            }
            // We are retrying the initial registration
            registerMasterFutures = tryRegisterAllMasters()
        }
        // We have exceeded the initial registration retry threshold
        // All retries from now on should use a higher interval
        if (connectionAttemptCount == INITIAL_REGISTRATION_RETRIES) {
          registrationRetryTimer.foreach(_.cancel(true))
          registrationRetryTimer = Some(
            forwordMessageScheduler.scheduleAtFixedRate(new Runnable {
              override def run(): Unit = Utils.tryLogNonFatalError {
                self.send(ReregisterWithMaster)
              }
            }, PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS,
              PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS,
              TimeUnit.SECONDS))
        }
      } else {
        logError("All masters are unresponsive! Giving up.")
        System.exit(1)
      }
    }
  }


  /**
    * Cancel last registeration retry, or do nothing if no retry
    */
  private def cancelLastRegistrationRetry(): Unit = {
    if (registerMasterFutures != null) {
      registerMasterFutures.foreach(_.cancel(true))
      registerMasterFutures = null
    }
    registrationRetryTimer.foreach(_.cancel(true))
    registrationRetryTimer = None
  }
}

object Worker extends Logging {
  val SYSTEM_NAME = "sparkWorker"
  val ENDPOINT_NAME = "Worker"

  def main(argStrings: Array[String]) {
    Thread.setDefaultUncaughtExceptionHandler(new SparkUncaughtExceptionHandler( exitOnUncaughtException = false))
    Utils.initDaemon(log)
    val conf = new SparkConf
    val args = new WorkerArguments(argStrings, conf)
    val rpcEnv = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, args.cores,
      args.memory, args.masters, args.workDir, conf = conf)
    // With external shuffle service enabled, if we request to launch multiple workers on one host,
    // we can only successfully launch the first worker and the rest fails, because with the port
    // bound, we may launch no more than one external shuffle service on each host.
    // When this happens, we should give explicit reason of failure instead of fail silently. For
    // more detail see SPARK-20989.
    val externalShuffleServiceEnabled = conf.get(config.SHUFFLE_SERVICE_ENABLED)
    val sparkWorkerInstances = scala.sys.env.getOrElse("SPARK_WORKER_INSTANCES", "1").toInt
    require(externalShuffleServiceEnabled == false || sparkWorkerInstances <= 1,
      "Starting multiple workers on one host is failed because we may launch no more than one " +
        "external shuffle service on each host, please set spark.shuffle.service.enabled to " +
        "false or set SPARK_WORKER_INSTANCES to 1 to resolve the conflict.")
    rpcEnv.awaitTermination()
  }

  def startRpcEnvAndEndpoint(
    host: String,
    port: Int,
    webUiPort: Int,
    cores: Int,
    memory: Int,
    masterUrls: Array[String],
    workDir: String,
    workerNumber: Option[Int] = None,
    conf: SparkConf = new SparkConf): RpcEnv = {

    // The LocalSparkCluster runs multiple local sparkWorkerX RPC Environments
    val systemName = SYSTEM_NAME + workerNumber.map(_.toString).getOrElse("")
    val securityMgr = new SecurityManager(conf)
    val rpcEnv = RpcEnv.create(systemName, host, port, conf, securityMgr)
    val masterAddresses = masterUrls.map(RpcAddress.fromSparkURL(_))
    rpcEnv.setupEndpoint(ENDPOINT_NAME, new Worker(rpcEnv, webUiPort, cores, memory,
      masterAddresses, ENDPOINT_NAME, workDir, conf, securityMgr))
    rpcEnv
  }

}