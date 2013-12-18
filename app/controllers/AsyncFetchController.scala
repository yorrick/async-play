package controllers

import play.api.mvc.{SimpleResult, Controller, Action}
import scala.concurrent._
import play.api.libs.ws.Response
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits._
import play.Logger
import play.api.libs.json._

import reactivemongo.api._
import play.modules.reactivemongo._
import play.modules.reactivemongo.json.collection.JSONCollection

import models._
import models.JsonFormats._


object AsyncController extends Controller with MongoController {

  def index = Action {
    Ok("Hello World")
  }

  def proxy = Action.async {
    val responseFuture: Future[Response] = WS.url("http://example.com").get()

    println("Before map")
    val resultFuture: Future[SimpleResult] = responseFuture.map {resp =>
      println("During map")
      Status(resp.status)(resp.body).as(resp.ahcResponse.getContentType)
    }
    println("After map")

    resultFuture
  }

  def withErrorHandling[T](f: Future[T], fallback: T): Future[T] = {
    f.recover { case t: Throwable =>
      fallback
    }
  }

  def getLatency(start: Long): Long = System.currentTimeMillis() - start

  def fetchWebSiteLatency(url: String): Future[(String, Long)] = {
    val start = System.currentTimeMillis()

    withErrorHandling(WS.url(url).get() map {r: Response =>
      Logger.info(s"url $url finished at " + System.currentTimeMillis())
      (url, getLatency(start))
    }, (url, -1))
  }

  val webSites = "http://google.com" :: "http://yahoo.com" :: "http://dfobjodfbocbcvob.com" :: Nil

  private def responseTime(future: Future[List[(String, Long)]]) = Action.async {
    val start = System.currentTimeMillis()

    future.map {list =>
      val allLatencies = getLatency(start)
      Ok(list.mkString(", ") + s" all latencies: $allLatencies")
    }
  }

  def serialFuture: Future[List[(String, Long)]] = webSites.foldLeft(Future(List.empty[(String, Long)])) {
    (previousFuture, next) ⇒
      for {
        previousResults ← previousFuture
        next ← fetchWebSiteLatency(next)
      } yield previousResults :+ next
  }

  def parallelFuture = Future.traverse(webSites)(fetchWebSiteLatency)

  def serialResponseTime = responseTime(serialFuture)
  def parallelResponseTime = responseTime(parallelFuture)





  def collection: JSONCollection = db.collection[JSONCollection]("persons")

  def create(name: String, age: Int) = Action.async {
    val user = User(name=name, age=age, feeds=List(Feed("Slashdot news", "http://slashdot.org/slashdot.rdf")))
    val futureResult = collection.insert(user)

    // when the insert is performed, send a OK 200 result
    futureResult.map(_ => Ok)
  }

  def find(name: String) = Action.async {

//    db.myCollection.find( { $where: "this.credits == this.debits" } );
//    Model.where( :$where => "sleep(100) || true" ).count

    // let's do our query
    val cursor: Cursor[User] = collection.
      // find all people with name `name`
//      find(Json.obj("name" -> name, "$where" -> "sleep(10000) || true")).
      find(Json.obj("name" -> name)).
      // sort them by creation date
//      sort(Json.obj("created" -> -1)).
      // perform the query and get a cursor of JsObject
      cursor[User]

    // gather all the JsObjects in a list
    val futureUsersList: Future[List[User]] = cursor.collect[List]()

    // everything's ok! Let's reply with the array
    futureUsersList.map { persons =>
      Ok(persons.toString)
    }
  }
}