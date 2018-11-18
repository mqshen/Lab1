package glutinous.rpc.netty

import glutinous.network.client.RpcResponseCallback
import glutinous.rpc.{RpcAddress, RpcCallContext}
import glutinous.util.Logging

import scala.concurrent.Promise

abstract class NettyRpcCallContext(override val senderAddress: RpcAddress) extends RpcCallContext with Logging {
  protected def send(message: Any): Unit

  override def reply(response: Any): Unit = {
    send(response)
  }

  override def sendFailure(e: Throwable): Unit = {
    send(RpcFailure(e))
  }
}

class LocalNettyRpcCallContext(senderAddress: RpcAddress, p: Promise[Any]) extends NettyRpcCallContext(senderAddress) {

  override protected def send(message: Any): Unit = p.success(message)

}

class RemoteNettyRpcCallContext(
  nettyEnv: NettyRpcEnv,
  callback: RpcResponseCallback,
  senderAddress: RpcAddress)
  extends NettyRpcCallContext(senderAddress) {

  override protected def send(message: Any): Unit = {
    val reply = nettyEnv.serialize(message)
    callback.onSuccess(reply)
  }
}