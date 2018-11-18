package spark.deploy.history

import java.util.concurrent.TimeUnit

import spark.internal.config.ConfigBuilder
import spark.network.util.ByteUnit

object config {

  val DEFAULT_LOG_DIR = "file:/tmp/spark-events"

  val EVENT_LOG_DIR = ConfigBuilder("spark.history.fs.logDirectory")
    .stringConf
    .createWithDefault(DEFAULT_LOG_DIR)

  val MAX_LOG_AGE_S = ConfigBuilder("spark.history.fs.cleaner.maxAge")
    .timeConf(TimeUnit.SECONDS)
    .createWithDefaultString("7d")

  val LOCAL_STORE_DIR = ConfigBuilder("spark.history.store.path")
    .doc("Local directory where to cache application history information. By default this is " +
      "not set, meaning all history information will be kept in memory.")
    .stringConf
    .createOptional

  val MAX_LOCAL_DISK_USAGE = ConfigBuilder("spark.history.store.maxDiskUsage")
    .bytesConf(ByteUnit.BYTE)
    .createWithDefaultString("10g")

  val HISTORY_SERVER_UI_PORT = ConfigBuilder("spark.history.ui.port")
    .doc("Web UI port to bind Spark History Server")
    .intConf
    .createWithDefault(18080)

  val FAST_IN_PROGRESS_PARSING =
    ConfigBuilder("spark.history.fs.inProgressOptimization.enabled")
      .doc("Enable optimized handling of in-progress logs. This option may leave finished " +
        "applications that fail to rename their event logs listed as in-progress.")
      .booleanConf
      .createWithDefault(true)

  val END_EVENT_REPARSE_CHUNK_SIZE =
    ConfigBuilder("spark.history.fs.endEventReparseChunkSize")
      .doc("How many bytes to parse at the end of log files looking for the end event. " +
        "This is used to speed up generation of application listings by skipping unnecessary " +
        "parts of event log files. It can be disabled by setting this config to 0.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("1m")

}
