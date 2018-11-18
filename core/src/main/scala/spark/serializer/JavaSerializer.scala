package spark.serializer

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import spark.SparkConf
import spark.util.Utils

class JavaSerializer(conf: SparkConf) extends Serializer with Externalizable {
  private var counterReset = conf.getInt("spark.serializer.objectStreamReset", 100)
  private var extraDebugInfo = conf.getBoolean("spark.serializer.extraDebugInfo", true)

  protected def this() = this(new SparkConf())  // For deserialization only

  override def newInstance(): SerializerInstance = {
    val classLoader = defaultClassLoader.getOrElse(Thread.currentThread.getContextClassLoader)
    new JavaSerializerInstance(counterReset, extraDebugInfo, classLoader)
  }

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    out.writeInt(counterReset)
    out.writeBoolean(extraDebugInfo)
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    counterReset = in.readInt()
    extraDebugInfo = in.readBoolean()
  }
}
