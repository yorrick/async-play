package models

import akka.actor._
import scala.concurrent.duration._

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

import models.actorroom._

class Receiver extends Actor {
  def receive = {
    case Received(from, js: JsValue) =>
      val msg = (js \ "text").as[String]
      context.parent ! Broadcast(from, ChatRoom.buildMsg("talk", from, msg))
  }
}

class BotReceiver extends Actor {
  def receive = {
    case Received(from, js: JsValue) =>
      play.Logger.info(s"Bot ${from} broadcasting ${js}")
      context.parent ! Broadcast(from, js)
  }
}

class BotSender extends Actor {
  def receive = {
    case s =>
      play.Logger.info(s"Bot should have sent ${s}")

  }
}

object ChatRoom {

  // initializes Room
  val wsm = Room(Props(classOf[ChatRoomSupervisor]))

  val botId = "robot"

  wsm.bot(botId, Props[BotSender], Props[BotReceiver]).map {robot =>
    Akka.system.scheduler.schedule(
      30 seconds,
      30 seconds,
      robot.receiver,
      Received(botId, ChatRoom.buildMsg("talk", botId, "I'm still alive"))
    )
  }

  class ChatRoomSupervisor extends Supervisor {

    def customBroadcast: Receive = {
      case Broadcast(from, js: JsObject) =>
        // adds members to all messages
        val ids = Json.obj("members" -> members.map(_._1))

        members.foreach {
          case (id, member) =>
            member.sender ! Broadcast(from, js ++ ids)

          case _ => ()
        }
    }

    override def receive = customBroadcast orElse super.receive
  }

  implicit val msgFormatter = new AdminMsgFormatter[JsValue]{
    def connected(id: String) = buildMsg("join", id, s"$id joined")
    def disconnected(id: String) = buildMsg("quit", id, s"$id quit")
    def error(id: String, msg: String) = buildMsg("msg", id, msg)
  }

  def buildMsg(kind: String, user: String, text: String): JsObject = {
    Json.obj(
      "kind" -> kind,
      "user" -> user,
      "message" -> text
    )
  }
}