package transactor

import java.util.concurrent.{ConcurrentLinkedDeque, TimeoutException}

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern._
import akka.util.Timeout
import protocol._

import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise

class Coordinator(val id: String) extends Actor {

  implicit val ec = context.dispatcher
  implicit val timeout = Timeout(5 seconds)
  val log = context.system.log

  val queue = new ConcurrentLinkedDeque[Transaction]()
  var running = TrieMap[String, Transaction]()

  val cluster = Cluster(context.system)
  ClusterClientReceptionist(context.system).registerService(self)

  val proxy = context.actorOf(
    ClusterSingletonProxy.props(
      singletonManagerPath = "/user/leader/singleton",
      settings = ClusterSingletonProxySettings(context.system)),
    name = "leaderProxy")

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])

    context.system.scheduler.schedule(5 milliseconds, 5 milliseconds){

      var work = Seq.empty[Transaction]

      var n = 0

      while(!queue.isEmpty && n < BATCH_SIZE)
      {
        work = work :+ queue.poll()
        n += 1
      }

      val now = System.currentTimeMillis()

      running = running.filter { case (id, t) =>
        now - t.tmp < SERVER_TIMEOUT
      }

      var keys = running.map(_._2.keys).flatten.toSeq

      work.sortBy(_.id).foreach { t =>
        val elapsed = now - t.tmp

        if(elapsed >= SERVER_TIMEOUT){
          t.p.success(false)

          queue.remove(t)

        } else if(!t.keys.exists(keys.contains(_))) {

          running.put(t.id, t)
          keys = keys ++ t.keys

          t.p.success(true)

          queue.remove(t)
        } else {
          //t.p.success(false)
        }
      }
    }
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive = {

    case MemberUp(member) =>
      log.info(s"${Console.BLUE}Member is Up: {}${Console.RESET}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)
    case _: MemberEvent => // ignore

    case cmd: Enqueue =>
      val t = Transaction(cmd.id, cmd.keys)
      queue.add(t)
      t.p.future.pipeTo(sender)

    case cmd: Release => running.remove(id)

    case cmd: String => sender ! "world"
      //(proxy ? cmd).pipeTo(sender)

    case _ =>
  }
}
