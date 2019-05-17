package transactor

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.client.ClusterClientReceptionist

class Transactor(val id: String) extends Actor {

  val log = context.system.log

  val cluster = Cluster(context.system)
  ClusterClientReceptionist(context.system).registerService(self)

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  override def receive: Receive = {

    case MemberUp(member) =>
      log.info(s"${Console.BLUE}Member is Up: {}${Console.RESET}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)
    case _: MemberEvent => // ignore

    case msg: String =>
      log.info(s"${Console.RED}received ${msg}!\n${Console.RESET}")
      sender ! "World"

    case _ =>
  }
}
