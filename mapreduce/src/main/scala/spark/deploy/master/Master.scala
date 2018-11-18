package spark.deploy.master

import java.util.concurrent.{ScheduledFuture, TimeUnit}

import spark.deploy.DeployMessages._
import spark.deploy.ExecutorState
import spark.deploy.master.MasterMessages._
import spark.deploy.master.ui.MasterWebUI
import spark.internal.Logging
import spark.master.LeaderElectable
import spark.rpc.{RpcAddress, RpcCallContext, RpcEnv, ThreadSafeRpcEndpoint}
import spark.util.{SparkUncaughtExceptionHandler, ThreadUtils, Utils}
import spark.{SecurityManager, SparkConf}

import scala.collection.mutable.{HashMap, HashSet}

class Master(
  override val rpcEnv: RpcEnv,
  address: RpcAddress,
  webUiPort: Int,
  val securityMgr: SecurityManager,
  val conf: SparkConf)
  extends ThreadSafeRpcEndpoint with Logging with LeaderElectable {

  private val WORKER_TIMEOUT_MS = conf.getLong("spark.worker.timeout", 60) * 1000
  private val REAPER_ITERATIONS = conf.getInt("spark.dead.worker.persistence", 15)

  // After onStart, webUi will be set
  private var webUi: MasterWebUI = null
  private var restServerBoundPort: Option[Int] = None
  private var masterWebUiUrl: String = _

  private var state = RecoveryState.STANDBY

  private val forwardMessageThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("master-forward-message-thread")

  private var checkForWorkerTimeOutTask: ScheduledFuture[_] = _

  val workers = new HashSet[WorkerInfo]
  private val idToWorker = new HashMap[String, WorkerInfo]
  private val addressToWorker = new HashMap[RpcAddress, WorkerInfo]


  private val masterUrl = address.toSparkURL

  private val masterPublicAddress = {
    val envVar = conf.getenv("SPARK_PUBLIC_DNS")
    if (envVar != null) envVar else address.host
  }

  override def onStart(): Unit = {
    logInfo("Starting Spark master at " + masterUrl)
    logInfo(s"Running Spark version ${spark.SPARK_VERSION}")
    webUi = new MasterWebUI(this, webUiPort)
    webUi.bind()
    masterWebUiUrl = "http://" + masterPublicAddress + ":" + webUi.boundPort

    checkForWorkerTimeOutTask = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(CheckForWorkerTimeOut)
      }
    }, 0, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  override def electedLeader(): Unit = self.send(ElectedLeader)

  override def revokedLeadership(): Unit = self.send(RevokedLeadership)

  override def receive: PartialFunction[Any, Unit] = {
    case ElectedLeader =>
      println("ElectedLeader ")
      println("----------------")
      state = RecoveryState.ALIVE
      logInfo("I have been elected leader! New state: " + state)
      if (state == RecoveryState.RECOVERING) {
//        beginRecovery(storedApps, storedDrivers, storedWorkers)
//        recoveryCompletionTask = forwardMessageThread.schedule(new Runnable {
//          override def run(): Unit = Utils.tryLogNonFatalError {
//            self.send(CompleteRecovery)
//          }
//        }, WORKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }

    case RegisterWorker(id, workerHost, workerPort, workerRef, cores, memory, workerWebUiUrl, masterAddress) =>
      logInfo("Registering worker %s:%d with %d cores, %s RAM".format( workerHost, workerPort, cores, Utils.megabytesToString(memory)))
      if (state == RecoveryState.STANDBY) {
        workerRef.send(MasterInStandby)
      } else if (idToWorker.contains(id)) {
        workerRef.send(RegisterWorkerFailed("Duplicate worker ID"))
      } else {
        val worker = new WorkerInfo(id, workerHost, workerPort, cores, memory,
          workerRef, workerWebUiUrl)
        if (registerWorker(worker)) {
//          persistenceEngine.addWorker(worker)
          workerRef.send(RegisteredWorker(self, masterWebUiUrl, masterAddress))
//          schedule()
        } else {
          val workerAddress = worker.endpoint.address
          logWarning("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          workerRef.send(RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress))
        }
      }
    case Heartbeat(workerId, worker) =>
      idToWorker.get(workerId) match {
        case Some(workerInfo) =>
          workerInfo.lastHeartbeat = System.currentTimeMillis()
        case None =>
          if (workers.map(_.id).contains(workerId)) {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " Asking it to re-register.")
            worker.send(ReconnectWorker(masterUrl))
          } else {
            logWarning(s"Got heartbeat from unregistered worker $workerId." +
              " This worker was never registered, so ignoring the heartbeat.")
          }
      }

    case CheckForWorkerTimeOut =>
      timeOutDeadWorkers()

    case e =>
      println(e)
      println("----------------")
  }


  /** Check for, and remove, any timed-out workers */
  private def timeOutDeadWorkers() {
    // Copy the workers into an array so we don't modify the hashset while iterating through it
    val currentTime = System.currentTimeMillis()
    val toRemove = workers.filter(_.lastHeartbeat < currentTime - WORKER_TIMEOUT_MS).toArray
    for (worker <- toRemove) {
      if (worker.state != WorkerState.DEAD) {
        logWarning("Removing %s because we got no heartbeat in %d seconds".format(
          worker.id, WORKER_TIMEOUT_MS / 1000))
        removeWorker(worker, s"Not receiving heartbeat for ${WORKER_TIMEOUT_MS / 1000} seconds")
      } else {
        if (worker.lastHeartbeat < currentTime - ((REAPER_ITERATIONS + 1) * WORKER_TIMEOUT_MS)) {
          workers -= worker // we've seen this DEAD worker in the UI, etc. for long enough; cull it
        }
      }
    }
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case BoundPortsRequest =>
      context.reply(BoundPortsResponse(address.port, webUi.boundPort, restServerBoundPort))
    case RequestMasterState =>
      context.reply(MasterStateResponse(
        address.host, address.port, restServerBoundPort))
    case e =>
      println(e)
      println("----------------")
  }

  private def registerWorker(worker: WorkerInfo): Boolean = {
    // There may be one or more refs to dead workers on this same node (w/ different ID's),
    // remove them.
    workers.filter { w =>
      (w.host == worker.host && w.port == worker.port) && (w.state == WorkerState.DEAD)
    }.foreach { w =>
      workers -= w
    }

    val workerAddress = worker.endpoint.address
    if (addressToWorker.contains(workerAddress)) {
      val oldWorker = addressToWorker(workerAddress)
      if (oldWorker.state == WorkerState.UNKNOWN) {
        // A worker registering from UNKNOWN implies that the worker was restarted during recovery.
        // The old worker must thus be dead, so we will remove it and accept the new worker.
        removeWorker(oldWorker, "Worker replaced by a new worker with same address")
      } else {
        logInfo("Attempted to re-register worker at same address: " + workerAddress)
        return false
      }
    }

    workers += worker
    idToWorker(worker.id) = worker
    addressToWorker(workerAddress) = worker
    true
  }


  private def removeWorker(worker: WorkerInfo, msg: String) {
    logInfo("Removing worker " + worker.id + " on " + worker.host + ":" + worker.port)
    worker.setState(WorkerState.DEAD)
    idToWorker -= worker.id
    addressToWorker -= worker.endpoint.address

    for (exec <- worker.executors.values) {
      logInfo("Telling app of lost executor: " + exec.id)
      exec.application.driver.send(ExecutorUpdated(
        exec.id, ExecutorState.LOST, Some("worker lost"), None, workerLost = true))
      exec.state = ExecutorState.LOST
//      exec.application.removeExecutor(exec)
    }
//    for (driver <- worker.drivers.values) {
//      if (driver.desc.supervise) {
//        logInfo(s"Re-launching ${driver.id}")
//        relaunchDriver(driver)
//      } else {
//        logInfo(s"Not re-launching ${driver.id} because it was not supervised")
//        removeDriver(driver.id, DriverState.ERROR, None)
//      }
//    }
    logInfo(s"Telling app of lost worker: " + worker.id)
//    apps.filterNot(completedApps.contains(_)).foreach { app =>
//      app.driver.send(WorkerRemoved(worker.id, worker.host, msg))
//    }
//    persistenceEngine.removeWorker(worker)
  }
}

