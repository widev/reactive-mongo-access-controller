package accesscontroller

import reactivemongo.bson.{BSONDateTime, BSONObjectID, BSONDocument}
import scala.concurrent.{ExecutionContext, Future}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.indexes.{IndexType, Index}
import org.joda.time.DateTime

import reactivemongo.core.commands.{Count, LastError}
import com.typesafe.config.ConfigFactory
import reactivemongo.api.{Cursor, MongoDriver}

import models._
import errors._
import wrapper._

/**
 *
 * The main controller which give access to the users, user groups and sessions controllers.
 * It also permit to interact directly with the database.
 *
 * @param ec Current [[scala.concurrent.ExecutionContext]]
 */
class AccessController(implicit ec: ExecutionContext) {
  private val store = new Store

  lazy val users: Users = new Users(store)(sessions, userGroups)
  lazy val userGroups: UserGroups = new UserGroups(store)(users)
  lazy val sessions: Sessions = new Sessions(store)(users)

  /**
   *
   * It should be your only way to get an [[accesscontroller.AccessControlDB]] instance.
   *
   * @param uri The database server address
   * @param name The database name
   * @return An instance of [[accesscontroller.AccessControlDB]]
   */
  def db(uri: String, name: String) = AccessControlDB(uri, name)(users, userGroups, sessions)
}

/**
 *
 * Contains every necessary objects to interact with the database without access controls.
 *
 * @param ec Current [[scala.concurrent.ExecutionContext]]
 */
class Store(implicit ec: ExecutionContext) {
  val config = ConfigFactory.load.getConfig("access-controller.store")
  val driver = new MongoDriver
  val connection = driver.connection(Seq(config.getString("uri")))
  val db = connection.db(config.getString("db"))

  /**
   *
   * Gets or create a [[reactivemongo.api.collections.default.BSONCollection]] from its name
   *
   * @param name Name of the collection
   * @return [[reactivemongo.api.collections.default.BSONCollection]]
   */
  def collection(name: String) = db[BSONCollection](name)
}

/**
 *
 * The users controller class permits users handling.
 *
 * @param store Instance of [[accesscontroller.Store]] to permit db access without access controls on it
 * @param s [[accesscontroller.Sessions]] ref
 * @param ug [[accesscontroller.UserGroups]] controller ref
 * @param ec Current [[scala.concurrent.ExecutionContext]]
 */
class Users(private val store: Store)(s: =>Sessions, ug: =>UserGroups)(implicit ec: ExecutionContext) {
  private val config = ConfigFactory.load.getConfig("access-controller.users")
  private val collection = store.collection(config.getString("collection"))

  lazy val sessions = s
  lazy val userGroups = ug

  // ensure that two users cannot have the same username
  collection.indexesManager.ensure(
    Index(List("credentials.username" -> IndexType.Ascending), unique = true)
  )

  /**
   *
   * Gets a user from its [[reactivemongo.bson.BSONObjectID]].
   *
   * If the user doesn't exists, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * @param userId [[accesscontroller.User._id]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[User]}
   */
  def getUser(userId: BSONObjectID)(implicit ec: ExecutionContext): Future[User] =
    collection.find(BSONDocument("_id" -> userId)).cursor[User].headOption.flatMap {
      case Some(user) => Future.successful(user)
      case _ => Future.failed(NoMatchingUserException(userId.stringify))
    }

  /**
   *
   * Gets users from a {Set[BSONObjectID]}.
   *
   * If some users don't exists, this method doesn't throw. If you want to verify some users existence, use the
   * [[accesscontroller.Users.checkUsers]] method instead.
   *
   * @param users [[accesscontroller.User._id]]s you want to get
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Cursor[User]} which can be used to enumerate each found users
   */
  def getUsers(users: Set[BSONObjectID])(implicit ec: ExecutionContext): Cursor[User] =
    collection.find(BSONDocument("_id" -> BSONDocument("$in" -> users))).cursor[User]

  /**
   *
   * Checks that all the user ids exists
   *
   * @param users [[accesscontroller.User._id]]s you want to check
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {true} if every ids are existent
   */
  def checkUsers(users: Set[BSONObjectID])(implicit ec: ExecutionContext): Future[Boolean] = {
    store.db.command(Count(collection.name, query = Some(BSONDocument("_id" -> BSONDocument("$in" -> users))))).flatMap {
      case c if c == users.size => Future.successful(true)
      case _ => Future.successful(false)
    }
  }

