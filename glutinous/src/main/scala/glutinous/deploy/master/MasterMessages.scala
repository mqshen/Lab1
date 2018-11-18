package glutinous.deploy.master

object MasterMessages {

  case object BoundPortsRequest

  case class BoundPortsResponse(rpcEndpointPort: Int, webUIPort: Int, restPort: Option[Int])

  case object ElectedLeader
}
