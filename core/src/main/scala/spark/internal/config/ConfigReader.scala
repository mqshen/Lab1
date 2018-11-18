package spark.internal.config


import scala.collection.mutable.HashMap
import scala.util.matching.Regex

private object ConfigReader {

  private val REF_RE = "\\$\\{(?:(\\w+?):)?(\\S+?)\\}".r

}

class ConfigReader(conf: ConfigProvider) {


  private val bindings = new HashMap[String, ConfigProvider]()

  /**
    * Binds a prefix to a provider. This method is not thread-safe and should be called
    * before the instance is used to expand values.
    */
  def bind(prefix: String, provider: ConfigProvider): ConfigReader = {
    bindings(prefix) = provider
    this
  }
  /**
    * Reads a configuration key from the default provider, and apply variable substitution.
    */
  def get(key: String): Option[String] = conf.get(key).map(substitute)


  def bindEnv(provider: ConfigProvider): ConfigReader = bind("env", provider)

  /**
    * Perform variable substitution on the given input string.
    */
  def substitute(input: String): String = substitute(input, Set())

  private def substitute(input: String, usedRefs: Set[String]): String = {
    if (input != null) {
      ConfigReader.REF_RE.replaceAllIn(input, { m =>
        val prefix = m.group(1)
        val name = m.group(2)
        val ref = if (prefix == null) name else s"$prefix:$name"
        require(!usedRefs.contains(ref), s"Circular reference in $input: $ref")

        val replacement = bindings.get(prefix)
          .flatMap(getOrDefault(_, name))
          .map { v => substitute(v, usedRefs + ref) }
          .getOrElse(m.matched)
        Regex.quoteReplacement(replacement)
      })
    } else {
      input
    }
  }
  /**
    * Gets the value of a config from the given `ConfigProvider`. If no value is found for this
    * config, and the `ConfigEntry` defines this config has default value, return the default value.
    */
  private def getOrDefault(conf: ConfigProvider, key: String): Option[String] = {
    conf.get(key).orElse {
      ConfigEntry.findEntry(key) match {
        case e: ConfigEntryWithDefault[_] => Option(e.defaultValueString)
        case e: ConfigEntryWithDefaultString[_] => Option(e.defaultValueString)
        case e: ConfigEntryWithDefaultFunction[_] => Option(e.defaultValueString)
        case e: FallbackConfigEntry[_] => getOrDefault(conf, e.fallback.key)
        case _ => None
      }
    }
  }

}
