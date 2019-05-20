package transactor

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import com.typesafe.config.ConfigFactory

object Server {

  def main(args: Array[String]): Unit = {

    val port = args(0)

    val config = ConfigFactory.parseString(s"""
            akka.remote.netty.tcp.port=$port
        """).withFallback(ConfigFactory.load())
    val system = ActorSystem("transactors", config)

    val leader = system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[Leader]),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)), name = "leader")

    system.actorOf(Props(classOf[Coordinator], port), s"t-$port")
  }

}
