
import org.eclipse.jetty.server._
import org.eclipse.jetty.server.handler.{ContextHandlerCollection, ErrorHandler, ResourceHandler}
import org.eclipse.jetty.util.thread.{QueuedThreadPool, ScheduledExecutorScheduler}
object JettyTest {

  val SPARK_CONNECTOR_NAME = "Spark"

  def main(args: Array[String]): Unit = {
    val test = new JettyTest()
    test.startJetty("test", "localhost")
    Thread.sleep(Long.MaxValue)
  }
}

class JettyTest {
  import JettyTest._

  def startServiceOnPort[T](
                             startPort: Int,
                             startService: Int => (T, Int),
                             serviceName: String = ""): (T, Int) = {

    require(startPort == 0 || (1024 <= startPort && startPort < 65536),
      "startPort should be between 1024 and 65535 (inclusive), or 0 for a random free port.")

    val serviceString = if (serviceName.isEmpty) "" else s" '$serviceName'"
    for (offset <- 0 to 5) {
      // Do not increment port if startPort is 0, which is treated as a special port
      val tryPort = 8080
      try {
        val (service, port) = startService(tryPort)
        return (service, port)
      } catch {
        case e: Exception =>
          e.printStackTrace()
      }
    }
    // Should never happen
    throw new Exception(s"Failed to start service$serviceString on port $startPort")
  }

  def startJetty(serverName: String, hostName: String): Unit = {
    val pool = new QueuedThreadPool
    if (serverName.nonEmpty) {
      pool.setName(serverName)
    }
    pool.setDaemon(true)

    val server = new Server(pool)

//    val errorHandler = new ErrorHandler()
//    errorHandler.setShowStacks(true)
//    errorHandler.setServer(server)
//    server.addBean(errorHandler)

//    import org.eclipse.jetty.server.handler.ResourceHandler
//    //val collection = new ContextHandlerCollection
//    val resourceHandler = new ResourceHandler
//    server.setHandler(resourceHandler)

    // Executor used to create daemon threads for the Jetty connectors.
    val serverExecutor = new ScheduledExecutorScheduler(s"$serverName-JettyScheduler", true)

    try {
      server.start()

      // As each acceptor and each selector will use one thread, the number of threads should at
      // least be the number of acceptors and selectors plus 1. (See SPARK-13776)
      var minThreads = 1

      def newConnector(
          connectionFactories: Array[ConnectionFactory],
          port: Int): (ServerConnector, Int) = {
        val connector = new ServerConnector(
          server,
          null,
          serverExecutor,
          null,
          -1,
          -1,
          connectionFactories: _*)
        connector.setPort(port)
        connector.setHost(hostName)
        connector.setReuseAddress(true)

        // Currently we only use "SelectChannelConnector"
        // Limit the max acceptor number to 8 so that we don't waste a lot of threads
        connector.setAcceptQueueSize(math.min(connector.getAcceptors, 8))

        connector.start()
        // The number of selectors always equals to the number of acceptors
        minThreads += connector.getAcceptors * 2

        (connector, connector.getLocalPort())
      }

      // Bind the HTTP port.
      def httpConnect(currentPort: Int): (ServerConnector, Int) = {
        newConnector(Array(new HttpConnectionFactory()), currentPort)
      }

      val (httpConnector, httpPort) = startServiceOnPort[ServerConnector](8080, httpConnect, serverName)


      httpConnector.setName(SPARK_CONNECTOR_NAME)

      server.addConnector(httpConnector)

      pool.setMaxThreads(math.max(pool.getMaxThreads, minThreads))
    } catch {
      case e: Exception =>
        server.stop()
        if (serverExecutor.isStarted()) {
          serverExecutor.stop()
        }
        if (pool.isStarted()) {
          pool.stop()
        }
        throw e
    }
  }

}
