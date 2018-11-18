package glutinous.serializer

import java.io.{Closeable, InputStream, OutputStream}
import java.nio.ByteBuffer

import scala.reflect.ClassTag

class Serializer {
  @volatile protected var defaultClassLoader: Option[ClassLoader] = None
}

abstract class SerializerInstance {
  def serialize[T: ClassTag](t: T): ByteBuffer

  def deserialize[T: ClassTag](bytes: ByteBuffer): T

  def deserialize[T: ClassTag](bytes: ByteBuffer, loader: ClassLoader): T

  def serializeStream(s: OutputStream): SerializationStream

  def deserializeStream(s: InputStream): DeserializationStream
}

abstract class SerializationStream extends Closeable {
  /** The most general-purpose method to write an object. */
  def writeObject[T: ClassTag](t: T): SerializationStream
  /** Writes the object representing the key of a key-value pair. */
  def writeKey[T: ClassTag](key: T): SerializationStream = writeObject(key)
  /** Writes the object representing the value of a key-value pair. */
  def writeValue[T: ClassTag](value: T): SerializationStream = writeObject(value)
  def flush(): Unit
  override def close(): Unit

  def writeAll[T: ClassTag](iter: Iterator[T]): SerializationStream = {
    while (iter.hasNext) {
      writeObject(iter.next())
    }
    this
  }
}

abstract class DeserializationStream extends Closeable {
  def readObject[T: ClassTag](): T
}
