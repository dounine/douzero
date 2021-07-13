package com.dounine.douzero.core

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  StatusCodes
}
import akka.http.scaladsl.server.Directives.{
  complete,
  concat,
  extractDataBytes,
  get,
  path,
  post,
  withRequestTimeout
}
import akka.http.scaladsl.server.{Directive1, Route, ValidationRejection}
import akka.stream.{SourceShape, SystemMaterializer}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Source}
import akka.util.ByteString
import com.dounine.douzero.model.BaseSerializer
import org.json4s.{DefaultFormats, Formats, jackson}
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.{read, write}
import akka.{NotUsed, actor}
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.stream._
import akka.stream.scaladsl.{
  Concat,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  Partition,
  Sink,
  Source
}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.model.HttpEntity
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

object Player extends JsonParse {

  private val logger = LoggerFactory.getLogger(Player.getClass)

  sealed trait Event extends BaseSerializer

  case class PredictInfo(
      win: Map[String, String]
  ) extends Event

  case class CardInfo(
      lastMove: String,
      playedCards: Seq[String],
      playerCards: Seq[String],
      pushCardNum: Int,
      maxCardNum: Int,
      position: Int
  ) extends Event

  case class PushCard(
      actionPosition: Int,
      bombNum: Int,
      cardPlayActionSeq: Seq[String],
      players: Map[Int, CardInfo],
      threeLandlordCards: Seq[String]
  ) extends Event

  case class PushCardOk(
      request: PushCard,
      data: PredictInfo
  ) extends Event

  case class PushCardFinish(
      request: PushCard,
      winPosition: Int
  ) extends Event

  case class PushCardFail(
      request: PushCard,
      msg: String
  ) extends Event

