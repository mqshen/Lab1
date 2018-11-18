package glutinous.rpc.netty

import java.io.ObjectInputStream

import glutinous.network.client.TransportClient
import glutinous.rpc.{RpcEndpointAddress, RpcEndpointRef, RpcTimeout}

import scala.concurrent.Future
import scala.reflect.ClassTag

class NettyRpcEndpointRef(private val endpointAddress: RpcEndpointAddress,
                          @transient @volatile private var nettyEnv: NettyRpcEnv) extends RpcEndpointRef {
  @transient @volatile var client: TransportClient = _

  override def name: String = endpointAddress.name

  /**
    * return the address for the [[RpcEndpointRef]]
    */
  override def address = endpointAddress.rpcAddress

  private def readObject(in: ObjectInputStream): Unit = {
    in.defaultReadObject()
    nettyEnv = NettyRpcEnv.currentEnv.value
    client = NettyRpcEnv.currentClient.value
  }

  /**
    * Send a message to the corresponding [[glutinous.rpc.RpcEndpoint.receiveAndReply)]] and return a [[Future]] to
    * receive the reply within the specified timeout.
    *
    * This method only sends the message once and never retries.
    */
  override def ask[T: ClassTag](message: Any, timeout: RpcTimeout): Future[T] = {
    nettyEnv.ask(new RequestMessage(nettyEnv.address, this, message), timeout)
  }

  override def send(message: Any): Unit = {
    require(message != null, "Message is null")
    nettyEnv.send(new RequestMessage(nettyEnv.address, this, message))
  }
}
