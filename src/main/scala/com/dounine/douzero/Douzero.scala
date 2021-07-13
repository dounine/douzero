package com.dounine.douzero

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, get, path, withRequestTimeout}
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.stream.{FlowShape, Graph, SourceShape, SystemMaterializer}
import akka.http.scaladsl.server.Directives._
import com.dounine.douzero.core.{Player, Player2}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success}

object Douzero {

  private val logger = LoggerFactory.getLogger(Douzero.getClass)
  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem(Behaviors.empty[NotUsed], "douzero")
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer

    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    val cluster: Cluster = Cluster.get(system)
    val managementRoutes: Route = ClusterHttpManagementRoutes(cluster)
    val player: Route = Player(system)
    val player2: Route = Player2(system)
    val routers: Array[Route] = Array(player, player2)
    val rootRouter: RequestContext => Future[RouteResult] = Route.seal(
      withRequestTimeout(
        10.seconds,
        (_: HttpRequest) =>
          HttpResponse(
            StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              """{"code":"fail","msg":"service timeout"}"""
            )
          )
      )(
        concat(
          routers: _*
        )
      )
    )
    Http(system)
      .newServerAt(
        interface = "0.0.0.0",
        port = system.settings.config.getInt("jb.http.port")
      )
      .bind(concat(rootRouter, managementRoutes))
      .onComplete({
        case Failure(exception) => throw exception
        case Success(value) => {
          val config = system.settings.config
          logger.info(
            s">>>>> predict_url -> ${config.getString("jb.predict_url")}  <<<<<"
          )
          logger.info(
            s">>>>> running to port -> ${config.getInt("jb.http.port")} <<<<<"
          )
        }
      })

  }
}
