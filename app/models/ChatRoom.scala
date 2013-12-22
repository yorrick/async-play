package models

import akka.actor._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._

import akka.util.Timeout
import akka.pattern.ask


object Robot {

  def apply(chatRoom: ActorRef) {

    // Create an Iteratee that logs all messages to the console.
    val loggerIteratee = Iteratee.foreach[JsValue](event => Logger("robot").info(event.toString))

    implicit val timeout = Timeout(1 second)
    // Make the robot join the room
    chatRoom ? (Join("Robot")) map {
      case Connected(robotChannel) =>
        // Apply this Enumerator on the logger.
        robotChannel |>> loggerIteratee
    }

    // Make the robot talk every 30 seconds
    Akka.system.scheduler.schedule(
      30 seconds,
      30 seconds,
      chatRoom,
      Talk("Robot", "I'm still alive")
    )
  }

}

object ChatRoomManager {

  implicit val timeout = Timeout(1 second)

  lazy val chatRoom: ActorRef = {
    val chatRoom = Akka.system.actorOf(Props[ChatRoom])

    // Create a bot user (just for fun)
    Robot(chatRoom)

    chatRoom
  }

  lazy val pattern = new Regex("""@(\w*) .*""", "target")

  /**
   * Creates the (Iteratee, Enumerator) tuple to handle a websocket connection.
   * @param username
   * @return
   */
  def join(username:String): Future[(Iteratee[JsValue,_], Enumerator[JsValue])] =
    // send a message to chatRoom actor, asynchronously
    (chatRoom ? Join(username)).map {

      // chat room provides us the enumarator that will send data to the browser
      case Connected(enumerator) =>

        // Create an Iteratee to consume the feed send by browser
        val iteratee = Iteratee.foreach[JsValue] { event =>
          // sends Talk message to roomActor, synchronously (we don't need a result returned by a Future)
          val text = (event \ "text").as[String]
          chatRoom ! Talk(username, text)
        }.map { _ =>
          chatRoom ! Quit(username)
        }

        (iteratee,enumerator)

      case CannotConnect(error) =>
        // A finished Iteratee sending EOF (does not consume anything from the browser)
        val iteratee = Done[JsValue,Unit]((),Input.EOF)

        // Send an error and close the socket
        val enumerator =  Enumerator[JsValue](JsObject(Seq("error" -> JsString(error))))
                            .andThen(Enumerator.enumInput(Input.EOF))

        (iteratee,enumerator)
    }

}

class ChatRoom extends Actor {

//  var members = Set.empty[String]
  var members = scala.collection.mutable.Map.empty[String, Option[Concurrent.Channel[JsValue]]]

  // creates an enumerator that will be shared by all websockets
//  val (chatEnumerator, chatChannel) = Concurrent.broadcast[JsValue]
//  Concurrent.unicast[JsValue]()

  def receive = {

    case Join(username) => {
      if(members.contains(username)) {
        sender ! CannotConnect("This username is already used")
      } else {
        members = members + (username -> None)

        val enumerator = Concurrent.unicast[JsValue]{ channel =>
          members(username) = Some(channel)
        }

        self ! NotifyJoin(username)
        // send a Connected message back to the sender with a message that contains the shared Enumerator
        // the Connected message will be wrapped in a Future
        sender ! Connected(enumerator)
      }
    }

    case NotifyJoin(username) => {
      notify("join", username, "has entered the room")
    }

    case Talk(username, text) => {
      val targetUser: Option[String] = members.keys.map(memberName => s"@$memberName").filter(text.startsWith(_)).toList match {
        case member :: Nil => Some(member.replaceFirst("@", ""))
        case _ => None
      }

      notify("talk", username, text, targetUser)
    }

    case Quit(username) => {
      members = members - username
      notify("quit", username, "has left the room")
    }

  }

  def notify(kind: String, user: String, text: String, target: Option[String] = None) {
    val msg = JsObject(
      Seq(
        "kind" -> JsString(kind),
        "user" -> JsString(user),
        "message" -> JsString(text),
        "members" -> JsArray(
          members.keys.toList.map(JsString)
        )
      )
    )

    val filterFunction = {entry: (String, Option[Concurrent.Channel[JsValue]]) =>
      entry match {
        case (username, channel) =>
          target match {
            case None => true
            case Some(targetUsername) => username == targetUsername
          }
        case _ => false
      }
    }

    members.filter(filterFunction).values.foreach(_.foreach(_.push(msg)))
  }

}

case class Join(username: String)
case class Quit(username: String)
case class Talk(username: String, text: String)
case class NotifyJoin(username: String)

case class Connected(enumerator:Enumerator[JsValue])
case class CannotConnect(msg: String)