object Master extends Logging {
  val SYSTEM_NAME = "sparkMaster"
  val ENDPOINT_NAME = "Master"

  def main(argStrings: Array[String]) {
    Thread.setDefaultUncaughtExceptionHandler(new SparkUncaughtExceptionHandler(
      exitOnUncaughtException = false))
    Utils.initDaemon(log)
    val conf = new SparkConf
    val args = new MasterArguments(argStrings, conf)
    val (rpcEnv, _, _, master) = startRpcEnvAndEndpoint(args.host, args.port, args.webUiPort, conf)
    master.electedLeader()

    rpcEnv.awaitTermination()
  }

  /**
    * Start the Master and return a three tuple of:
    *   (1) The Master RpcEnv
    *   (2) The web UI bound port
    *   (3) The REST server bound port, if any
    */
  def startRpcEnvAndEndpoint(
                              host: String,
                              port: Int,
                              webUiPort: Int,
                              conf: SparkConf): (RpcEnv, Int, Option[Int], Master) = {
    val securityMgr = new SecurityManager(conf)
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, host, port, conf, securityMgr)
    val master = new Master(rpcEnv, rpcEnv.address, webUiPort, securityMgr, conf)
    val masterEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME, master)
    val portsResponse = masterEndpoint.askSync[BoundPortsResponse](BoundPortsRequest)
    (rpcEnv, portsResponse.webUIPort, portsResponse.restPort, master)
  }
}