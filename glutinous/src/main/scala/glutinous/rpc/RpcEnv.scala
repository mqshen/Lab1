package glutinous.rpc

import glutinous.rpc.netty.NettyRpcEnvFactory
import glutinous.util.RpcUtils

import scala.concurrent.Future

object RpcEnv {

  def create(name: String, host: String, port: Int, clientMode: Boolean) = {
    val config = RpcEnvConfig(name, host, port, 0, clientMode)
    new NettyRpcEnvFactory().create(config)
  }

}
abstract class RpcEnv {

  val defaultLookupTimeout = RpcUtils.lookupRpcTimeout()

  def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef]

  def setupEndpointRefByURI(uri: String): RpcEndpointRef = {
    defaultLookupTimeout.awaitResult(asyncSetupEndpointRefByURI(uri))
  }

  def setupEndpointRef(address: RpcAddress, endpointName: String): RpcEndpointRef = {
    setupEndpointRefByURI(RpcEndpointAddress(address, endpointName).toString)
  }

  private[rpc] def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef
  /**
    * Return the address that [[RpcEnv]] is listening to.
    */
  def address: RpcAddress


  def awaitTermination(): Unit
  /**
    * Register a [[RpcEndpoint]] with a name and return its [[RpcEndpointRef]]. [[RpcEnv]] does not
    * guarantee thread-safety.
    */
  def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef

  /**
    * [[RpcEndpointRef]] cannot be deserialized without [[RpcEnv]]. So when deserializing any object
    * that contains [[RpcEndpointRef]]s, the deserialization codes should be wrapped by this method.
    */
  def deserialize[T](deserializationAction: () => T): T
}


case class RpcEnvConfig(name: String, bindAddress: String, port: Int,
                        numUsableCores: Int,
                        clientMode: Boolean)