package com.widev.wac

import reactivemongo.bson.{BSONDateTime, BSONObjectID, BSONDocument}
import scala.concurrent.{ExecutionContext, Future}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.indexes.{IndexType, Index}
import org.joda.time.DateTime

import reactivemongo.core.commands.{Count, LastError}

object Users {
  private val collection: BSONCollection = Store.db[BSONCollection]("users")

  {
    import scala.concurrent.ExecutionContext.Implicits.global

    collection.indexesManager.ensure(
      Index(List("credentials.username" -> IndexType.Ascending), unique = true)
    )
  }

  def getUser(userId: BSONObjectID)(implicit ec: ExecutionContext): Future[User] =
    collection.find(BSONDocument("_id" -> userId)).cursor[User].headOption.flatMap {
      case Some(user) => Future.successful { user }
      case _ => Future.failed { NoMatchingUserException(userId.stringify) }
    }

  def getUsers(users: Set[BSONObjectID])(implicit ec: ExecutionContext) =
    collection.find(BSONDocument("_id" -> BSONDocument("$in" -> users))).cursor[User]

  def checkUsers(users: Set[BSONObjectID])(implicit ec: ExecutionContext): Future[Boolean] = {
    Store.db.command(Count(collection.name, query = Some(BSONDocument("_id" -> BSONDocument("$in" -> users))))).flatMap {
      case c if c == users.size => Future.successful(true)
      case _ => Future.successful(false)
    }
  }

  def createUser(user: User)(implicit ec: ExecutionContext): Future[User] =
    collection.insert(User.Handler.write(user)).flatMap { _ =>
      Future.successful { user }
    }

  def connectUser(credentials: Credentials)(implicit ec: ExecutionContext): Future[AccessContext] =
    collection.find(BSONDocument("credentials" -> Credentials.Handler.write(credentials))).cursor[User].headOption.flatMap {
      case Some(user) => Sessions.createSession(Session(userId = user._id)).flatMap { session =>
        Future.successful { AccessContext(user, Some(session)) }
      }
      case _ => Future.failed(NoMatchingUserException())
    }

  def connectUser(token: String)(implicit ec: ExecutionContext): Future[AccessContext] =
    Sessions.getSession(token).flatMap { session =>
      collection.find(BSONDocument("_id" -> session.userId)).cursor[User].headOption.flatMap {
        case Some(user) => Future.successful { AccessContext(user, Some(session)) }
        case _ =>
          Sessions.deleteSession(session.token)
          Future.failed(NotValidSessionException(session.token))
      }
    }

  def disconnectUser(implicit ac: AccessContext, ec: ExecutionContext): Future[AccessContext] = ac.session match {
    case Some(session) => Sessions.deleteSession(session.token).flatMap { _ =>
      Future.successful { ac.copy(session = None) }
    }
    case _ => Future.failed(NotValidAccessContextException())
  }

