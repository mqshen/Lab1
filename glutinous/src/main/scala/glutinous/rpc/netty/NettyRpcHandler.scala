package glutinous.rpc.netty

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import glutinous.network.client.{RpcResponseCallback, TransportClient}
import glutinous.network.server.RpcHandler
import glutinous.rpc.RpcAddress
import glutinous.util.Logging

class NettyRpcHandler(
  dispatcher: Dispatcher,
  nettyEnv: NettyRpcEnv) extends RpcHandler with Logging {

  private val remoteAddresses = new ConcurrentHashMap[RpcAddress, RpcAddress]()

  override def channelActive(client: TransportClient): Unit = {
    val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
    assert(addr != null)
    val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
    dispatcher.postToAll(RemoteProcessConnected(clientAddr))
  }

  override def receive(client: TransportClient, message: ByteBuffer, callback: RpcResponseCallback) = {
    val messageToDispatch = internalReceive(client, message)
    dispatcher.postRemoteMessage(messageToDispatch, callback)
  }

  override def receive( client: TransportClient, message: ByteBuffer): Unit = {
    val messageToDispatch = internalReceive(client, message)
    dispatcher.postOneWayMessage(messageToDispatch)
  }

  private def internalReceive(client: TransportClient, message: ByteBuffer): RequestMessage = {
    val addr = client.getChannel().remoteAddress().asInstanceOf[InetSocketAddress]
    assert(addr != null)
    val clientAddr = RpcAddress(addr.getHostString, addr.getPort)
    val requestMessage = RequestMessage(nettyEnv, client, message)
    if (requestMessage.senderAddress == null) {
      // Create a new message with the socket address of the client as the sender.
      new RequestMessage(clientAddr, requestMessage.receiver, requestMessage.content)
    } else {
      // The remote RpcEnv listens to some port, we should also fire a RemoteProcessConnected for
      // the listening address
      val remoteEnvAddress = requestMessage.senderAddress
      if (remoteAddresses.putIfAbsent(clientAddr, remoteEnvAddress) == null) {
        dispatcher.postToAll(RemoteProcessConnected(remoteEnvAddress))
      }
      requestMessage
    }
  }
}
