package spark


import spark.internal.Logging
import spark.internal.config._
import spark.network.sasl.SecretKeyHolder

private[spark] class SecurityManager(
                                      sparkConf: SparkConf,
                                      val ioEncryptionKey: Option[Array[Byte]] = None)
  extends Logging with SecretKeyHolder {


  private val authOn = sparkConf.get(NETWORK_AUTH_ENABLED)
  /**
    * Check to see if authentication for the Spark communication protocols is enabled
    * @return true if authentication is enabled, otherwise false
    */
  def isAuthenticationEnabled(): Boolean = authOn


  /**
    * Gets the user used for authenticating HTTP connections.
    * For now use a single hardcoded user.
    * @return the HTTP user as a String
    */
  def getHttpUser(): String = "sparkHttpUser"


  private val defaultSSLOptions =
    SSLOptions.parse(sparkConf, "spark.ssl", defaults = None)

  def getSSLOptions(module: String): SSLOptions = {
    val opts =
      SSLOptions.parse(sparkConf, s"spark.ssl.$module", Some(defaultSSLOptions))
    logDebug(s"Created SSL options for $module: $opts")
    opts
  }
  /**
    * Gets the user used for authenticating SASL connections.
    * For now use a single hardcoded user.
    * @return the SASL user as a String
    */
  def getSaslUser(): String = "sparkSaslUser"

  /**
    * Gets an appropriate SASL User for the given appId.
    *
    * @throws IllegalArgumentException if the given appId is not associated with a SASL user.
    */
  override def getSaslUser(appId: String) = ???

  /**
    * Gets an appropriate SASL secret key for the given appId.
    *
    * @throws IllegalArgumentException if the given appId is not associated with a SASL secret key.
    */
  override def getSecretKey(appId: String) = ???

  /**
    * Checks the given user against the view acl and groups list to see if they have
    * authorization to view the UI. If the UI acls are disabled
    * via spark.acls.enable, all users have view access. If the user is null
    * it is assumed authentication is off and all users have access. Also if any one of the
    * UI acls or groups specify the WILDCARD(*) then all users have view access.
    *
    * @param user to see if is authorized
    * @return true is the user has permission, otherwise false
    */
  def checkUIViewPermissions(user: String): Boolean = {
    true
  }
}

object SecurityManager {

  val SPARK_AUTH_CONF = NETWORK_AUTH_ENABLED.key
  val SPARK_AUTH_SECRET_CONF = "spark.authenticate.secret"
  // This is used to set auth secret to an executor's env variable. It should have the same
  // value as SPARK_AUTH_SECRET_CONF set in SparkConf
  val ENV_AUTH_SECRET = "_SPARK_AUTH_SECRET"

  // key used to store the spark secret in the Hadoop UGI
  //val SECRET_LOOKUP_KEY = new Text("sparkCookie")
}