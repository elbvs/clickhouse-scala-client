package com.crobox.clickhouse.balancing

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, Supervision}
import akka.util.Timeout
import com.crobox.clickhouse.balancing.discovery.ConnectionManagerActor.{GetConnection, LogDeadConnections}
import com.crobox.clickhouse.balancing.discovery.cluster.ClusterConnectionFlow

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Host balancer that does a round robin on all the entries found in the `system.clusters` table.
 * It assumes that the service itself can access directly the clickhouse nodes and that the default port `8123` is used
 * for every node.
  **/
case class ClusterAwareHostBalancer(host: Uri,
                                    cluster: String = "cluster",
                                    manager: ActorRef,
                                    scanningInterval: FiniteDuration)(
    implicit system: ActorSystem,
    connectionRetrievalTimeout: Timeout,
    ec: ExecutionContext
) extends HostBalancer {

  ClusterConnectionFlow
    .clusterConnectionsFlow(Future.successful(host), scanningInterval, cluster)
    .withAttributes(
      ActorAttributes.supervisionStrategy({
        case ex: IllegalArgumentException =>
          logger.error("Failed resolving hosts for cluster, stopping the flow.", ex)
          Supervision.stop
        case ex =>
          logger.error("Failed resolving hosts for cluster, resuming.", ex)
          Supervision.Resume
      })
    )
    .runWith(Sink.actorRef(manager, LogDeadConnections, throwable => logger.error(throwable.getMessage, throwable)))

  override def nextHost: Future[Uri] =
    (manager ? GetConnection()).mapTo[Uri]
}
