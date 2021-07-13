package test.com.dounine.bilibili

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.{ClosedShape, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{
  BidiFlow,
  Flow,
  GraphDSL,
  RunnableGraph,
  Sink,
  Source,
  Zip
}
import akka.stream.typed.scaladsl.ActorSource
import akka.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
object ActorShutdown {
  def apply(): Behavior[String] =
    Behaviors.setup { context =>
      {
        implicit val materializer = Materializer(context)
        Behaviors.receiveMessage {
          case "stop" => {
            Behaviors.stopped
          }
          case "run" => {
            RunnableGraph
              .fromGraph(GraphDSL.create() { implicit builder =>
                {
                  import GraphDSL.Implicits._

                  val source =
                    builder.add(Source(1 to 10).throttle(1, 1.seconds))

                  val sink = builder.add(Sink.foreach[Int](i => {
                    println(i)
                  }))

                  source ~> sink

                  ClosedShape
                }
              })
              .run()
            Behaviors.same
          }
        }
      }
    }
}
sealed trait EE
case class Ping() extends EE
case class Pong() extends EE

object BidiGraphActor {
  sealed trait Event
  case class Run() extends Event
  case class GetData() extends Event
  case class Qrcode() extends Event
  case class Ok() extends Event
  case class Fail() extends Event
  case class Stop() extends Event
  def apply(out: ActorRef[Event]): Behavior[Event] =
    Behaviors.setup { context =>
      {
        implicit val ec = context.executionContext
        implicit val materializer = Materializer(context)
        val (actor, source) = ActorSource
          .actorRef[Event](
            completionMatcher = PartialFunction.empty,
            failureMatcher = PartialFunction.empty,
            bufferSize = 2,
            overflowStrategy = OverflowStrategy.dropHead
          )
          .preMaterialize()

        RunnableGraph
          .fromGraph(GraphDSL.create() { implicit builder =>
            {
              import GraphDSL.Implicits._

              val s = builder.add(source)
              val zip = builder.add(Zip[Event, Event]())
              val waitOk = builder.add(
                Flow[Event]
                  .collect {
                    case e @ Run() => Run()
                  }
                  .flatMapConcat(_ =>
                    Source(1 to 3)
                      .map(i =>
                        if (i == 3) {
                          Right(Qrcode())
                        } else Left(new Exception("not found"))
                      )
                      .throttle(1, 100.milliseconds)
                      .take(1)
                      .orElse(Source.single(Left(new Exception("not found"))))
                  )
              )

              ClosedShape
            }
          })
          .run()

        Behaviors.receiveMessage[Event] {
          case GetData() => {
            actor ! GetData()
            Behaviors.same
          }
          case Run() => {
            actor ! Run()
            Behaviors.same
          }
          case Stop() => {
            Behaviors.stopped
          }
        }
      }
    }
}

class StreamGraphTest
    extends ScalaTestWithActorTestKit()
    with Matchers
    with AnyWordSpecLike
    with LogCapturing {

  def code(msg: EE): String = {
    msg match {
      case Ping() => {
        println("code ping")
        "ping"
      }
      case Pong() => {
        println("code pong")
        "pong"
      }
    }
  }

  def decode(byte: String): EE = {
    byte match {
      case "ping" => {
        println("decode ping")
        Ping()
      }
      case "pong" => {
        println("decode pong")
        Pong()
      }
    }
  }

  "流" should {

    "hi" in {
      Source(0 until 3).runForeach(i=>info(i.toString))

//      info(Array(0,0,1,1,2,3,4).drop(1).mkString(","))
//      info((Set(1,2,3) &~ Set(1,2)).mkString(","))
//      Array(1,2,3,4).combinations(4).map(_.mkString(",")+"\n").foreach(info(_))
//      var a = 1
//      var b = 3
//
//      a = a ^ b
//      b = a ^ b
//      a = a ^ b
//
//      info(s"${a} -> ${b}")

    }

    "take" ignore {
      Source(1 to 3)
        .takeWhile(_ > 4)
        .map(Right.apply)
        .orElse(Source.single(Left(new Exception("not found"))))
        .runWith(Sink.head)
        .futureValue shouldBe Left(new Exception("not found"))
    }

    "bibi 2" ignore {
      val bidi: BidiFlow[Int, Long, ByteString, String, NotUsed] =
        BidiFlow.fromFlows(
          Flow[Int].map(x ⇒ x.toLong + 2),
          Flow[ByteString].map(_.decodeString("UTF-8"))
        )

      val bidi2 = BidiFlow.fromFlows(
        Flow[Long].map(_.toInt),
        Flow[String].map(ByteString(_))
      )

      val cc = bidi.atop(bidi2)

      val f1 = bidi.join(Flow[Long].map(_ => ByteString("")))

      bidi.reversed.join(Flow[String].map(_.toInt))

      val f = Flow[String]
        .map(Integer.valueOf(_).toInt)
        .join(bidi)

      Source(List(ByteString("1"), ByteString("2")))
        .via(f)
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(3, 4)
    }

    "bidi flow " ignore {

      val bidiFlow = BidiFlow.fromFunctions(code, decode)

      val pingpong: Flow[EE, Pong, NotUsed] = Flow[EE].collect {
        case Ping() => Pong()
        case Pong() => Pong()
      }
      val flow = bidiFlow.atop(bidiFlow.reversed).join(pingpong)

      Source
        .single(Pong())
        .via(flow)
        .runWith(Sink.head)
        .futureValue shouldBe Pong()
    }

    "test" ignore {
      Source(1 to 4)
        .map(i => {
          if (i < 3) {
            throw new Exception("error")
          } else i
        })
        .recoverWithRetries(
          1,
          {
            case e => {
              info("restart")
              Source
                .single(1)
                .map(i => {
                  if (i > 0) {
                    throw new Exception("error recover")
                  } else i
                })
            }
          }
        )
        .runForeach((i: Int) => {})

      TimeUnit.SECONDS.sleep(3)
    }

    "测试actor关闭 graph的运行情况" ignore {
      val actor = system.systemActorOf(ActorShutdown(), "hello")
      actor.tell("run")
      TimeUnit.SECONDS.sleep(1)
      actor.tell("stop")
      TimeUnit.SECONDS.sleep(2)
    }

    "flatMapConcat 子流 terminal情况" ignore {
      Source(1 to 3)
        .flatMapConcat(i => {
          Source
            .single(i)
            .watchTermination()((fp, f) => {
              info("shutdown")
            })
        })
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2, 3)
    }

    "1到3获取" ignore {
      val source = Source(1 to 3)
        .map(i => {
          if (i == 1) {
            Right(i)
          } else Left(new Exception("not found"))
        })
    }

    "slider" ignore {
      val source = Source(1 to 4)
      source
        .sliding(2)
        .runForeach(i => {
          println(i.toString())
        })
    }
  }

}