  def deleteUser(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    getUser(ac.user._id).flatMap { user =>
      Future.traverse(user.groups) { groupId =>
        UserGroups.deleteUserFromUserGroup(groupId, user._id).recover {
          case _: OwnerCannotBeDeletedFromItsUserGroupException =>
            UserGroups.deleteUserGroup(groupId)
        }
      }
    }.flatMap { _ =>
      for {
        _ <- collection.remove(BSONDocument("_id" -> ac.user._id))
        _ <- Sessions.deleteSession
      } yield {}
    }

  sealed class Tools {
    private def updateUsersUserGroupList(users: Set[BSONObjectID], groupId: BSONObjectID, opcode: String)(implicit ec: ExecutionContext): Future[Unit] =
      collection.update(
        BSONDocument("_id" -> BSONDocument("$in" -> users)), BSONDocument(opcode -> BSONDocument("groups" -> groupId)), multi = true
      ).flatMap { _ => Future.successful() }

    /**
     * Add a groupId to the current user group list.
     *
     * This method should never been used externally because it doesn't update the concerned group model nor check the groupId.
     * To add a user to a group, please use the UserGroups object methods.
     *
     * @param groupId The user group id to add
     * @param ac The access context
     * @param ec The execution context
     * @return {{ Future[Unit] }}
     */
    def addUserGroupToUser(groupId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
      addUserGroupToUsers(Set(ac.user._id), groupId)

    /**
     * Add a groupId to plural users' group list.
     *
     * This method should never been used externally because it doesn't update the concerned group model nor check the groupId.
     * To add a user to a group, please use the UserGroups object methods.
     *
     * @param users A list of user id
     * @param groupId The group id to add to each users
     * @param ec The execution context
     * @return {{ Future[Unit] }}
     */
    def addUserGroupToUsers(users: Set[BSONObjectID], groupId: BSONObjectID)(implicit ec: ExecutionContext): Future[Unit] =
      checkUsers(users).flatMap {
        case true => updateUsersUserGroupList(users, groupId, "$addToSet")
        case _ => Future.failed(NoMatchingUserException())
      }

    def removeUserGroupFromUser(groupId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
      removeUserGroupFromUsers(Set(ac.user._id), groupId)

    def removeUserGroupFromUsers(users: Set[BSONObjectID], groupId: BSONObjectID)(implicit ec: ExecutionContext): Future[Unit] =
      updateUsersUserGroupList(users, groupId, "$pull")

  }

}

object Sessions {
  private val collection: BSONCollection = Store.db[BSONCollection]("sessions")

  {
    import scala.concurrent.ExecutionContext.Implicits.global

    collection.indexesManager.ensure(
      Index(List("token" -> IndexType.Ascending), unique = true)
    )
  }

  def createSession(session: Session)(implicit ec: ExecutionContext): Future[Session] =
    Users.getUser(session.userId).flatMap { _ =>
      collection.insert(session).flatMap { _ =>
        Future.successful { session }
      }
    }

  def getSession(token: String)(implicit ec: ExecutionContext): Future[Session] =
    collection.find(BSONDocument("token" -> token)).cursor[Session].headOption.flatMap {
      case Some(session) if session.expirationDate.isAfterNow => Future.successful { session }
      case Some(session) =>
        deleteSession(session.token)
        Future.failed(ExpiredSessionException(session.token))
      case _ => Future.failed(NoMatchingSessionException(token))
    }

  def deleteSession(implicit ac: AccessContext, ec: ExecutionContext): Future[AccessContext] = ac.session match {
    case Some(session) =>
      deleteSession(session.token).flatMap { _ =>
          Future.successful { ac.copy(session = None) }
      }
    case _ => Future.successful { ac }
  }

  def deleteSession(token: String)(implicit ec: ExecutionContext): Future[Boolean] =
    collection.remove(BSONDocument("token" -> token)).flatMap {
      case result: LastError if result.updated > 0 =>
        Future.successful { true }
      case _ =>
        Future.failed { NoMatchingSessionException(token) }
    }

  def clearSessions(implicit ec: ExecutionContext): Future[Int] =
    collection.remove(BSONDocument("expirationDate" -> BSONDocument("$lt" -> BSONDateTime(DateTime.now.getMillis)))).flatMap { res =>
      Future.successful { res.updated }
    }

}

object UserGroups {
  private val collection: BSONCollection = Store.db[BSONCollection]("user-groups")

  private val usersTools = new Users.Tools()

  def getUserGroup(groupId: BSONObjectID)(implicit ec: ExecutionContext): Future[UserGroup] =
    collection.find(BSONDocument("_id" -> groupId)).cursor[UserGroup].headOption.flatMap {
      case Some(group) => Future.successful(group)
      case _ => Future.failed(NoMatchingUserGroupException(groupId.stringify))
    }

  def createUserGroup(userGroup: UserGroup)(implicit ec: ExecutionContext): Future[UserGroup] =
    collection.find(BSONDocument("_id" -> userGroup._id)).cursor[UserGroup].headOption.flatMap {
      case Some(group) => Future.failed(AlreadyExistsException(userGroup._id.stringify))
      case _ => usersTools.addUserGroupToUsers(userGroup.users + userGroup.ownerId, userGroup._id).flatMap { _ =>
        collection.insert(userGroup).flatMap { _ => Future.successful { userGroup } }
      }
    }

  def createUserGroup(name: String, users: Set[BSONObjectID] = Set())(implicit ac: AccessContext, ec: ExecutionContext): Future[(UserGroup, AccessContext)] =
    createUserGroup(UserGroup(ownerId = ac.user._id, name = name, users = users)).flatMap { userGroup =>
      Future.successful(userGroup, ac.copy(user = ac.user.copy(groups = ac.user.groups + userGroup._id)))
    }

  def checkGroups(groups: Set[BSONObjectID])(implicit ec: ExecutionContext): Future[Boolean] =
    Store.db.command(Count(collection.name, query = Some(BSONDocument("_id" -> BSONDocument("$in" -> groups))))).flatMap {
      case c if c == groups.size => Future.successful(true)
      case _ => Future.successful(false)
    }

  def putUserInUserGroup(groupId: BSONObjectID, userId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[UserGroup] =
    putUsersInUserGroup(groupId, Set(userId))

  private def updateUserGroupUsers(groupId: BSONObjectID, users: Set[BSONObjectID], opcode: String)(implicit ec: ExecutionContext) =
    collection.update(BSONDocument("_id" -> groupId), BSONDocument(opcode -> BSONDocument("users" -> BSONDocument("$each" -> users))))

  def putUsersInUserGroup(groupId: BSONObjectID, users: Set[BSONObjectID])(implicit ac: AccessContext, ec: ExecutionContext): Future[UserGroup] = {
    val f1 = Users.checkUsers(users)
    val f2 = getUserGroup(groupId)
    f1.flatMap {
      case true => f2.flatMap {
        case group if group.ownerId == ac.user._id =>
          for {
            _ <- usersTools.addUserGroupToUsers(users, groupId)
            _ <- updateUserGroupUsers(groupId, users, "$addToSet")
          } yield {
            group.copy(users = group.users ++ users)
          }
        case _ => Future.failed(NoWriteAccessOnUserGroupException(groupId.stringify, ac.user._id.stringify))
      }
      case _ => Future.failed(NoMatchingUserException())
    }
  }

  def deleteUserFromUserGroup(groupId: BSONObjectID, userId: BSONObjectID)(implicit ec: ExecutionContext): Future[UserGroup] =
    deleteUsersFromUserGroup(groupId, Set(userId))

  def deleteUsersFromUserGroup(groupId: BSONObjectID, users: Set[BSONObjectID])(implicit ec: ExecutionContext): Future[UserGroup] =
    Users.checkUsers(users).flatMap {
      case true =>
        getUserGroup(groupId).flatMap {
          case group if !users(group.ownerId) =>
            for {
              _ <- usersTools.removeUserGroupFromUsers(users, groupId)
              _ <- updateUserGroupUsers(groupId, users, "$pull")
            } yield {
              group.copy(users = group.users -- users)
            }
          case group => Future.failed(OwnerCannotBeDeletedFromItsUserGroupException(groupId.stringify, group.ownerId.stringify))
        }
      case _ => Future.failed(NoMatchingUserException())
    }

  def deleteUserGroup(groupId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    getUserGroup(groupId).flatMap {
      case group if group.ownerId == ac.user._id => {
        for {
          _ <- usersTools.removeUserGroupFromUsers(group.members, groupId)
          _ <- collection.remove(BSONDocument("_id" -> groupId))
        } yield {}
      }
      case _ => Future.failed(NoWriteAccessOnUserGroupException(groupId.stringify, ac.user._id.stringify))
    }

  def putUserGroupOwner(groupId: BSONObjectID, userId: BSONObjectID)(implicit ac: AccessContext, ec: ExecutionContext): Future[UserGroup] = {
    val f1 = Users.getUser(userId)
    val f2 = getUserGroup(groupId)
    f1.flatMap { user =>
      f2.flatMap {
        case group if group.ownerId == ac.user._id && group.ownerId != userId =>
          collection.update(BSONDocument("_id" -> groupId), BSONDocument("$set" -> BSONDocument("ownerId" -> userId))).map { _ =>
            usersTools.addUserGroupToUser(groupId)(ac.copy(user = user), ec)
            group.copy(ownerId = userId)
          }
        case group if group.ownerId == userId => Future.successful(group)
        case _ => Future.failed(NoWriteAccessOnUserGroupException(groupId.stringify, ac.user._id.stringify))
      }
    }
  }
}