package glutinous.rpc

import glutinous.GlutinousException

case class RpcEndpointAddress(rpcAddress: RpcAddress, name: String) {

  def this(host: String, port: Int, name: String) = {
    this(RpcAddress(host, port), name)
  }

  override val toString = if (rpcAddress != null) {
    s"spark://$name@${rpcAddress.host}:${rpcAddress.port}"
  } else {
    s"spark-client://$name"
  }

}

object RpcEndpointAddress {
  def apply(host: String, port: Int, name: String): RpcEndpointAddress = {
    new RpcEndpointAddress(host, port, name)
  }

  def apply(sparkUrl: String): RpcEndpointAddress = {
    try {
      val uri = new java.net.URI(sparkUrl)
      val host = uri.getHost
      val port = uri.getPort
      val name = uri.getUserInfo
      if (uri.getScheme != "spark" ||
        host == null ||
        port < 0 ||
        name == null ||
        (uri.getPath != null && !uri.getPath.isEmpty) || // uri.getPath returns "" instead of null
        uri.getFragment != null ||
        uri.getQuery != null) {
        throw new GlutinousException("Invalid Spark URL: " + sparkUrl)
      }
      new RpcEndpointAddress(host, port, name)
    } catch {
      case e: java.net.URISyntaxException =>
        throw new GlutinousException("Invalid Spark URL: " + sparkUrl, e)
    }
  }
}
