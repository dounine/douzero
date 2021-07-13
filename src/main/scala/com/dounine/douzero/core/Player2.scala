package com.dounine.douzero.core

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{
  as,
  complete,
  concat,
  entity,
  path,
  post
}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Merge,
  Partition,
  Source,
  ZipWith
}
import akka.stream._
import akka.util.ByteString
import com.dounine.douzero.core.Player.PkEntity
import com.dounine.douzero.model.BaseSerializer
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}

object Player2 extends JsonParse {
  private val logger = LoggerFactory.getLogger(Player2.getClass)

  sealed trait Event extends BaseSerializer

  case class PredictData(
      three_landlord_cards: String,
      player_position: String,
      bomb_num: String,
      card_play_action_seq: String,
      last_move_landlord: String,
      last_move_landlord_down: String,
      last_move_landlord_up: String,
      num_cards_left_landlord: String,
      num_cards_left_landlord_down: String,
      num_cards_left_landlord_up: String,
      player_hand_cards: String,
      other_hand_cards: String,
      played_cards_landlord: String,
      played_cards_landlord_down: String,
      played_cards_landlord_up: String
  ) extends BaseSerializer

  /**
    * 玩家牌信息
    * @param lastMove 最后出的牌
    * @param playedCards 已经打出的牌
    * @param playerCards 还剩的牌
    * @param pushCardNum 出了多少次牌
    * @param maxCardNum 出的牌、对方都不要最大次数
    * @param bombNum 炸弹
    */
  case class PlayerCardInfo(
      lastMove: String = "",
      playedCards: Seq[String] = Nil,
      playerCards: Seq[String],
      pushCardNum: Int = 0,
      maxCardNum: Int = 0,
      bombNum: Int = 0
  ) extends BaseSerializer

  /**
    * 房间牌信息
    * @param cardPlayActionSeq 出牌顺序
    * @param threeLandlordCards 三张地主牌
    */
  case class RoomCardInfo(
      cardPlayActionSeq: Seq[String] = Nil,
      threeLandlordCards: Seq[String] = Nil
  ) extends BaseSerializer

  /**
    * 初始化牌面信息
    * @param p0 地主牌
    * @param p1 下家牌
    * @param p2 上家牌
    */
  case class InitCards(
      p0: PlayerCardInfo,
      p1: PlayerCardInfo,
      p2: PlayerCardInfo
  ) extends Event

  /**
    * 请出牌
    * @param position 位置
    */
  case class Please(
      position: Int
  ) extends Event

  /**
    * 预测出牌信息
    */
  case class PredictRequest(
      position: Int,
      data: PredictData
  ) extends Event

  /**
    * 预测成功结果
    * @param position 位置
    * @param win 出牌胜率信息
    */
  case class PredictOK(
      position: Int,
      win: Map[String, String]
  ) extends Event

  /**
    * 预测失败
    * @param msg 错误信息
    */
  case class PredictFail(
      msg: String
  ) extends Event

  /**
    * 出牌
    * @param position 位置
    * @param card 出牌
    */
  case class PushCard(
      position: Int,
      card: String
  ) extends Event

  /**
    * 出完赢了
    * @param position 位置
    */
  case class PushWin(
      position: Int,
      cards: Map[Int, PlayerCardInfo],
      room: RoomCardInfo,
      msg: Option[String] = None
  ) extends Event