  def createPlayer(
      system: ActorSystem[_]
  ): Flow[Event, Event, NotUsed] = {
    val predictUrl = system.settings.config.getString("jb.predict_url")
    val http = Http(system)
    implicit val ec = system.executionContext
    Flow[Event]
      .statefulMapConcat { () =>
        {
          case PushCardOk(request, data) => {
            logger.info("PushCardOk -> {}", data)
            var myCardInfo = request.players(request.actionPosition)
            val winCard =
              data.win.map(i => (i._1, i._2.toDouble)).toSeq.maxBy(_._2)
            val playedCard = winCard._1.split("")

            val otherPlayerPushCards = (ArrayBuffer(0, 1, 2) --= Array(
              request.actionPosition
            )).map(request.players(_).lastMove).mkString("")

            myCardInfo = myCardInfo.copy(
              lastMove = winCard._1,
              pushCardNum =
                if (winCard._1 != "") myCardInfo.pushCardNum + 1
                else myCardInfo.pushCardNum,
              maxCardNum =
                if (otherPlayerPushCards == "") myCardInfo.maxCardNum + 1
                else myCardInfo.maxCardNum,
              playerCards =
                (ArrayBuffer(myCardInfo.playerCards: _*) --= playedCard).toSeq,
              playedCards = myCardInfo.playedCards ++ playedCard
            )
            val bomb =
              if (
                winCard._1 == "XD" || winCard._1 == "DX" || winCard._1 == "大小" || winCard._1 == "小大" || (playedCard.length == 4 && playedCard
                  .forall(_ == playedCard.head))
              )
                1
              else 0

            val copyRequest = request.copy(
              actionPosition = (request.actionPosition + 1) % 3,
              bombNum = request.bombNum + bomb,
              cardPlayActionSeq =
                request.cardPlayActionSeq ++ Seq(winCard._1),
              players = request.players ++ Map(
                myCardInfo.position -> myCardInfo
              )
            )
//            println(s"p${myCardInfo.position} "+winCard._1)

            if (myCardInfo.playerCards.isEmpty) {
              PushCardFinish(request, request.actionPosition) :: Nil
            } else copyRequest :: Nil
          }
          case ee @ _ => ee :: Nil
        }
      }
      .mapAsync(1) {
        case r @ PushCardFinish(request, winPosition) => Future.successful(r)
        case request @ PushCard(
              actionPosition,
              bombNum,
              cardPlayActionSeq,
              players,
              threeLandlordCards
            ) => {
          val dizhu = players(0)
          val down = players(1)
          val up = players(2)
          val data = Map(
            "three_landlord_cards" -> threeLandlordCards.mkString(""),
            "player_position" -> actionPosition.toString,
            "bomb_num" -> bombNum.toString,
            "card_play_action_seq" -> cardPlayActionSeq.mkString(","),
            "last_move_landlord" -> dizhu.lastMove,
            "last_move_landlord_down" -> down.lastMove,
            "last_move_landlord_up" -> up.lastMove,
            "num_cards_left_landlord" -> dizhu.playerCards.size.toString,
            "num_cards_left_landlord_down" -> down.playerCards.size.toString,
            "num_cards_left_landlord_up" -> up.playerCards.size.toString,
            "player_hand_cards" -> players(actionPosition).playerCards
              .mkString(""),
            "other_hand_cards" -> (ArrayBuffer(0, 1, 2) --= Array(
              actionPosition
            )).flatMap(players(_).playerCards).mkString(""),
            "played_cards_landlord" -> dizhu.playedCards.mkString(""),
            "played_cards_landlord_down" -> down.playedCards
              .mkString(""),
            "played_cards_landlord_up" -> up.playedCards.mkString("")
          )
//          println("-------- ")
//          println(data.mkString("\n"))
          val begin = LocalDateTime.now()
          http
            .singleRequest(
              request = HttpRequest(
                uri = predictUrl,
                method = HttpMethods.POST,
                entity = HttpEntity(
                  contentType = MediaTypes.`application/x-www-form-urlencoded`,
                  string = data
                    .map(i => s"${i._1}=${i._2}")
                    .mkString("&")
                )
              )
            )
            .recover {
              case e => PushCardFail(request = request, msg = e.getMessage)
            }
            .flatMap {
              case HttpResponse(_, _, entity, _) =>
                entity.dataBytes
                  .runFold(ByteString.empty)(_ ++ _)(
                    SystemMaterializer(system).materializer
                  )
                  .map(_.utf8String.jsonTo[Map[String, Any]])
                  .map(result => {
//                    logger.info(
//                      "time -> " + java.time.Duration
//                        .between(begin, LocalDateTime.now())
//                    )
                    if (result("status").asInstanceOf[BigInt] != 0) {
                      logger.error(result.toJson)
                      PushCardFail(
                        request,
                        result("message").asInstanceOf[String]
                      )
                    } else
                      PushCardOk(
                        request,
                        PredictInfo(
                          win = result("win_rates")
                            .asInstanceOf[Map[String, String]]
                        )
                      )
                  })

              case msg @ _ => {
                logger.error(msg.toString)
                Future.failed(new Exception(s"请求失败 $msg"))
              }
            }
        }
      }
  }

