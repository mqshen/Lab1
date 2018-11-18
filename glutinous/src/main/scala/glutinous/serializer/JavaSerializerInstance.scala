package glutinous.serializer

import java.io._
import java.nio.ByteBuffer

import glutinous.util.ByteBufferInputStream

import scala.reflect.ClassTag

private object JavaDeserializationStream {
  val primitiveMappings = Map[String, Class[_]](
    "boolean" -> classOf[Boolean],
    "byte" -> classOf[Byte],
    "char" -> classOf[Char],
    "short" -> classOf[Short],
    "int" -> classOf[Int],
    "long" -> classOf[Long],
    "float" -> classOf[Float],
    "double" -> classOf[Double],
    "void" -> classOf[Void]
  )
}

class JavaDeserializationStream(in: InputStream, loader: ClassLoader) extends DeserializationStream {
  private val objIn = new ObjectInputStream(in) {
    override def resolveClass(desc: ObjectStreamClass): Class[_] =
      try {
        // scalastyle:off classforname
        Class.forName(desc.getName, false, loader)
        // scalastyle:on classforname
      } catch {
        case e: ClassNotFoundException =>
          JavaDeserializationStream.primitiveMappings.getOrElse(desc.getName, throw e)
      }
  }

  override def close() = ???

  override def readObject[T: ClassTag]() = objIn.readObject().asInstanceOf[T]
}

class JavaSerializationStream(out: OutputStream, counterReset: Int, extraDebugInfo: Boolean)
  extends SerializationStream {
  private val objOut = new ObjectOutputStream(out)
  private var counter = 0

  def writeObject[T: ClassTag](t: T): SerializationStream = {
    try {
      objOut.writeObject(t)
    } catch {
      case e: NotSerializableException if extraDebugInfo =>
        println("need to implement, writeObject", e)
        throw e
        //throw SerializationDebugger.improveException(t, e)
    }
    counter += 1
    if (counterReset > 0 && counter >= counterReset) {
      objOut.reset()
      counter = 0
    }
    this
  }

  def flush() { objOut.flush() }
  def close() { objOut.close() }
}

class JavaSerializerInstance(
  counterReset: Int, extraDebugInfo: Boolean, defaultClassLoader: ClassLoader)
  extends SerializerInstance {

  override def deserialize[T: ClassTag](bytes: ByteBuffer): T = {
    val bis = new ByteBufferInputStream(bytes)
    val in = deserializeStream(bis)
    in.readObject()
  }

  override def deserializeStream(s: InputStream): DeserializationStream = {
    new JavaDeserializationStream(s, defaultClassLoader)
  }

  override def serialize[T: ClassTag](t: T) = ???

  override def deserialize[T: ClassTag](bytes: ByteBuffer, loader: ClassLoader) = ???

  override def serializeStream(s: OutputStream) = {
    new JavaSerializationStream(s, counterReset, extraDebugInfo)
  }
}