  /**
   *
   * Creates a new user from its [[accesscontroller.User]] model
   *
   * @param user [[accesscontroller.User]] model
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[User]} containing the created [[accesscontroller.User]]
   */
  def createUser(user: User)(implicit ec: ExecutionContext): Future[User] =
    collection.insert(User.Handler.write(user)).flatMap { _ =>
      Future.successful { user }
    }

  /**
   *
   * Connects an existent user from its [[accesscontroller.Credentials]]
   *
   * If the [[accesscontroller.Credentials]] are unknown, it throws a {NoMatchingUserException}
   *
   * This method can change the {AccessContext}, so you should implicitly map it
   *
   * Example:
   * {{{
   * implicit val ac = ??? // your initial access context
   *
   * accessController.users.connectUser(Credentials("username", "password")).map { implicit ac =>
   *  // your code using the new implicit {AccessContext} here ...
   * }
   * }}}
   *
   * @param credentials [[accesscontroller.Credentials]] of the user
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[AccessContext]} containing the new [[accesscontroller.AccessContext]]
   */
  def connectUser(credentials: Credentials)(implicit ec: ExecutionContext): Future[AccessContext] =
    collection.find(BSONDocument("credentials" -> Credentials.Handler.write(credentials))).cursor[User].headOption.flatMap {
      case Some(user) => sessions.createSession(Session(userId = user._id)).flatMap { session =>
        Future.successful { AccessContext(user, Some(session)) }
      }
      case _ => Future.failed(NoMatchingUserException())
    }

  /**
   *
   * Connects an existent user from its [[accesscontroller.Session]] token
   *
   * If the token is unknown or expired, a [[accesscontroller.NotValidSessionException]] is thrown
   * If the found session correspond to an unknown user, a [[accesscontroller.NoMatchingUserException]] is thrown
   *
   * This method can change the [[accesscontroller.AccessContext]], so you should implicitly map it
   *
   * Example:
   * {{{
   * implicit val ac = ??? // your initial access context
   *
   * accessController.users.connectUser("token").map { implicit ac =>
   *  // your code using the new implicit {AccessContext} here ...
   * }
   * }}}
   *
   * @param token Session token of the user you want to connect
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[AccessContext]} with the new [[accesscontroller.AccessContext]]
   */
  def connectUser(token: String)(implicit ec: ExecutionContext): Future[AccessContext] =
    sessions.getSession(token).flatMap { session =>
      collection.find(BSONDocument("_id" -> session.userId)).cursor[User].headOption.flatMap {
        case Some(user) => Future.successful { AccessContext(user, Some(session)) }
        case _ =>
          sessions.deleteSession(session.token)
          Future.failed(NotValidSessionException(session.token))
      }
    }

  /**
   *
   * Disconnects a user from its session.
   *
   * If the current [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]]
   * or a [[accesscontroller.ExpiredSessionException]] can be thrown.
   *
   * This method can change the [[accesscontroller.AccessContext]], so you should implicitly map it
   *
   * Example:
   * {{{
   * implicit val ac = ??? // your initial access context
   *
   * accessController.users.disconnectUser.map { implicit ac =>
   *  // your code using the new implicit {AccessContext} here ...
   * }
   * }}}
   *
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[AccessContext]} with the new [[accesscontroller.AccessContext]]
   */
  def disconnectUser(implicit ac: AccessContext, ec: ExecutionContext): Future[AccessContext] =
    ac.check { implicit ac =>
      sessions.deleteSession(ac.session.get.token).flatMap { _ =>
        Future.successful { ac.copy(session = None) }
      }
    }

