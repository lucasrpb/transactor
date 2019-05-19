package transactor

import akka.serialization.SerializerWithStringManifest

class MySerializer extends SerializerWithStringManifest {
  override def identifier: Int = 10000

  override def manifest(o: AnyRef): String = o match {
    case _: Person => "person"
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case Person(name) => name.getBytes()
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case "person" => Person(new String(bytes))
    }
  }
}
