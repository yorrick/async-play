import scala.concurrent._

import org.specs2.mutable._
import reactivemongo.core.commands.LastError
import play.api.test._
import play.api.test.Helpers._

import models.User


/**
 * Specs2 tests
 */
class AsyncControllerSpec extends Specification {

  "createUser" should {

    "create a new person" in new WithApplication {
      val resultFuture: Future[LastError] = controllers.AsyncController.createUser(User(29, "toto", Nil))
      val result: LastError = Helpers.await[LastError](resultFuture)
      failure("toto")
      result.ok must beTrue
    }

  }
}