  /**
   *
   * Deletes the [[accesscontroller.AccessContext.user]].
   *
   * If the user is unknown, a [[accesscontroller.NoMatchingUserException]] is thrown,
   * If the [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]] or
   * a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * After deleting the [[accesscontroller.AccessContext.user]], your current [[accesscontroller.AccessContext]] won't
   * be consistent anymore. It's up to you to create a new consistent one.
   *
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[Unit]}
   */
  def deleteUser(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    ac.check { implicit ac =>
      getUser(ac.user._id).flatMap { user =>
        Future.traverse(user.groups) { groupId =>
          userGroups.deleteUserFromUserGroup(groupId, user._id).recover {
            case _: OwnerCannotBeDeletedFromItsUserGroupException =>
              userGroups.deleteUserGroup(groupId)
          }
        }
      }.flatMap { _ =>
        for {
          _ <- collection.remove(BSONDocument("_id" -> ac.user._id))
          _ <- sessions.deleteSession
        } yield {}
      }
    }

  private[accesscontroller] def updateUsersUserGroupList(users: Set[BSONObjectID], groupId: BSONObjectID, opcode: String)(implicit ec: ExecutionContext): Future[Unit] =
    collection.update(
      BSONDocument("_id" -> BSONDocument("$in" -> users)), BSONDocument(opcode -> BSONDocument("groups" -> groupId)), multi = true
    ).flatMap { _ => Future.successful() }

  private[accesscontroller] def addUserGroupToUser(groupId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    addUserGroupToUsers(Set(ac.user._id), groupId)

  private[accesscontroller] def addUserGroupToUsers(users: Set[BSONObjectID], groupId: BSONObjectID)(implicit ec: ExecutionContext): Future[Unit] =
    checkUsers(users).flatMap {
      case true => updateUsersUserGroupList(users, groupId, "$addToSet")
      case _ => Future.failed(NoMatchingUserException())
    }

  private[accesscontroller] def removeUserGroupFromUser(groupId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    removeUserGroupFromUsers(Set(ac.user._id), groupId)

  private[accesscontroller] def removeUserGroupFromUsers(users: Set[BSONObjectID], groupId: BSONObjectID)(implicit ec: ExecutionContext): Future[Unit] =
    updateUsersUserGroupList(users, groupId, "$pull")

}

/**
 *
 * The sessions controller permits to handle your users' sessions.
 *
 * @param store [[accesscontroller.Store]]
 * @param u [[accesscontroller.Users]] ref
 * @param ec Current [[scala.concurrent.ExecutionContext]]
 */
class Sessions(private val store: Store)(u: =>Users)(implicit ec: ExecutionContext) {
  lazy val users = u

  private val config = ConfigFactory.load.getConfig("access-controller.sessions")
  private val collection: BSONCollection = store.collection(config.getString("collection"))

  // ensure each sessions has a unique token
  collection.indexesManager.ensure(
    Index(List("token" -> IndexType.Ascending), unique = true)
  )

  /**
   *
   * Create a new [[accesscontroller.Session]]
   *
   * If the attached user doesn't exists, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * @param session [[accesscontroller.Session]] model
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[Session]} containing the created [[accesscontroller.Session]]
   */
  def createSession(session: Session)(implicit ec: ExecutionContext): Future[Session] =
    users.getUser(session.userId).flatMap { _ =>
      collection.insert(session).flatMap { _ =>
        Future.successful { session }
      }
    }

  /**
   *
   * Gets a [[accesscontroller.Session]] from its token.
   *
   * If the token is not found, a [[accesscontroller.NoMatchingSessionException]] is thrown.
   * If the session is expired, a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * @param token [[accesscontroller.Session.token]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[Session]} containing the found [[accesscontroller.Session]]
   */
  def getSession(token: String)(implicit ec: ExecutionContext): Future[Session] =
    collection.find(BSONDocument("token" -> token)).cursor[Session].headOption.flatMap {
      case Some(session) if session.expirationDate.isAfterNow => Future.successful { session }
      case Some(session) =>
        deleteSession(session.token)
        Future.failed(ExpiredSessionException(session.token))
      case _ => Future.failed(NoMatchingSessionException(token))
    }

  /**
   *
   * Deletes a [[accesscontroller.Session]] from the current [[accesscontroller.AccessController]]
   *
   * If the [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]] or
   * a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * This method can change the [[accesscontroller.AccessContext]], so you should implicitly map it
   *
   * Example:
   * {{{
   * implicit val ac = ??? // your initial access context
   *
   * accessController.sessions.deleteSession.map { implicit ac =>
   *  // your code using the new implicit {AccessContext} here ...
   * }
   * }}}
   *
   * @param ac Current [[accesscontroller.AccessController]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[AccessContext]} containing the new [[accesscontroller.AccessContext]]
   */
  def deleteSession(implicit ac: AccessContext, ec: ExecutionContext): Future[AccessContext] =
    ac.session match {
      case Some(session) =>
        deleteSession(session.token).flatMap { _ =>
          Future.successful { ac.copy(session = None) }
        }
      case _ => Future.successful { ac }
    }

  /**
   *
   * Deletes a [[accesscontroller.Session]] from its token.
   *
   * If the [[accesscontroller.Session.token]] is unknown, a [[accesscontroller.NoMatchingSessionException]] is thrown.
   *
   * @param token [[accesscontroller.Session.token]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[Unit]}
   */
  def deleteSession(token: String)(implicit ec: ExecutionContext): Future[Unit] =
    collection.remove(BSONDocument("token" -> token)).flatMap {
      case result: LastError if result.updated > 0 =>
        Future.successful()
      case _ =>
        Future.failed { NoMatchingSessionException(token) }
    }

  /**
   *
   * Deletes every expired [[accesscontroller.Session]]s
   *
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[Int]} containing the number of deleted [[accesscontroller.Session]]s
   */
  def clearSessions(implicit ec: ExecutionContext): Future[Int] =
    collection.remove(BSONDocument("expirationDate" -> BSONDocument("$lt" -> BSONDateTime(DateTime.now.getMillis)))).flatMap { res =>
      Future.successful { res.updated }
    }

}

/**
 *
 * The user groups controller permits to handle user groups
 *
 * @param store [[accesscontroller.Store]]
 * @param u [[accesscontroller.Users]] ref
 * @param ec Current [[scala.concurrent.ExecutionContext]]
 */
class UserGroups(private val store: Store)(u: =>Users)(implicit ec: ExecutionContext) {
  lazy val users = u

  private val config = ConfigFactory.load.getConfig("access-controller.user-groups")
  private val collection: BSONCollection = store.collection(config.getString("collection"))

  /**
   *
   * Gets a [[accesscontroller.UserGroup]].
   *
   * If the [[accesscontroller.UserGroup._id]] doesn't exist, a [[accesscontroller.NoMatchingUserGroupException]] is thrown.
   *
   * @param groupId Searched [[accesscontroller.UserGroup._id]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[UserGroup]} containing the found [[accesscontroller.UserGroup]]
   */
  def getUserGroup(groupId: BSONObjectID)(implicit ec: ExecutionContext): Future[UserGroup] =
    collection.find(BSONDocument("_id" -> groupId)).cursor[UserGroup].headOption.flatMap {
      case Some(group) => Future.successful(group)
      case _ => Future.failed(NoMatchingUserGroupException(groupId.stringify))
    }

  /**
   *
   * Creates a [[accesscontroller.UserGroup]].
   *
   * If the [[accesscontroller.UserGroup.members]] contains an unknown [[accesscontroller.User._id]],
   * a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * If the [[accesscontroller.UserGroup.ownerId]] is unknown, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * @param userGroup A [[accesscontroller.UserGroup]] model to insert
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[UserGroup]} containing the created [[accesscontroller.UserGroup]]
   */
  def createUserGroup(userGroup: UserGroup)(implicit ec: ExecutionContext): Future[UserGroup] =
    collection.find(BSONDocument("_id" -> userGroup._id)).cursor[UserGroup].headOption.flatMap {
      case Some(group) => Future.failed(AlreadyExistsException(userGroup._id.stringify))
      case _ => users.addUserGroupToUsers(userGroup.users + userGroup.ownerId, userGroup._id).flatMap { _ =>
        collection.insert(userGroup).flatMap { _ => Future.successful { userGroup } }
      }
    }

  /**
   *
   * Creates a [[accesscontroller.UserGroup]].
   *
   * The [[accesscontroller.UserGroup.ownerId]] is set to the current [[accesscontroller.AccessContext.user._id]].
   *
   * If the [[accesscontroller.UserGroup.members]] contains an unknown [[accesscontroller.User._id]],
   * a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * If the [[accesscontroller.UserGroup.ownerId]] is unknown, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * If the [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]] or
   * a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * This method can change the [[accesscontroller.AccessContext]], so you should implicitly map it
   *
   * Example:
   * {{{
   * implicit val ac = ??? // your initial access context
   *
   * accessController.userGroups.createUserGroup("name", Set(user1, user2, ...)).map {
   *   case (group, ac_) => {
   *     implicit val ac = ac_ // in order to update the implicit access context
   *     // your code using the new implicit {AccessContext} here ...
   *   }
   * }
   * }}}
   *
   * @param name Name of the group
   * @param users Group members
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[(UserGroup, AccessContext)]} containing the created [[accesscontroller.UserGroup]] and the new
   *         [[accesscontroller.AccessContext]]
   */
  def createUserGroup(name: String, users: Set[BSONObjectID] = Set())(implicit ac: AccessContext, ec: ExecutionContext): Future[(UserGroup, AccessContext)] =
    ac.check { implicit ac =>
      createUserGroup(UserGroup(ownerId = ac.user._id, name = name, users = users)).flatMap { userGroup =>
        Future.successful(userGroup, ac.copy(user = ac.user.copy(groups = ac.user.groups + userGroup._id)))
      }
    }

  /**
   *
   * Checks the [[accesscontroller.UserGroup._id]] existence.
   *
   * @param groups [[accesscontroller.UserGroup._id]]s
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[Boolean]} containing {false} if one or more [[accesscontroller.UserGroup._id]] doesn't exists,
   *         otherwise {true}
   */
  def checkGroups(groups: Set[BSONObjectID])(implicit ec: ExecutionContext): Future[Boolean] =
    store.db.command(Count(collection.name, query = Some(BSONDocument("_id" -> BSONDocument("$in" -> groups))))).flatMap {
      case c if c == groups.size => Future.successful(true)
      case _ => Future.successful(false)
    }

  /**
   *
   * Puts one [[accesscontroller.User]] into a [[accesscontroller.UserGroup]]
   *
   * If the [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]] or
   * a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * If the [[accesscontroller.UserGroup._id]] is unknown, a [[accesscontroller.NoMatchingUserGroupException]] is thrown.
   *
   * If the [[accesscontroller.User._id]] is unknown, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * If the [[accesscontroller.AccessContext.user]] isn't the owner of the [[accesscontroller.UserGroup]],
   * a [[accesscontroller.NoWriteAccessOnUserGroupException]] is thrown.
   *
   * @param groupId A [[accesscontroller.UserGroup._id]]
   * @param userId A [[accesscontroller.User._id]]
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return {Future[UserGroup]} containing the new version of the [[accesscontroller.UserGroup]]
   */
  def putUserInUserGroup(groupId: BSONObjectID, userId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[UserGroup] =
    ac.check { implicit ac => putUsersInUserGroup(groupId, Set(userId)) }

  private def updateUserGroupUsers(groupId: BSONObjectID, users: Set[BSONObjectID], opcode: String)(implicit ec: ExecutionContext) =
    collection.update(BSONDocument("_id" -> groupId), BSONDocument(opcode -> BSONDocument("users" -> BSONDocument("$each" -> users))))

  /**
   *
   * Puts some [[accesscontroller.User]]s into a [[accesscontroller.UserGroup]]
   *
   * Please see [[accesscontroller.UserGroups.putUserInUserGroup]] method for more details
   *
   * @param groupId A [[accesscontroller.UserGroup._id]]
   * @param userList Some [[accesscontroller.User._id]]s
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[UserGroup]} containing the new version of [[accesscontroller.UserGroup]]
   */
  def putUsersInUserGroup(groupId: BSONObjectID, userList: Set[BSONObjectID])(implicit ac: AccessContext, ec: ExecutionContext): Future[UserGroup] =
    ac.check { implicit ac =>
      val f1 = users.checkUsers(userList)
      val f2 = getUserGroup(groupId)
      f1.flatMap {
        case true => f2.flatMap {
          case group if group.ownerId == ac.user._id =>
            for {
              _ <- users.addUserGroupToUsers(userList, groupId)
              _ <- updateUserGroupUsers(groupId, userList, "$addToSet")
            } yield {
              group.copy(users = group.users ++ userList)
            }
          case _ => Future.failed(NoWriteAccessOnUserGroupException(groupId.stringify, ac.user._id.stringify))
        }
        case _ => Future.failed(NoMatchingUserException())
      }
    }

  /**
   *
   * Deletes a [[accesscontroller.User]] from a [[accesscontroller.UserGroup]].
   *
   * If the [[accesscontroller.UserGroup._id]] is unknown, a [[accesscontroller.NoMatchingUserGroupException]] is thrown.
   *
   * If the [[accesscontroller.User._id]] is unknown, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * @param groupId A [[accesscontroller.UserGroup._id]]
   * @param userId A [[accesscontroller.User._id]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[UserGroup]} containing the new version of [[accesscontroller.UserGroup]]
   */
  def deleteUserFromUserGroup(groupId: BSONObjectID, userId: BSONObjectID)(implicit ec: ExecutionContext): Future[UserGroup] =
    deleteUsersFromUserGroup(groupId, Set(userId))

  /**
   *
   * Deletes some [[accesscontroller.User]]s from a [[accesscontroller.UserGroup]].
   *
   * Please see the [[accesscontroller.UserGroups.deleteUsersFromUserGroup]] method for more details.
   *
   * @param groupId A [[accesscontroller.UserGroup._id]]
   * @param userList Some [[accesscontroller.User._id]]s
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[UserGroup]} containing the new version of [[accesscontroller.UserGroup]]
   */
  def deleteUsersFromUserGroup(groupId: BSONObjectID, userList: Set[BSONObjectID])(implicit ec: ExecutionContext): Future[UserGroup] =
    users.checkUsers(userList).flatMap {
      case true =>
        getUserGroup(groupId).flatMap {
          case group if !userList(group.ownerId) =>
            for {
              _ <- users.removeUserGroupFromUsers(userList, groupId)
              _ <- updateUserGroupUsers(groupId, userList, "$pull")
            } yield {
              group.copy(users = group.users -- userList)
            }
          case group => Future.failed(OwnerCannotBeDeletedFromItsUserGroupException(groupId.stringify, group.ownerId.stringify))
        }
      case _ => Future.failed(NoMatchingUserException())
    }

  /**
   *
   * Deletes a [[accesscontroller.UserGroup]].
   *
   * If the [[accesscontroller.AccessContext.user]] is not the owner of the [[accesscontroller.UserGroup]],
   * a [[accesscontroller.NoWriteAccessOnUserGroupException]] is thrown.
   *
   * If the [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]] or
   * a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * This method can change the [[accesscontroller.AccessContext]], so you should implicitly map it
   *
   * Example:
   * {{{
   * implicit val ac = ??? // your initial access context
   *
   * accessController.userGroups.deleteUserGroup("groupid").map { implicit ac =>
   *   // your code using the new implicit {AccessContext} here ...
   * }
   * }}}
   *
   * @param groupId A [[accesscontroller.UserGroup._id]]
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[AccessContext]} containing the new version of [[accesscontroller.AccessContext]]
   */
  def deleteUserGroup(groupId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[AccessContext] =
    ac.check { implicit ac =>
      getUserGroup(groupId).flatMap {
        case group if group.ownerId == ac.user._id => {
          for {
            _ <- users.removeUserGroupFromUsers(group.members, groupId)
            _ <- collection.remove(BSONDocument("_id" -> groupId))
          } yield {
            ac.copy(user = ac.user.copy(groups = ac.user.groups - groupId))
          }
        }
        case _ => Future.failed(NoWriteAccessOnUserGroupException(groupId.stringify, ac.user._id.stringify))
      }
    }

  /**
   *
   * Sets the [[accesscontroller.UserGroup.ownerId]] to a new one.
   *
   * If the [[accesscontroller.UserGroup._id]] is unknown, a [[accesscontroller.NoMatchingUserGroupException]] is thrown.
   *
   * If the [[accesscontroller.User._id]] is unknown, a [[accesscontroller.NoMatchingUserException]] is thrown.
   *
   * If the [[accesscontroller.AccessContext.user]] is not the [[accesscontroller.UserGroup]] owner,
   * a [[accesscontroller.NoWriteAccessOnUserGroupException]] is thrown.
   *
   * If the [[accesscontroller.AccessContext]] is inconsistent, a [[accesscontroller.NotValidAccessContextException]] or
   * a [[accesscontroller.ExpiredSessionException]] is thrown.
   *
   * @param groupId A [[accesscontroller.UserGroup._id]]
   * @param userId A [[accesscontroller.User._id]]
   * @param ac Current [[accesscontroller.AccessContext]]
   * @param ec Current [[scala.concurrent.ExecutionContext]]
   * @return A {Future[UserGroup]} containing the new version
   */
  def putUserGroupOwner(groupId: BSONObjectID, userId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[UserGroup] =
    ac.check { implicit ac =>
      val f1 = users.getUser(userId)
      val f2 = getUserGroup(groupId)
      f1.flatMap { user =>
        f2.flatMap {
          case group if group.ownerId == ac.user._id && group.ownerId != userId =>
            collection.update(BSONDocument("_id" -> groupId), BSONDocument("$set" -> BSONDocument("ownerId" -> userId))).map { _ =>
              users.addUserGroupToUser(groupId)(ac.copy(user = user), ec)
              group.copy(ownerId = userId)
            }
          case group if group.ownerId == userId => Future.successful(group)
          case _ => Future.failed(NoWriteAccessOnUserGroupException(groupId.stringify, ac.user._id.stringify))
        }
      }
    }

}