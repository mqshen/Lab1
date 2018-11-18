package glutinous.deploy

import glutinous.rpc.{RpcAddress, RpcEndpointRef}
import glutinous.util.Utils

sealed trait DeployMessage extends Serializable

object DeployMessages {

  case class RegisterWorker(
    id: String,
    host: String,
    port: Int,
    worker: RpcEndpointRef,
    masterAddress: RpcAddress)
    extends DeployMessage {
    Utils.checkHost(host)
    assert (port > 0)
  }

  case object ReregisterWithMaster

  sealed trait RegisterWorkerResponse

  case object MasterInStandby extends DeployMessage with RegisterWorkerResponse

  case class RegisterWorkerFailed(message: String) extends DeployMessage with RegisterWorkerResponse

  case class RegisteredWorker(master: RpcEndpointRef, masterAddress: RpcAddress)
    extends DeployMessage with RegisterWorkerResponse

}
