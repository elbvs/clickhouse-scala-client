package com.crobox.clickhouse

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout
import com.crobox.clickhouse.balancing.HostBalancer
import com.crobox.clickhouse.balancing.discovery.ConnectionManagerActor.GetConnection
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest._
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

abstract class ClickhouseClientAsyncSpec(val config: Config = ConfigFactory.load())
    extends TestKit(ActorSystem("clickhouseClientAsyncTestSystem", config.getConfig("crobox.clickhouse.client")))
    with AsyncFlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  implicit val timeout: Timeout = 5.second

  override protected def afterAll(): Unit =
    try super.afterAll()
    finally Await.result(system.terminate(), 10.seconds)

  def requestParallelHosts(balancer: HostBalancer, connections: Int = 10): Future[Seq[Uri]] =
    Future.sequence(
      (1 to connections)
        .map(_ => {
          balancer.nextHost
        })
    )

  def getConnections(manager: ActorRef, connections: Int = 10): Future[Seq[Uri]] =
    Future.sequence(
      (1 to connections)
        .map(_ => {
          (manager ? GetConnection()).mapTo[Uri]
        })
    )

  //  TODO change this methods to custom matchers
  def returnsConnectionsInRoundRobinFashion(manager: ActorRef, expectedConnections: Set[Uri]): Future[Assertion] = {
    val RequestConnectionsPerHost = 100
    getConnections(manager, RequestConnectionsPerHost * expectedConnections.size)
      .map(connections => {
        expectedConnections.foreach(
          uri =>
            connections
              .count(_ == uri) shouldBe (RequestConnectionsPerHost +- RequestConnectionsPerHost / 10) //10% delta for warm-up phase
        )
        succeed
      })
  }
}
