package transactor

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import akka.serialization.SerializerWithStringManifest

import scala.concurrent.Promise

package object protocol {

  val SERVER_TIMEOUT = 50
  val CLIENT_TIMEOUT = 700
  val BATCH_SIZE = 1000

  trait Command

  case class Transaction(id: String,
                         keys: Seq[String],
                         p: Promise[Boolean] = Promise[Boolean](),
                         tmp: Long = System.currentTimeMillis())

  case class Enqueue(id: String, var keys: Seq[String]) extends Command
  case class Release(id: String) extends Command

}
