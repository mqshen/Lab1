package glutinous.deploy.master

import glutinous.deploy.DeployMessages._
import glutinous.deploy.master.MasterMessages.{BoundPortsRequest, BoundPortsResponse, ElectedLeader}
import glutinous.rpc.{RpcAddress, RpcCallContext, RpcEnv, ThreadSafeRpcEndpoint}
import glutinous.util.Logging

import scala.collection.mutable.{HashMap, HashSet}

class Master(override val rpcEnv: RpcEnv, address: RpcAddress) extends ThreadSafeRpcEndpoint with Logging {
  private var state = RecoveryState.STANDBY

  private val idToWorker = new HashMap[String, WorkerInfo]
  private val addressToWorker = new HashMap[RpcAddress, WorkerInfo]

  val workers = new HashSet[WorkerInfo]

  override def onStart(): Unit = {

  }

  def electedLeader(): Unit = self.send(ElectedLeader)

  override def receive: PartialFunction[Any, Unit] = {
    case ElectedLeader =>
      println("ElectedLeader ")
      println("----------------")
      state = RecoveryState.ALIVE
      info("I have been elected leader! New state: " + state)
    case RegisterWorker(id, workerHost, workerPort, workerRef, masterAddress) =>
      info("Registering worker %s:%d ".format( workerHost, workerPort))
      if (state == RecoveryState.STANDBY) {
        workerRef.send(MasterInStandby)
      } else  if (idToWorker.contains(id)) {
        workerRef.send(RegisterWorkerFailed("Duplicate worker ID"))
      } else {
        val worker = new WorkerInfo(id, workerHost, workerPort, workerRef)
        if (registerWorker(worker)) {
          workerRef.send(RegisteredWorker(self, masterAddress))
          //schedule()
        } else {
          val workerAddress = worker.endpoint.address
          warn("Worker registration failed. Attempted to re-register worker at same " +
            "address: " + workerAddress)
          workerRef.send(RegisterWorkerFailed("Attempted to re-register worker at same address: "
            + workerAddress))
        }
      }
    case e =>
      println("------------------")
      println(e)
      println("------------------")
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
        info("Attempted to re-register worker at same address: " + workerAddress)
        return false
      }
    }

    workers += worker
    idToWorker(worker.id) = worker
    addressToWorker(workerAddress) = worker
    true
  }

  private def removeWorker(worker: WorkerInfo, msg: String) {
    info("Removing worker " + worker.id + " on " + worker.host + ":" + worker.port)
    worker.setState(WorkerState.DEAD)
    idToWorker -= worker.id
    addressToWorker -= worker.endpoint.address


    info(s"Telling app of lost worker: " + worker.id)
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case BoundPortsRequest =>
      context.reply(BoundPortsResponse(address.port, 8080, None))
    case e =>
      println("------------------")
      println(e)
      println("------------------")
  }

}

object Master extends Logging {
  val SYSTEM_NAME = "glutinousMaster"
  val ENDPOINT_NAME = "Master"

  def main(argStrings: Array[String]): Unit = {
    val (rpcEnv, _, _, master) = startRpcEnvAndEndpoint("localhost", 7077)
    master.electedLeader()

    rpcEnv.awaitTermination()
  }

  def startRpcEnvAndEndpoint(host: String, port: Int) = {
    val rpcEnv = RpcEnv.create(SYSTEM_NAME, host, port, false)
    val master = new Master(rpcEnv, rpcEnv.address)
    val masterEndpoint = rpcEnv.setupEndpoint(ENDPOINT_NAME, master)
    val portsResponse = masterEndpoint.askSync[BoundPortsResponse](BoundPortsRequest)
    (rpcEnv, portsResponse.webUIPort, portsResponse.restPort, master)
  }
}