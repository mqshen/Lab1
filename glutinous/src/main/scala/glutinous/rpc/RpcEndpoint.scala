package glutinous.rpc

import glutinous.GlutinousException

trait RpcEnvFactory {

  def create(config: RpcEnvConfig): RpcEnv
}

trait RpcEndpoint {
  /**
    * The [[RpcEnv]] that this [[RpcEndpoint]] is registered to.
    */
  val rpcEnv: RpcEnv

  final def self: RpcEndpointRef = {
    require(rpcEnv != null, "rpcEnv has not been initialized")
    rpcEnv.endpointRef(this)
  }

  def receive: PartialFunction[Any, Unit] = {
    case _ => throw new GlutinousException(self + " does not implement 'receive'")
  }

  def onStart() = {
    // By default, do nothing.
  }

  def onConnected(remoteAddress: RpcAddress): Unit = {
    // By default, do nothing.
  }

  def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case _ => context.sendFailure(new GlutinousException(self + " won't reply anything"))
  }


  /**
    * Invoked when any exception is thrown during handling messages.
    */
  def onError(cause: Throwable): Unit = {
    // By default, throw e and let RpcEnv handle it
    throw cause
  }

}

trait ThreadSafeRpcEndpoint extends RpcEndpoint
