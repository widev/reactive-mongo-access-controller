package accesscontroller

import reactivemongo.api.collections.default.BSONCollection
import scala.concurrent.{Future, ExecutionContext, Await}
import reactivemongo.bson.BSONDocument
import scala.concurrent.duration._

trait TestUtils {
  def cleanCollections(implicit ec: ExecutionContext): Unit =
    Await.result(Store.db.collectionNames.flatMap { names =>
      Future.sequence(names.map {
        case "system.indexes" => Future.successful()
        case name => Store.db[BSONCollection](name).remove(BSONDocument())
      })
    }, 1 second)

  def createRandomUsers(n: Int)(implicit ec: ExecutionContext): List[User] =
    Await.result(Future.traverse(List.fill(n)(User(credentials = Credentials(Session.token(), Session.token()))))(Users.createUser), 1 second)

  def connectUsers(users: List[User])(implicit ec: ExecutionContext): List[AccessContext] =
    Await.result(Future.traverse(users) { user => Users.connectUser(user.credentials) }, 1 second)
}
