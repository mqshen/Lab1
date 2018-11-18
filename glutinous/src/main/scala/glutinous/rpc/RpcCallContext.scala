package glutinous.rpc

trait RpcCallContext {

  /**
    * Reply a message to the sender. If the sender is [[RpcEndpoint]], its [[RpcEndpoint.receive]]
    * will be called.
    */
  def reply(response: Any): Unit

  /**
    * Report a failure to the sender.
    */
  def sendFailure(e: Throwable): Unit

  /**
    * The sender of this message.
    */
  def senderAddress: RpcAddress
}
