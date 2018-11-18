package glutinous.util

import glutinous.{GlutinousException, Helper}

import scala.util.control.{ControlThrowable, NonFatal}

object Utils extends Logging {
  def checkHost(host: String) = assert(host != null && host.indexOf(':') == -1, s"Expected hostname (not IP) but got $host")

  def extractHostPortFromSparkUrl(sparkUrl: String): (String, Int) = {
    try {
      val uri = new java.net.URI(sparkUrl)
      val host = uri.getHost
      val port = uri.getPort
      if (uri.getScheme != "spark" ||
        host == null ||
        port < 0 ||
        (uri.getPath != null && !uri.getPath.isEmpty) || // uri.getPath returns "" instead of null
        uri.getFragment != null ||
        uri.getQuery != null ||
        uri.getUserInfo != null) {
        throw new GlutinousException("Invalid master URL: " + sparkUrl)
      }
      (host, port)
    } catch {
      case e: java.net.URISyntaxException =>
        throw new GlutinousException("Invalid master URL: " + sparkUrl, e)
    }
  }

  def tryLogNonFatalError(block: => Unit) {
    try {
      block
    } catch {
      case NonFatal(t) =>
        error(s"Uncaught exception in thread ${Thread.currentThread().getName}", t)
    }
  }

  def tryOrExit(block: => Unit) {
    try {
      block
    } catch {
      case e: ControlThrowable => throw e
      case t: Throwable =>
        Helper.printImp()
        //sparkUncaughtExceptionHandler.uncaughtException(t)
    }
  }
}
