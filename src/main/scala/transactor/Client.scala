package transactor

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import protocol._
import akka.actor.{ActorPath, ActorSystem}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.util.Timeout

import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern._
import com.typesafe.config.ConfigFactory

object Client {

  def main(args: Array[String]): Unit = {

    val rand = ThreadLocalRandom.current()

    val sequencers = Map(
      "0" -> "t-2551"
      ,"1" -> "t-2552"
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
      , ActorPath.fromString("akka.tcp://transactors@127.0.0.1:2552/system/receptionist")
    )

    val cli = system.actorOf(
      ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)),
      "client")

    implicit val timeout = Timeout(CLIENT_TIMEOUT seconds)

    def transfer(id: String, a1: String, a2: String): Future[Boolean] = {

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

      val p = Promise[Seq[Boolean]]
      system.scheduler.scheduleOnce(CLIENT_TIMEOUT milliseconds){
        p.success(Seq(false))
      }

      val tmp = System.currentTimeMillis()

      Future.firstCompletedOf(Seq(p.future, Future.sequence(locks).recover{case _ => Seq(false)})).map { results =>
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
      }.map { r =>

        val now = System.currentTimeMillis()
        println(s"\nTX DONE ${id} => ${r} time: ${now - tmp}ms")

        partitions.foreach { case (p, _) =>
          (cli ? ClusterClient.Send(s"/user/${sequencers(p)}", Release(id), localAffinity = false))
        }

        r
      }
    }

    var tasks = Seq.empty[Future[Boolean]]
    val len = accounts.size

    for(i<-0 until 1000){
      val a1 = rand.nextInt(0, len).toString
      val a2 = rand.nextInt(0, len).toString

      if(!a1.equals(a2)){
        tasks = tasks :+ transfer(i.toString, a1, a2)
      }
    }

    val t0 = System.currentTimeMillis()
    val results = Await.result(Future.sequence(tasks), 5 seconds)
    val elapsed = System.currentTimeMillis() - t0

    val n = results.length
    val hits = results.count(_ == true)

    val mb = moneyBefore.map(_._2).sum
    val ma = accounts.map(_._2.balance).sum

    assert(ma == mb)

    val rps = (n * 1000)/elapsed

    println(s"\nn: ${n} successes: ${hits} rate: ${(hits/n.toDouble) * 100} % req/s: ${rps} " +
      s"rate/s: ${(hits.toDouble/rps)*100} %\n")

    Await.ready(system.terminate(), 10 seconds)

    /*val t0 = System.currentTimeMillis()
    val f = (cli ? ClusterClient.Send(s"/user/t-2551", "Hello", localAffinity = false)).flatMap { r =>

      val elapsed = System.currentTimeMillis() - t0

      println(s"r ${r} elapsed: ${elapsed}ms\n")
      system.terminate()
    }

    Await.ready(f, 60 seconds)*/
  }

}
