package transactor

import akka.actor.{ActorPath, ActorSystem}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.util.Timeout

import scala.concurrent.duration._
import akka.pattern._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

object Client {

  def main(args: Array[String]): Unit = {

    val system = ActorSystem("client")

    val initialContacts = Set(
      ActorPath.fromString("akka.tcp://transactors@127.0.0.1:2551/system/receptionist"),
      ActorPath.fromString("akka.tcp://transactors@127.0.0.1:2552/system/receptionist"))

    val c = system.actorOf(
      ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)),
      "client")

    implicit val timeout = Timeout(5 seconds)
    val f = c ? ClusterClient.Send("/user/t-2551", "ping", localAffinity = true)
    //c ! ClusterClient.SendToAll("/user/serviceB", "hi")

    f.map { r =>
      println(s"result: ${r}\n")
    }.recover {
      case t: Throwable => println(t.getCause)
    }.andThen { case _ =>
      system.terminate()
    }

    Await.ready(f, timeout.duration)
  }

}
