package accesscontroller

import reactivemongo.api.collections.default.BSONCollection
import scala.concurrent.{Future, ExecutionContext, Await}
import reactivemongo.bson.{BSONObjectID, BSONDocument}
import scala.concurrent.duration._

trait TestUtils {
  import scala.concurrent.ExecutionContext.Implicits.global

  val access = new AccessController
  val store = new Store

  def cleanCollections(implicit ec: ExecutionContext): Unit =
    Await.result(store.db.collectionNames.flatMap { names =>
      Future.sequence(names.map {
        case "system.indexes" => Future.successful()
        case name => store.db[BSONCollection](name).remove(BSONDocument())
      })
    }, 1 second)

  def createRandomUsers(n: Int)(implicit ec: ExecutionContext): List[User] =
    Await.result(Future.traverse(List.fill(n)(User(credentials = Credentials(Session.token(), Session.token()))))(access.users.createUser), 1 second)

  def connectUsers(users: List[User])(implicit ec: ExecutionContext): List[AccessContext] =
    Await.result(Future.traverse(users) { user => access.users.connectUser(user.credentials) }, 1 second)

  def randomSession = Session(token = Session.token(), userId = BSONObjectID.generate)
}
