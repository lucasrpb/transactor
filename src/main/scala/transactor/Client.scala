package transactor

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import protocol._

import akka.actor.{ActorPath, ActorSystem}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern._
import com.typesafe.config.ConfigFactory

object Client {

  def main(args: Array[String]): Unit = {

    val rand = ThreadLocalRandom.current()

    val sequencers = Map(
      "0" -> "t-2551"
      //,"1" -> "t-2552"
    )

    case class Account(id: String, var balance: Int)

    val accounts = TrieMap[String, Account]()

    var moneyBefore = Map.empty[String, Int]

    for(i<-0 until 1000){
      val id = i.toString
      val b = rand.nextInt(100, 1000)
      val a = new Account(id, b)
      accounts.put(id, a)

      moneyBefore = moneyBefore + (id -> b)
    }

    val config = ConfigFactory.load("client.conf")
    val system = ActorSystem("client", config)

    val initialContacts = Set(
      ActorPath.fromString("akka.tcp://transactors@127.0.0.1:2551/system/receptionist")
     // , ActorPath.fromString("akka.tcp://transactors@127.0.0.1:2552/system/receptionist")
    )

    val cli = system.actorOf(
      ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)),
      "client")

    implicit val timeout = Timeout(5 seconds)

    def transfer(i: Int, a1: String, a2: String): Future[Boolean] = {
      val id = i.toString//UUID.randomUUID.toString
      val tmp = System.currentTimeMillis()

      val acc1 = accounts(a1)
      val acc2 = accounts(a2)

      val keys = Seq(a1, a2)
      var partitions = Map.empty[String, Enqueue]

      keys.foreach { k =>
        val p = (k.toInt % sequencers.size).toString

        if(partitions.isDefinedAt(p)){
          val msg = partitions(p)
          msg.keys = msg.keys :+ k
        } else {
          partitions = partitions + (p -> Enqueue(id, Seq(k)))
        }
      }

      val locks = partitions.map { case (p, msg) =>
        (cli ? ClusterClient.Send(s"/user/${sequencers(p)}", msg, localAffinity = false))
      }.map(_.mapTo[Boolean])

      Future.sequence(locks).map { results =>

        if(results.exists(_ == false)){
          false
        } else {

          var b1 = acc1.balance
          var b2 = acc2.balance

          if(b1 == 0) {
            println(s"no money to transfer")
          } else {
            val ammount = rand.nextInt(0, b1)

            b1 = b1 - ammount
            b2 = b2 + ammount

            acc1.balance = b1
            acc2.balance = b2
          }

          true
        }
      }.recover{case _ => false}
        .map { r =>

          val now = System.currentTimeMillis()
          println(s"\nTX DONE ${id} => ${r} time: ${now - tmp}ms")

          partitions.foreach { case (p, t) =>
            (cli ? ClusterClient.Send(s"/user/${sequencers(p)}", Release(id), localAffinity = true))
          }

          r
        }
    }

    var tasks = Seq.empty[Future[Boolean]]

    for(i<-0 until 100){
      val a1 = rand.nextInt(0, accounts.size).toString
      val a2 = rand.nextInt(0, accounts.size).toString

      if(!a1.equals(a2)){
        tasks = tasks :+ transfer(i, a1, a2)
      }
    }

    //tasks = tasks :+ transfer(0, "0", "1")

    val results = Await.result(Future.sequence(tasks), 10 seconds)

    val n = results.length
    val hits = results.count(_ == true)
    val rate = (hits/n.toDouble)*100

    println(s"\nn: ${n} successes: ${hits} rate: ${rate}%\n")

    val mb = moneyBefore.map(_._2).sum
    val ma = accounts.map(_._2.balance).sum

    /*moneyBefore.keys.toSeq.sorted.foreach { id =>
      println(s"account $id before ${moneyBefore(id)} after ${accounts(id).balance}")
    }*/

    println(s"before: ${ma} after ${mb}\n")

    assert(ma == mb)

    Await.ready(system.terminate(), 10 seconds)

    /*val t0 = System.currentTimeMillis()
    val f = (cli ? ClusterClient.Send(s"/user/t-2551", true, localAffinity = false)).andThen {
      case r =>

        println(s"\n\nr => $r elapsed ${System.currentTimeMillis() - t0}ms\n\n")

        system.terminate()
    }

    Await.ready(f, 10 seconds)*/
  }

}
