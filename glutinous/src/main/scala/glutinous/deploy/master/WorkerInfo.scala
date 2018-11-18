package glutinous.deploy.master

import glutinous.rpc.RpcEndpointRef

import scala.collection.mutable

class WorkerInfo(val id: String,
                 val host: String,
                 val port: Int,
                 val endpoint: RpcEndpointRef) extends Serializable {

  @transient var state: WorkerState.Value = _

  init()

  private def init() {
    //executors = new mutable.HashMap
    state = WorkerState.ALIVE
  }

  def setState(state: WorkerState.Value): Unit = {
    this.state = state
  }
}
