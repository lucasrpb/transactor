package transactor

import java.util.concurrent.ConcurrentLinkedDeque

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.client.ClusterClientReceptionist

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern._
import protocol._

import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise

class Transactor(val id: String) extends Actor {

  val log = context.system.log

  val queue = new ConcurrentLinkedDeque[Transaction]()
  var work = Seq.empty[Transaction]
  val running = TrieMap[String, Transaction]()

  val cluster = Cluster(context.system)
  ClusterClientReceptionist(context.system).registerService(self)

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])

    context.system.scheduler.schedule(10 milliseconds, 10 milliseconds){

      var work = Seq.empty[Transaction]
      val it = queue.iterator()

      while(it.hasNext){
        work = work :+ it.next()
      }

      var keys = running.map(_._2.keys).flatten.toSeq

      val now = System.currentTimeMillis()

      work.sortBy(_.id).foreach { t =>
        val elapsed = now - t.tmp

        if(elapsed >= TIMEOUT){

          println(s"${Console.RED}tx ${t.id} timed out...${Console.RESET}")

          t.p.success(false)
          queue.remove(t)


        } else if(!t.keys.exists(keys.contains(_))){

          println(s"${Console.BLUE}processing tx ${t.id}...${Console.RESET}")

          t.p.success(true)

          running.put(t.id, t)
          keys = keys ++ t.keys

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

    case cmd: Release =>
      running.remove(cmd.id)
      sender ! true

    case obj: Person =>
      log.info(s"received ${obj}\n")
      sender ! true

    case obj: Boolean => log.info(s"received ${obj}!\n")
      sender ! true

    case _ =>
  }
}
