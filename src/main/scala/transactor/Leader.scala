package transactor

import java.util.concurrent.ConcurrentLinkedDeque

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberEvent, UnreachableMember}
import akka.cluster.client.ClusterClientReceptionist
import transactor.protocol.Transaction
import protocol._

import scala.collection.concurrent.TrieMap

class Leader extends Actor {

  val log = context.system.log
  val cluster = Cluster(context.system)

  ClusterClientReceptionist(context.system).registerService(self)

  override def receive: Receive = {
    case msg: String =>

      println(s"${Console.RED}received ${msg}!\n${Console.RESET}")

      sender ! "world"
    case _ =>
  }
}