  def createRoom(implicit
      system: ActorSystem[_]
  ): Flow[Event, Event, NotUsed] = {
    val predictUrl = system.settings.config.getString("jb.predict_url")
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val materializer: Materializer = SystemMaterializer(
      system
    ).materializer
    val http: HttpExt = Http(system)

    Flow[Event]
      .statefulMapConcat {
        var cards: Map[Int, PlayerCardInfo] = Map[Int, PlayerCardInfo]()
        var roomCards: RoomCardInfo = RoomCardInfo()

        () => {

          case InitCards(p0, p1, p2) =>
            cards = Map(
              0 -> p0,
              1 -> p1,
              2 -> p2
            )
            Please(position = 0) :: Nil
          case Please(position) =>
            val player: PlayerCardInfo = cards(position)
            val landlord: PlayerCardInfo = cards(0)
            val landlord_down: PlayerCardInfo = cards(1)
            val landlord_up: PlayerCardInfo = cards(2)
            PredictRequest(
              position = position,
              data = PredictData(
                three_landlord_cards =
                  roomCards.threeLandlordCards.mkString(""),
                player_position = position.toString,
                bomb_num = cards.values.map(_.bombNum).sum.toString,
                card_play_action_seq =
                  roomCards.cardPlayActionSeq.mkString(","),
                last_move_landlord = landlord.lastMove,
                last_move_landlord_down = landlord_down.lastMove,
                last_move_landlord_up = landlord_up.lastMove,
                num_cards_left_landlord = landlord.playerCards.size.toString,
                num_cards_left_landlord_down =
                  landlord_down.playerCards.size.toString,
                num_cards_left_landlord_up =
                  landlord_up.playerCards.size.toString,
                player_hand_cards = player.playerCards.mkString(""),
                other_hand_cards = (ArrayBuffer(0, 1, 2) --= Array(
                  position
                )).flatMap(cards(_).playerCards).mkString(""),
                played_cards_landlord = landlord.playedCards.mkString(""),
                played_cards_landlord_down =
                  landlord_down.playedCards.mkString(""),
                played_cards_landlord_up = landlord_up.playedCards.mkString("")
              )
            ) :: Nil
          case PredictOK(position, win) =>
            val winCard: String =
              win.map(i => (i._1, i._2.toDouble)).toSeq.maxBy(_._2)._1 //取胜率最高的牌
            PushCard(position = position, card = winCard) :: Nil
          case PredictFail(msg) =>
            PushWin(position = -1, cards, roomCards, Some(msg)) :: Nil
          case PushCard(position, card) =>
            roomCards = roomCards.copy(
              cardPlayActionSeq = roomCards.cardPlayActionSeq ++ Seq(card)
            )
            val myCardInfo: PlayerCardInfo = cards(position)
            val otherPlayerPushCards: String = (ArrayBuffer(0, 1, 2) --= Array(
              position
            )).map(cards(_).lastMove).mkString("")
            val bomb: Int =
              if (
                Array("XD", "DX", "大小", "小大").contains(
                  card
                ) || (card.length == 4 && card.distinct.length == 1)
              )
                1
              else 0
            cards = cards ++ Map(
              position -> myCardInfo.copy(
                lastMove = card,
                bombNum = myCardInfo.bombNum + bomb,
                pushCardNum =
                  if (card != "") myCardInfo.pushCardNum + 1
                  else myCardInfo.pushCardNum,
                maxCardNum =
                  if (otherPlayerPushCards == "")
                    myCardInfo.maxCardNum + 1
                  else myCardInfo.maxCardNum,
                playerCards = (ArrayBuffer(myCardInfo.playerCards: _*) --= card
                  .split("")).toSeq,
                playedCards = myCardInfo.playedCards ++ card.split("")
              )
            )

            if (cards(position).playerCards.isEmpty) {
              PushWin(position, cards, roomCards) :: Nil
            } else {
              Please(
                position = (position + 1) % 3
              ) :: Nil
            }

        }
      }
      .mapAsync(1) {
        case PredictRequest(
              position,
              data
            ) =>
          http
            .singleRequest(
              request = HttpRequest(
                uri = predictUrl,
                method = HttpMethods.POST,
                entity = HttpEntity(
                  contentType = MediaTypes.`application/x-www-form-urlencoded`,
                  string = data.toJson
                    .jsonTo[Map[String, String]]
                    .map(i => s"${i._1}=${i._2}")
                    .mkString("&")
                )
              )
            )
            .flatMap {
              case HttpResponse(_, _, entity, _) =>
                entity.dataBytes
                  .runFold(ByteString.empty)(_ ++ _)
                  .map(_.utf8String.jsonTo[Map[String, Any]])
                  .map(result => {
                    if (result.contains("win_rates")) {
                      PredictOK(
                        position = position,
                        win =
                          result("win_rates").asInstanceOf[Map[String, String]]
                      )
                    } else {
                      PredictFail(result("message").asInstanceOf[String])
                    }
                  })
              case msg => Future.successful(PredictFail(msg.toString))
            }
        case other => Future.successful(other)
      }
  }