  val timeoutResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(
      ContentTypes.`application/json`,
      """{"code":"fail","msg":"service timeout"}"""
    )
  )

  case class PkEntity(
      p0: String,
      p1: String,
      p2: String
  ) extends BaseSerializer


  case class MergeInfo(
      url: String,
      count: Int,
      sleep: Int
  )
  def apply(implicit system: ActorSystem[_]): Route = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    val http = Http(system)

    concat(
      get {
        path("sleep" / IntNumber / IntNumber) { (id: Int, sleep: Int) =>
          {
            complete(
              Source
                .single(Map("code" -> "ok", "id" -> id))
                .delay(sleep.milliseconds)
            )
          }
        }
      },
      post {
        path("merge") {
          entity(as[MergeInfo]) {
            data =>
              {
                complete(
                  Source(0 until data.count)
                    .map(i => {
                      (i, LocalDateTime.now())
                    })
                    .mapAsync(1) {
                      id =>
                        http
                          .singleRequest(
                            HttpRequest(
                              method = HttpMethods.GET,
                              uri = data.url + s"/${id._1}/${data.sleep}"
                            )
                          )
                          .flatMap {
                            case HttpResponse(_, _, entity, _) =>
                              entity.dataBytes
                                .runFold(ByteString.empty)(_ ++ _)(
                                  SystemMaterializer(system).materializer
                                )
                                .map(_.utf8String.jsonTo[Map[String, Any]])
                                .map(r => {
                                  logger.info(
                                    "time -> " + java.time.Duration
                                      .between(id._2, LocalDateTime.now())
                                  )
                                  r
                                })
                                .map(Right.apply)
                            case msg =>
                              Future.successful(Left(new Exception("error")))
                          }
                    }
                    .fold(0)((s, i) => if (i.isRight) s + 1 else s)
                    .map(i => Map("code" -> "ok", "count" -> i))
                )
              }
          }
        } ~
          path("merge2") {
            entity(as[MergeInfo]) {
              data =>
                {
                  complete(
                    Source(0 until data.count)
                      .map(i => {
                        (i, LocalDateTime.now())
                      })
                      .mapAsync(1) {
                        id =>
                          http
                            .singleRequest(
                              HttpRequest(
                                method = HttpMethods.POST,
                                uri = data.url,
                                entity = HttpEntity(
                                  contentType =
                                    MediaTypes.`application/x-www-form-urlencoded`,
                                  string = Map(
                                    "three_landlord_cards" -> "",
                                    "player_position" -> "0",
                                    "bomb_num" -> "0",
                                    "card_play_action_seq" -> "",
                                    "last_move_landlord" -> "",
                                    "last_move_landlord_down" -> "",
                                    "last_move_landlord_up" -> "",
                                    "num_cards_left_landlord" -> "20",
                                    "num_cards_left_landlord_down" -> "17",
                                    "num_cards_left_landlord_up" -> "17",
                                    "player_hand_cards" -> "33344556JJJQK22XD5A2",
                                    "other_hand_cards" -> "477888999TTTQQKA23456667789TJQKKAA",
                                    "played_cards_landlord" -> "",
                                    "played_cards_landlord_down" -> "",
                                    "played_cards_landlord_up" -> ""
                                  ).map(i => s"${i._1}=${i._2}")
                                    .mkString("&")
                                )
                              )
                            )
                            .flatMap {
                              case HttpResponse(_, _, entity, _) =>
                                entity.dataBytes
                                  .runFold(ByteString.empty)(_ ++ _)(
                                    SystemMaterializer(system).materializer
                                  )
                                  .map(_.utf8String.jsonTo[Map[String, Any]])
                                  .map(r => {
                                    logger.info(
                                      "time -> " + java.time.Duration
                                        .between(id._2, LocalDateTime.now())
                                    )
                                    r
                                  })
                                  .map(Right.apply)
                              case msg =>
                                Future.successful(Left(new Exception("error")))
                            }
                      }
                      .fold(0)((s, i) => if (i.isRight) s + 1 else s)
                      .map(i => Map("code" -> "ok", "count" -> i))
                  )
                }
            }
          } ~
          path("merge3") {
            entity(as[MergeInfo]) {
              data =>
                {
                  complete(
                    Source(0 until data.count)
                      .mapAsync(1) {
                        id =>
                          http
                            .singleRequest(
                              HttpRequest(
                                method = HttpMethods.POST,
                                uri = data.url,
                                entity = HttpEntity(
                                  contentType =
                                    MediaTypes.`application/x-www-form-urlencoded`,
                                  string = Map(
                                    "three_landlord_cards" -> "",
                                    "player_position" -> "0",
                                    "bomb_num" -> "0",
                                    "card_play_action_seq" -> "",
                                    "last_move_landlord" -> "",
                                    "last_move_landlord_down" -> "",
                                    "last_move_landlord_up" -> "",
                                    "num_cards_left_landlord" -> "20",
                                    "num_cards_left_landlord_down" -> "17",
                                    "num_cards_left_landlord_up" -> "17",
                                    "player_hand_cards" -> "33344556JJJQK22XD5A2",
                                    "other_hand_cards" -> "477888999TTTQQKA23456667789TJQKKAA",
                                    "played_cards_landlord" -> "",
                                    "played_cards_landlord_down" -> "",
                                    "played_cards_landlord_up" -> ""
                                  ).map(i => s"${i._1}=${i._2}")
                                    .mkString("&")
                                )
                              )
                            )
                            .flatMap {
                              case HttpResponse(_, _, entity, _) =>
                                entity.dataBytes
                                  .runFold(ByteString.empty)(_ ++ _)(
                                    SystemMaterializer(system).materializer
                                  )
                                  .map(_.utf8String.jsonTo[Map[String, Any]])
                                  .map(Right.apply)
                              case msg =>
                                Future.successful(Left(new Exception("error")))
                            }
                      }
                      .fold(0)((s, i) => if (i.isRight) s + 1 else s)
                      .map(i => Map("code" -> "ok", "count" -> i))
                  )
                }
            }
          } ~
          path("pk2") {
            entity(as[PkEntity]) {
              data =>
                {
                  val begin = LocalDateTime.now()
                  val source = Source.single(
                    PushCard(
                      actionPosition = 0,
                      bombNum = 0,
                      cardPlayActionSeq = Seq.empty,
                      players = Map(
                        0 -> CardInfo(
                          lastMove = "",
                          playerCards = data.p0
                            .replace("B", "T")
                            .replace("大", "D")
                            .replace("小", "X")
                            .split(""),
                          playedCards = Seq.empty,
                          position = 0,
                          pushCardNum = 0,
                          maxCardNum = 0
                        ),
                        1 -> CardInfo(
                          lastMove = "",
                          playerCards = data.p1
                            .replace("B", "T")
                            .replace("大", "D")
                            .replace("小", "X")
                            .split(""),
                          playedCards = Seq.empty,
                          position = 1,
                          pushCardNum = 0,
                          maxCardNum = 0
                        ),
                        2 -> CardInfo(
                          lastMove = "",
                          playerCards = data.p2
                            .replace("B", "T")
                            .replace("大", "D")
                            .replace("小", "X")
                            .split(""),
                          playedCards = Seq.empty,
                          position = 2,
                          pushCardNum = 0,
                          maxCardNum = 0
                        )
                      ),
                      threeLandlordCards = "".split("")
                    )
                  )

                  val sourceData = Source.fromGraph(GraphDSL.create() {
                    implicit builder: GraphDSL.Builder[NotUsed] =>
                      {
                        import GraphDSL.Implicits._

                        val merge = builder.add(Merge[Event](inputPorts = 2))
                        val player = builder.add(createPlayer(system))
                        val merge2 = builder.add(Merge[Map[String, Any]](1))
                        val partition = builder.add(
                          Partition[Event](
                            2,
                            {
                              case PushCardFinish(request, winPosition) => 0
                              case PushCardFail(_, msg)                 => 0
                              case PushCardOk(_, _)                     => 1
                              case PushCard(_, _, _, _, _)              => 1
                            }
                          )
                        )

                        source ~> merge.in(0)
                        merge.out ~> player ~> partition

                        partition.out(0) ~> Flow[Event].collect {
                          case PushCardFinish(request, winPosition) => {
                            logger.info(
                              "request time -> " + java.time.Duration
                                .between(begin, LocalDateTime.now())
                            )
                            Map(
                              "code" -> "ok",
                              "data" -> Map(
                                "winPosition" -> request.actionPosition,
                                "boom" -> request.bombNum,
                                "p0" -> Map(
                                  "pushCardNum" -> request
                                    .players(0)
                                    .pushCardNum,
                                  "maxCardNum" -> request.players(0).maxCardNum
                                ),
                                "p1" -> Map(
                                  "pushCardNum" -> request
                                    .players(1)
                                    .pushCardNum,
                                  "maxCardNum" -> request.players(1).maxCardNum
                                ),
                                "p2" -> Map(
                                  "pushCardNum" -> request
                                    .players(2)
                                    .pushCardNum,
                                  "maxCardNum" -> request.players(2).maxCardNum
                                )
                              )
                            )
                          }
                          case PushCardFail(request, msg) => {
                            Map(
                              "code" -> "fail",
                              "msg" -> msg
                            )
                          }
                        } ~> merge2

                        partition.out(1) ~> Flow[Event].collect {
                          case e @ PushCardOk(request, data) => e
                          case e @ PushCard(
                                actionPosition,
                                bombNum,
                                cardPlayActionSeq,
                                players,
                                threeLandlordCards
                              ) =>
                            e
                        } ~> merge.in(1)

                        SourceShape(merge2.out)
                      }
                  })

                  complete(sourceData.take(1))
                }
            }
          }
      }
    )
  }
}
