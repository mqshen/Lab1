package glutinous.serializer

import java.io.{Externalizable, ObjectInput, ObjectOutput}

class JavaSerializer extends Serializer with Externalizable {

  def newInstance(): SerializerInstance = {
    val classLoader = defaultClassLoader.getOrElse(Thread.currentThread.getContextClassLoader)
    new JavaSerializerInstance(100, true, classLoader)
  }

  override def writeExternal(out: ObjectOutput) = ???

  override def readExternal(in: ObjectInput) = ???
}
