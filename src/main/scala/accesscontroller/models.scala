package accesscontroller

import reactivemongo.bson._
import org.joda.time.DateTime
import com.typesafe.config.ConfigFactory

trait ModelIdentity {
  val id: BSONObjectID
}

case class AccessControlLists(readers: Set[BSONObjectID] = Set(), writers: Set[BSONObjectID] = Set())

object AccessControlLists {
  implicit val Handler = Macros.handler[AccessControlLists]
}

case class AccessControl(model: BSONDocument, accessLists: AccessControlLists = AccessControlLists())

object AccessControl {
  implicit val Handler = Macros.handler[AccessControl]
}

case class UserGroup(_id: BSONObjectID = BSONObjectID.generate, ownerId: BSONObjectID, name: String, users: Set[BSONObjectID] = Set()) {
  /**
   * @return All the available members
   */
  def members = users + ownerId
}

object UserGroup {
  implicit val Handler = Macros.handler[UserGroup]
}

case class Session(token: String = Session.token(), userId: BSONObjectID, createDate: DateTime = DateTime.now(), expirationDate: DateTime = DateTime.now().plusSeconds(Session.config.getInt("ttl")))

object Session {
  val config = ConfigFactory.load.getConfig("server.session")

  implicit object BSONDateTimeHandler extends BSONHandler[BSONDateTime, DateTime] {
    def read(d: BSONDateTime) = new DateTime(d.value)
    def write(d: DateTime) = BSONDateTime(d.getMillis)
  }

  implicit val Handler = Macros.handler[Session]

  val random = new scala.util.Random(new java.security.SecureRandom())
  def randomString(alphabet: String)(n: Int): String = Stream.continually(random.nextInt(alphabet.size)).map(alphabet).take(n).mkString
  def token(n: Int = 24) = randomString("abcdefghijklmnopqrstuvwxyz0123456789")(n)
}

case class Credentials(username: String, password: String, hashed: Boolean = false) {
  val hashedPassword = if (!hashed) Credentials.hash(password) else password
}

object Credentials {
  val config = ConfigFactory.load.getConfig("server.security")

  val md = java.security.MessageDigest.getInstance(config.getString("hashType"))
  def hash(password: String): String = md.digest((password + config.getString("add")).getBytes).map(_ & 0xFF).map(_.toHexString).mkString

  implicit object Handler extends BSONHandler[BSONDocument, Credentials] {
    def write(t: Credentials): BSONDocument = BSONDocument("username" -> t.username, "password" -> t.hashedPassword)
    def read(bson: BSONDocument): Credentials = Credentials(bson.getAs[String]("username").get, bson.getAs[String]("password").get, hashed = true)
  }
}

case class User(_id: BSONObjectID = BSONObjectID.generate, credentials: Credentials, groups: Set[BSONObjectID] = Set())

object User {
  implicit val Handler = Macros.handler[User]
}

case class AccessContext(user: User, session: Option[Session])