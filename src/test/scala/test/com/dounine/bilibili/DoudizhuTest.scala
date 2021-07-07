package test.com.dounine.bilibili

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse, MediaTypes}
import akka.stream.{ClosedShape, Materializer, OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, RunnableGraph, Sink, Source, Zip}
import akka.stream.typed.scaladsl.ActorSource
import akka.util.ByteString
import com.dounine.douzero.core.Player.PushCardFail
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.{read, write}
import org.json4s.{DefaultFormats, Formats, jackson}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, TimeoutException}
import scala.concurrent.duration._

class DoudizhuTest
    extends ScalaTestWithActorTestKit()
    with Matchers
    with AnyWordSpecLike
    with LogCapturing {

  implicit val serialization: Serialization.type = jackson.Serialization
  implicit val formats: Formats = DefaultFormats

  implicit class ToJson(data: Any) {
    def toJson: String = {
      write(data)
    }

    def logJson: String = {
      write(
        Map(
          "__" -> data.getClass.getName,
          "data" -> data
        )
      )
    }
  }

  implicit class JsonTo(data: String) {
    def jsonTo[T: Manifest]: T = {
      read[T](data)
    }
  }

  "doudizhu" should {
    "http" in {
      val http = Http(system)
      implicit val ec = system.executionContext

      val result = http
        .singleRequest(
          request = HttpRequest(
            uri = "http://localhost:5000/predict",
            method = HttpMethods.POST,
            entity = HttpEntity(
              contentType = MediaTypes.`application/x-www-form-urlencoded`,
              string =
                "bomb_num=4&card_play_action_seq=JB9876%2C%2C4444%2C5555%2C%2C%2CQQQ8%2C%2C3333%2C%2C%2CB896J7%2C%2C%2C22%2C%E5%A4%A7%E5%B0%8F&last_move_landlord=%E5%A4%A7%E5%B0%8F&last_move_landlord_down=&last_move_landlord_up=22&num_cards_left_landlord=4&num_cards_left_landlord_down=17&num_cards_left_landlord_up=1&played_cards_landlord=JB98765555QQQ8%E5%A4%A7%E5%B0%8F&played_cards_landlord_down=&played_cards_landlord_up=44443333B896J722&other_hand_cards=JBKK2&player_hand_cards=6677899BJQKKAAAA2&player_hand_cards_alias=1%2C2%2C6%2C7%2C12%2C14%2C19%2C26%2C27%2C34%2C35%2C36%2C37%2C40%2C46%2C48%2C52%2C&player_position=1&three_landlord_cards="
            )
          )
        )
        .recover {
          case e => PushCardFail(request = null, msg = e.getMessage)
        }
        .flatMap {
          case HttpResponse(_, _, entity, _) =>
            entity.dataBytes
              .runFold(ByteString.empty)(_ ++ _)(
                SystemMaterializer(system).materializer
              )
              .map(_.utf8String.jsonTo[Map[String, Any]])
//              .map(result => {
//                PushCardOk(
//                  null,
//                  PredictInfo(
//                    win =
//                      result("win_rates").asInstanceOf[Map[String, String]]
//                  )
//                )
//              })

          case msg @ _ =>
            Future.failed(new Exception(s"请求失败 $msg"))
        }

      info(result.futureValue.toString)
    }
    "drop" ignore {
      val bb = ArrayBuffer(1,0,0,1,2,3) --= Set(0,3)
      info(bb.toString())
    }
    "repeat" ignore {
      Source
        .repeat(1)
        .throttle(10, 1.seconds)
        .runForeach(i => {})
    }
    "take" ignore {
      //{
      //	42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 40, 41,			//方块3、4、5、6、7、8、9、10、11、12、13、1、2
      //	29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 27, 28,			//梅花3、4、5、6、7、8、9、10、11、12、13、1、2
      //	16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 14, 15,			//红桃3、4、5、6、7、8、9、10、11、12、13、1、2
      //	 3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13,  1,  2,			//黑桃3、4、5、6、7、8、9、10、11、12、13、1、2
      //	53, 54														//小王、大王
      //};
      val cardMappings = Map(
        "A" -> Array("40", "27", "14", "1"),
        "2" -> Array("41", "28", "15", "2"),
        "3" -> Array("42", "29", "16", "3"),
        "4" -> Array("43", "30", "17", "4"),
        "5" -> Array("44", "31", "18", "5"),
        "6" -> Array("45", "32", "19", "6"),
        "7" -> Array("46", "33", "20", "7"),
        "8" -> Array("47", "34", "21", "8"),
        "9" -> Array("48", "35", "22", "9"),
        "10" -> Array("49", "36", "23", "10"),
        "J" -> Array("50", "37", "24", "11"),
        "Q" -> Array("51", "38", "25", "12"),
        "K" -> Array("52", "39", "26", "13")
      )

      val str = "3334"
      val myCards = "3,42,29,16,20,21,22,23,24,25,26"

      var pushCards = Seq.empty[String]

      str
        .split("")
        .foreach(card => {
          pushCards = pushCards ++ (cardMappings(card) diff pushCards).take(1)
        })

      info(pushCards.mkString(","))
    }
  }

}
