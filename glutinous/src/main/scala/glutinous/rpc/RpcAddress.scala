package glutinous.rpc

import glutinous.util.Utils

/**
  * Address for an RPC environment, with hostname and port.
  */
case class RpcAddress(host: String, port: Int) {

  def hostPort: String = host + ":" + port

  /** Returns a string in the form of "spark://host:port". */
  def toSparkURL: String = "spark://" + hostPort

  override def toString: String = hostPort
}

object RpcAddress {
  /** Returns the [[RpcAddress]] encoded in the form of "spark://host:port" */
  def fromSparkURL(sparkUrl: String): RpcAddress = {
    val (host, port) = Utils.extractHostPortFromSparkUrl(sparkUrl)
    RpcAddress(host, port)
  }

}