package com.dounine.douzero.core

import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.{DefaultFormats, Formats, jackson}
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.{read, write}

class JsonParse extends Json4sSupport {

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

  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport
      .json()
      .withFramingRenderer(
        Flow[ByteString]
      )

}
