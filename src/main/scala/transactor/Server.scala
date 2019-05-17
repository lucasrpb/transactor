package transactor

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object Server {

  def main(args: Array[String]): Unit = {

    val port = "2551"

    val config = ConfigFactory.parseString(s"""
            akka.remote.netty.tcp.port=$port
        """).withFallback(ConfigFactory.load())
    val system = ActorSystem("transactors", config)

    system.actorOf(Props(classOf[Transactor], port), "t-2551")
  }

}
