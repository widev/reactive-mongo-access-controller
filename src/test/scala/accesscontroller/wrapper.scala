package accesscontroller

import akka.testkit.TestKit
import akka.actor.ActorSystem
import org.scalatest.{MustMatchers, WordSpecLike}
import reactivemongo.bson.{BSONObjectID, Macros, BSONDocument}
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.iteratee.Iteratee
import com.typesafe.config.ConfigFactory

class WrapperTest extends TestKit(ActorSystem("Sessions")) with WordSpecLike with MustMatchers with TestUtils {

  implicit val ec = system.dispatcher

  val config = ConfigFactory.load.getConfig("access-controller.store")
  val db = access.db(config.getString("uri"), config.getString("db"))
  val collection = db("test")

  cleanCollections


  val user = createRandomUsers(1)(ec)(0)
  val user1 = createRandomUsers(1)(ec)(0)

  implicit val ac = AccessContext(user, None)


  case class TestModel(_id: BSONObjectID = BSONObjectID.generate, test: Boolean = true)
  object TestModel {
    implicit val Handler = Macros.handler[TestModel]
  }

  "Wrapper" must {

    "wrap a document and insert it, then find it" in {
      val doc = BSONDocument("_id" -> BSONObjectID.generate, "toto" -> BSONDocument("tutu" -> "tata"))
      Await.result(collection.insert(doc), 1 second).ok must be(right = true)

      Await.result(collection.find(doc).cursor.headOption, 1 second).get must be(doc)

      Await.result(collection.find(doc).cursor.enumerate().apply(Iteratee.foreach { el =>
        el must be(doc)
      }), 1 second)

      Await.result(collection.find(doc).cursor.collect[List](), 1 second) must be (List(doc))

    }

    "wrap a test model and insert it, then find it, update it and delete it" in {
      val model = TestModel()
      Await.result(collection.insert(model), 1 second).ok must be(right = true)

      Await.result(collection.find(BSONDocument("test" -> true)).cursor[TestModel].headOption, 1 second)

      Await.result(collection.find(model).cursor[TestModel].headOption, 1 second).get must be(model)

      Await.result(collection.find(model).cursor[TestModel].enumerate().apply(Iteratee.foreach { testModel =>
        testModel must be(model)
      }), 1 second)

      Await.result(collection.find(model).cursor[TestModel].collect[List](), 1 second) must be (List(model))

      {
        implicit val ac = AccessContext(User(credentials = Credentials(Session.token(), Session.token())), None)
        Await.result(collection.find(model).cursor[TestModel].collect[List](), 1 second).length must be(0)
      }

      Await.result(collection.setUserRights(model, user1._id, Rights.read), 1 second)

      {
        implicit val ac = AccessContext(user1, None)
        Await.result(collection.find(model).cursor[TestModel].headOption, 1 second).get must be(model)
      }

      Await.result(collection.update(BSONDocument("_id" -> model._id), model.copy(test = false)), 1 second).ok must be(right = true)
      Await.result(collection.find(BSONDocument("_id" -> model._id)).cursor[TestModel].headOption, 1 second).get.test must be(right = false)

      intercept[NoWriteAccessOnSelectedDataException[BSONDocument]] {
        implicit val ac = AccessContext(user1, None)
        Await.result(collection.update(BSONDocument("_id" -> model._id), model.copy(test = false)), 1 second).ok must be(right = true)
      }

      intercept[NoWriteAccessOnSelectedDataException[BSONDocument]] {
        implicit val ac = AccessContext(user1, None)
        Await.result(collection.remove(BSONDocument("_id" -> model._id)), 1 second)
      }

      Await.result(collection.remove(BSONDocument("_id" -> model._id)), 1 second).ok must be(right = true)

    }

  }

}