  def process(
      p0: String,
      p1: String,
      p2: String,
      system: ActorSystem[_]
  ): Source[Map[String, Any], NotUsed] = {
    implicit val s = system
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer

    Source.fromGraph(GraphDSL.create() {
      implicit builder: GraphDSL.Builder[NotUsed] =>
        {
          import GraphDSL.Implicits._

          val action: SourceShape[InitCards] = builder.add(
            Source.single(
              InitCards(
                p0 = PlayerCardInfo(
                  playerCards = p0.split("")
                ),
                p1 = PlayerCardInfo(
                  playerCards = p1.split("")
                ),
                p2 = PlayerCardInfo(
                  playerCards = p2.split("")
                )
              )
            )
          )

          val broadcast: UniformFanOutShape[InitCards, InitCards] =
            builder.add(Broadcast[InitCards](2))
          val merge: UniformFanInShape[Event, Event] =
            builder.add(Merge[Event](2))
          val room: FlowShape[Event, Event] = builder.add(createRoom(system))
          val partition = builder.add(
            Partition[Event](
              2,
              {
                case PushWin(_, _, _, _)  => 1
                case Please(_)            => 0
                case PushCard(_, _)       => 0
                case PredictOK(_, _)      => 0
                case PredictFail(_)       => 0
                case PredictRequest(_, _) => 0
              }
            )
          )
          val zip: FanInShape2[InitCards, PushWin, Map[String, Any]] =
            builder.add(
              ZipWith[InitCards, PushWin, Map[String, Any]]((action, win) => {
                if (win.position >= 0) {
                  Map(
                    "code" -> "ok",
                    "data" -> Map(
                      "winPosition" -> win.position,
                      "boom" -> win.cards.values.map(_.bombNum).sum,
                      "p0" -> Map(
                        "pushCardNum" -> win.cards(0).pushCardNum,
                        "maxCardNum" -> win.cards(0).maxCardNum
                      ),
                      "p1" -> Map(
                        "pushCardNum" -> win.cards(1).pushCardNum,
                        "maxCardNum" -> win.cards(1).maxCardNum
                      ),
                      "p2" -> Map(
                        "pushCardNum" -> win.cards(2).pushCardNum,
                        "maxCardNum" -> win.cards(2).maxCardNum
                      )
                    )
                  )
                } else {
                  Map("code" -> "fail", "msg" -> win.msg.get)
                }
              })
            )

          /* ┌─────────────────────────────────────────────────────────────────────────────┐
           * │                                                                             │
           * │  ┌─────────┐   ┌───────────□  ┌─────────┐   ┌───────┐   ┌───────────┐       │
           * │  │ action  □──▶□ broadcast │─▶□  merge  □──▶□ room  □──▶□ partition □──┐    │
           * │  └─────────┘   └───────────□  └────□────┘   └───────┘   └─────□─────┘  │    │
           * │                      │             ▲                          │        │    │
           * │                      │             │                                   │    │
           * │                   action            ─ ─ ─ ─ please─ ─ ─ ─ ─ ─ ┘        │    │
           * │                      │                                                 │    │
           * │                      │                                                 │    │
           * │                      │          ┌─────────┐                            │    │
           * │                      └─────────▶□   zip   □◀────────────win or fail────┘    │
           * │                                 └────□────┘                                 │
           * │                                      │                                      │
           * │                                      │                                      │
           * │                                      ▼                                      │
           * └─────────────────────────────────────────────────────────────────────────────┘
           *                                    ┌────────┐
           *                                    │ result │
           *                                    └────────┘
           */
          action ~> broadcast ~> merge ~> room ~> partition

          broadcast ~> zip.in0

          partition.out(0) ~> merge

          partition.out(1) ~> Flow[Event].collect {
            case win @ PushWin(_, _, _, _) => win
          } ~> zip.in1

          SourceShape(zip.out)
        }
    })
  }

  def apply(implicit system: ActorSystem[_]): Route = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    concat(
      post {
        path("pk") {
          entity(as[PkEntity]) { data =>
            complete(
              process(
                p0 = data.p0,
                p1 = data.p1,
                p2 = data.p2,
                system = system
              )
            )
          }
        }
      }
    )
  }
}
