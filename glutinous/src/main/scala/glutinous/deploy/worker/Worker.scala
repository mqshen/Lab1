package glutinous.deploy.worker

import java.text.SimpleDateFormat
import java.util.concurrent.{TimeUnit, Future => JFuture, ScheduledFuture => JScheduledFuture}
import java.util.{Date, Locale, UUID}

import glutinous.deploy.DeployMessages.{RegisterWorker, ReregisterWithMaster}
import glutinous.deploy.master.Master
import glutinous.rpc.{RpcAddress, RpcEndpointRef, RpcEnv, ThreadSafeRpcEndpoint}
import glutinous.util.{Logging, ThreadUtils, Utils}

import scala.util.Random
import scala.util.control.NonFatal

class Worker(override val rpcEnv: RpcEnv, masterRpcAddresses: Array[RpcAddress], endpointName: String)
  extends ThreadSafeRpcEndpoint with Logging {
  private val host = rpcEnv.address.host
  private val port = rpcEnv.address.port

  Utils.checkHost(host)
  assert (port > 0)
  private def createDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

  private val forwordMessageScheduler =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("worker-forward-message-scheduler")

  private var registered = false
  private var connected = false
  private val workerId = generateWorkerId()

  private var master: Option[RpcEndpointRef] = None

  private def generateWorkerId(): String = {
    "worker-%s-%s-%d".format(createDateFormat.format(new Date), host, port)
  }

  private var connectionAttemptCount = 0
  private var registerMasterFutures: Array[JFuture[_]] = null
  private var registrationRetryTimer: Option[JScheduledFuture[_]] = None

  private val INITIAL_REGISTRATION_RETRIES = 6
  private val TOTAL_REGISTRATION_RETRIES = INITIAL_REGISTRATION_RETRIES + 10
  private val FUZZ_MULTIPLIER_INTERVAL_LOWER_BOUND = 0.500
  private val REGISTRATION_RETRY_FUZZ_MULTIPLIER = {
    val randomNumberGenerator = new Random(UUID.randomUUID.getMostSignificantBits)
    randomNumberGenerator.nextDouble + FUZZ_MULTIPLIER_INTERVAL_LOWER_BOUND
  }
  private val INITIAL_REGISTRATION_RETRY_INTERVAL_SECONDS = (math.round(10 *
    REGISTRATION_RETRY_FUZZ_MULTIPLIER))
  private val PROLONGED_REGISTRATION_RETRY_INTERVAL_SECONDS = (math.round(60
    * REGISTRATION_RETRY_FUZZ_MULTIPLIER))

  private val registerMasterThreadPool = ThreadUtils.newDaemonCachedThreadPool(
    "worker-register-master-threadpool",
    masterRpcAddresses.length // Make sure we can register with all masters at the same time
  )

  override def onStart() {
    assert(!registered)
    info("Starting Spark worker %s:%d ".format(host, port))

    //workerWebUiUrl = s"http://$publicAddress:${webUi.boundPort}"
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
        info("Not spawning another attempt to register with the master, since there is an" +
          " attempt scheduled already.")
    }
  }

  private def tryRegisterAllMasters(): Array[JFuture[_]] = {
    masterRpcAddresses.map { masterAddress =>
      registerMasterThreadPool.submit(new Runnable {
        override def run(): Unit = {
          try {
            info("Connecting to master " + masterAddress + "...")
            val masterEndpoint = rpcEnv.setupEndpointRef(masterAddress, Master.ENDPOINT_NAME)
            sendRegisterMessageToMaster(masterEndpoint)
          } catch {
            case ie: InterruptedException => // Cancelled
            case NonFatal(e) => warn(s"Failed to connect to master $masterAddress", e)
          }
        }
      })
    }
  }

  private def sendRegisterMessageToMaster(masterEndpoint: RpcEndpointRef): Unit = {
    val worker = RegisterWorker(
      workerId,
      host,
      port,
      self,
      masterEndpoint.address)
    masterEndpoint.send(worker)
  }

  private def reregisterWithMaster(): Unit = {
    Utils.tryOrExit {
      connectionAttemptCount += 1
      if (registered) {
        cancelLastRegistrationRetry()
      } else if (connectionAttemptCount <= TOTAL_REGISTRATION_RETRIES) {
        info(s"Retrying connection to master (attempt # $connectionAttemptCount)")
        master match {
          case Some(masterRef) =>
            if (registerMasterFutures != null) {
              registerMasterFutures.foreach(_.cancel(true))
            }
          case None =>
            if (registerMasterFutures != null) {
              registerMasterFutures.foreach(_.cancel(true))
            }
            // We are retrying the initial registration
            registerMasterFutures = tryRegisterAllMasters()
        }
      } else {
        error("All masters are unresponsive! Giving up.")
        System.exit(1)
      }
    }
  }

  private def cancelLastRegistrationRetry(): Unit = {
    if (registerMasterFutures != null) {
      registerMasterFutures.foreach(_.cancel(true))
      registerMasterFutures = null
    }
    registrationRetryTimer.foreach(_.cancel(true))
    registrationRetryTimer = None
  }

  override def receive: PartialFunction[Any, Unit] = {
    case ReregisterWithMaster =>
      reregisterWithMaster()
    case e =>
      println("------------------")
      println(e)
      println("------------------")
  }
}

object Worker extends Logging {
  val SYSTEM_NAME = "sparkWorker"
  val ENDPOINT_NAME = "Worker"
  private val SSL_NODE_LOCAL_CONFIG_PATTERN = """\-Dspark\.ssl\.useNodeLocalConf\=(.+)""".r

  def main(argStrings: Array[String]) {
    val rpcEnv = startRpcEnvAndEndpoint("localhost", 7078, Array("spark://localhost:7077"))
    rpcEnv.awaitTermination()
  }

  def startRpcEnvAndEndpoint(
                              host: String,
                              port: Int,
                              masterUrls: Array[String]): RpcEnv = {
    val workerNumber = None
    val systemName = SYSTEM_NAME + workerNumber.map(_.toString).getOrElse("")
    val rpcEnv = RpcEnv.create(systemName, host, port, false)
    val masterAddresses = masterUrls.map(RpcAddress.fromSparkURL(_))
    rpcEnv.setupEndpoint(ENDPOINT_NAME, new Worker(rpcEnv, masterAddresses, ENDPOINT_NAME))
    rpcEnv
  }
}