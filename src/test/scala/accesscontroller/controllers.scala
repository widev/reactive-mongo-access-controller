package accesscontroller

import akka.testkit.TestKit
import akka.actor.ActorSystem
import org.scalatest.{MustMatchers, WordSpecLike}
import scala.concurrent.Await
import scala.concurrent.duration._
import reactivemongo.bson.BSONObjectID
import org.joda.time.DateTime
import play.api.libs.iteratee.Iteratee

class SessionsTest extends TestKit(ActorSystem("Sessions")) with WordSpecLike with MustMatchers with TestUtils {
  implicit val ec = system.dispatcher

  cleanCollections

  val user = Await.result(access.users.createUser(User(credentials = Credentials(Session.token(6), Session.token(6)))), 1 second)
  val session = Session(userId = user._id)

  "createSession method" must {
    "return the created session" in {
      val result = Await.result(access.sessions.createSession(session), 1 second)
      result.userId must be(user._id)
    }

    "fail with NoMatchingUserException" in {
      intercept[NoMatchingUserException] {
        Await.result(access.sessions.createSession(session.copy(userId = BSONObjectID.generate)), 1 second)
      }
    }
  }

  "getSession method" must {
    "return the session if exists" in {
      val result = Await.result(access.sessions.getSession(session.token), 1 second)
      result must be(session)
    }

    "fail with NoMatchingSessionException" in {
      intercept[NoMatchingSessionException] {
        Await.result(access.sessions.getSession(Session.token(6)), 1 second)
      }
    }
  }

  implicit val ac = AccessContext(user, Some(session))

  "deleteSession (from access context) method" must {
    "return the new access context with no session" in {
      val result = Await.result(access.sessions.deleteSession, 1 second)
      result.session must be(None)
    }

    "fail with NoMatchingSessionException" in {
      intercept[NoMatchingSessionException] {
        Await.result(access.sessions.deleteSession, 1 second)
      }
    }
  }

  val session1 = Session(userId = user._id)
  Await.result(access.sessions.createSession(session1), 1 second)

  "deleteSession (from token) method" must {
    "return true to says the session is deleted" in {
      val result = Await.result(access.sessions.deleteSession(session1.token), 1 second)
      result must be(right = true)
    }

    "fail with NoMatchingSessionException" in {
      intercept[NoMatchingSessionException] {
        Await.result(access.sessions.deleteSession(session.token), 1 second)
      }
    }
  }

  val expiredSession = Session(userId = user._id, expirationDate = DateTime.now.minusHours(1))
  Await.result(access.sessions.createSession(expiredSession), 1 second)

  "clearSession method" must {
    "return it cleared one session" in {
      val result = Await.result(access.sessions.clearSessions, 1 second)
      result must be(1)
    }

    "return it cleared zero session" in {
      val result = Await.result(access.sessions.clearSessions, 1 second)
      result must be(0)
    }
  }

}

class UsersTest extends TestKit(ActorSystem("Users")) with WordSpecLike with MustMatchers with TestUtils {
  implicit val ec = system.dispatcher

  cleanCollections

  val credentials1 = Credentials(Session.token(6), Session.token(6))
  val user1 = User(credentials = credentials1)

  val credentials2 = Credentials(Session.token(), Session.token())
  val user2 = User(credentials = credentials2)

  val credentials3 = Credentials(Session.token(), Session.token())
  val user3 = User(credentials = credentials3)
  val session3 = Session(userId = user3._id)

  val credentials4 = Credentials(Session.token(), Session.token())
  val user4 = User(credentials = credentials4)

  val credentials5 = Credentials(Session.token(), Session.token())
  val user5 = User(credentials = credentials5)

  "Users" must {
    "create a user and then get it and check it exits" in {
      Await.result(access.users.createUser(user1), 1 second)._id must be(user1._id)
      Await.result(access.users.getUser(user1._id), 1 second)._id must be(user1._id)
      Await.result(access.users.checkUsers(Set(user1._id)), 1 second) must be(right = true)
      Await.result(access.users.checkUsers(Set(user1._id, BSONObjectID.generate)), 1 second) must be(right = false)
      Await.result(access.users.checkUsers(Set(BSONObjectID.generate)), 1 second) must be (right = false)
    }

    "fail with NoMatchingUserException when getting an unknown user" in {
      intercept[NoMatchingUserException] {
        Await.result(access.users.getUser(BSONObjectID.generate), 1 second)
      }
    }

    "create a user and connect it then disconnect it" in {
      Await.result(access.users.createUser(user2), 1 second)
      val ac2 = Await.result(access.users.connectUser(credentials2), 1 second)
      ac2.user._id must be(user2._id)
      ac2.session mustNot be (None)
      Await.result(access.users.disconnectUser(ac2, ec), 1 second).session must be(None)
    }

    "create a user and a session and connect from session token then disconnect it" in {
      Await.result(access.users.createUser(user3), 1 second)
      Await.result(access.sessions.createSession(session3), 1 second)
      val ac3 = Await.result(access.users.connectUser(session3.token), 1 second)
      ac3.user._id must be(user3._id)
      ac3.session mustNot be (None)
      ac3.session.get.token must be(session3.token)
      Await.result(access.users.disconnectUser(ac3, ec), 1 second).session must be(None)
    }

    "fail with NoMatchingSessionException when connecting from unknown session token then disconnect it" in {
      intercept[NoMatchingSessionException] {
        Await.result(access.users.connectUser(Session.token()), 1 second)
      }
    }

    "fail with NoMatchingUserException when connecting from unknown user" in {
      intercept[NoMatchingUserException] {
        Await.result(access.users.connectUser(Credentials(Session.token(), Session.token())), 1 second)
      }
    }

    "fail with NoValidAccessContextException when disconnection user with wrong access context" in {
      implicit val ac = AccessContext(user4, None)
      intercept[NotValidAccessContextException] {
        Await.result(access.users.disconnectUser, 1 second)
      }
    }

    "create a user and connect it then create its own user group and delete it" in {
      Await.result(access.users.createUser(user5), 1 second)
      implicit val ac5 = Await.result(access.users.connectUser(credentials5), 1 second)

      val usersToPutInGroup5 = createRandomUsers(10)
      val userGroup5 = Await.result(access.userGroups.createUserGroup(Session.token(), Set(usersToPutInGroup5.map(_._id):_*)), 1 second)

      Await.result(access.users.deleteUser, 1 second)

      // This sleep permits to let the db have time to delete the user group :/ strange ... figure it out !
      Thread.sleep(200)

      intercept[NoMatchingUserGroupException] {
        Await.result(access.userGroups.getUserGroup(userGroup5._1._id), 1 second)
      }

      intercept[NoMatchingUserException] {
        Await.result(access.users.getUser(user5._id), 1 second)
      }
    }

  }

}

class UserGroupsTest extends TestKit(ActorSystem("UserGroups")) with WordSpecLike with MustMatchers with TestUtils {
  implicit val ec = system.dispatcher

  cleanCollections

  val users = createRandomUsers(10)
  val owner = users(0)
  val acs = connectUsers(users)

  val userGroup = UserGroup(name = Session.token(), ownerId = owner._id, users = Set(users.map(_._id):_*))
  val badUserGroup = UserGroup(name = Session.token(), ownerId = owner._id, users = Set(users.map(_._id):_*) + BSONObjectID.generate)

  "createGroup method" must {
    "return the created user group" in {
      val result = Await.result(access.userGroups.createUserGroup(userGroup), 1 second)
      result must be(userGroup)
      Await.result(access.users.getUsers(userGroup.users).enumerate().apply(Iteratee.foreach { user =>
        user.groups must contain(userGroup._id)
      }), 1 second)
    }

    "fail with NoMatchingUserException" in {
      intercept[NoMatchingUserException] {
        Await.result(access.userGroups.createUserGroup(badUserGroup), 1 second)
      }
    }

    "fail with AlreadyExistException" in {
      intercept[AlreadyExistsException] {
        Await.result(access.userGroups.createUserGroup(userGroup), 1 second)
      }
    }
  }

  "createGroup (from access context) method" must {
    implicit val ac = AccessContext(owner, None)

    "return the created user group and the updated access context" in {
      val result = Await.result(access.userGroups.createUserGroup(Session.token(), userGroup.users), 1 second)
      result._1.ownerId must be(owner._id)
      result._1.users must be(userGroup.users)
      result._2.user.groups must contain(result._1._id)
      Await.result(access.users.getUsers(result._1.users).enumerate().apply(Iteratee.foreach { user =>
        user.groups must contain(result._1._id)
      }), 1 second)
    }

    "fail with NoMatchingUserException" in {
      intercept[NoMatchingUserException] {
        Await.result(access.userGroups.createUserGroup(Session.token(), badUserGroup.users), 1 second)
      }
    }
  }

  val userToPutInGroup = createRandomUsers(1)(ec)(0)
  val notOwnerUser = createRandomUsers(1)(ec)(0)

  "putUserInUserGroup method" must {
    implicit val ac = AccessContext(owner, None)

    "return the updated user group" in {
      val result = Await.result(access.userGroups.putUserInUserGroup(userGroup._id, userToPutInGroup._id), 1 second)
      result.users must contain(userToPutInGroup._id)
      Await.result(access.users.getUser(userToPutInGroup._id), 1 second).groups must contain(result._id)
    }

    "fail with NoWriteAccessOnUserGroupException" in {
      implicit val ac = AccessContext(notOwnerUser, None)
      intercept[NoWriteAccessOnUserGroupException] {
        Await.result(access.userGroups.putUserInUserGroup(userGroup._id, userToPutInGroup._id), 1 second)
      }
    }

    "fail with NoMatchingUserException" in {
      val fakeId = BSONObjectID.generate
      intercept[NoMatchingUserException] {
        Await.result(access.userGroups.putUserInUserGroup(userGroup._id, fakeId), 1 second)
      }
      Await.result(access.userGroups.getUserGroup(userGroup._id), 1 second).users mustNot contain(fakeId)
    }

    "fail with NoMatchingUserGroupException" in {
      val fakeId = BSONObjectID.generate
      intercept[NoMatchingUserGroupException] {
        Await.result(access.userGroups.putUserInUserGroup(fakeId, userToPutInGroup._id), 1 second)
      }
      Await.result(access.users.getUser(userToPutInGroup._id), 1 second).groups mustNot contain(fakeId)
    }

  }

  val usersToPutInGroup = createRandomUsers(10)

  "putUsersInUserGroup method" must {
    implicit val ac = AccessContext(owner, None)

    "return the updated user group" in {
      val result = Await.result(access.userGroups.putUsersInUserGroup(userGroup._id, Set(usersToPutInGroup.map(_._id):_*)), 1 second)
      (result.users ++ usersToPutInGroup.map(_._id)).size must be(result.users.size)
    }
  }

  "deleteUserFromUserGroup method" must {
    implicit val ac = AccessContext(owner, None)

    "return the updated user group" in {
      val result = Await.result(access.userGroups.deleteUserFromUserGroup(userGroup._id, userToPutInGroup._id), 1 second)
      result.users mustNot contain(userToPutInGroup._id)
      Await.result(access.users.getUser(userToPutInGroup._id), 1 second).groups mustNot contain(userGroup._id)
    }

    "fail with NoMatchingUserException" in {
      val fakeId = BSONObjectID.generate
      intercept[NoMatchingUserException] {
        Await.result(access.userGroups.deleteUserFromUserGroup(userGroup._id, fakeId), 1 second)
      }
    }

    "fail with NoMatchingUserGroupException" in {
      val fakeId = BSONObjectID.generate
      intercept[NoMatchingUserGroupException] {
        Await.result(access.userGroups.deleteUserFromUserGroup(fakeId, userToPutInGroup._id), 1 second)
      }
    }

    "fail with OwnerCannotBeDeletedFromItsUserGroupException" in {
      intercept[OwnerCannotBeDeletedFromItsUserGroupException] {
        Await.result(access.userGroups.deleteUserFromUserGroup(userGroup._id, owner._id), 1 second)
      }
    }
  }

  "deleteUsersFromUserGroup method" must {
    implicit val ac = AccessContext(owner, None)

    "return the updated user group" in {
      val result = Await.result(access.userGroups.deleteUsersFromUserGroup(userGroup._id, Set(usersToPutInGroup.map(_._id):_*)), 1 second)
      (result.users -- usersToPutInGroup.map(_._id)).size must be(result.users.size)
    }
  }

  "putUserGroupOwner method" must {
    implicit val ac = AccessContext(owner, None)

    "return the new user group" in {
      val result = Await.result(access.userGroups.putUserGroupOwner(userGroup._id, notOwnerUser._id), 1 second)
      result.ownerId must be(notOwnerUser._id)
    }

    "switch the owners and return the new user group" in {
      implicit val ac = AccessContext(notOwnerUser, None)
      val result = Await.result(access.userGroups.putUserGroupOwner(userGroup._id, owner._id), 1 second)
      result.ownerId must be(owner._id)
    }
  }

  "deleteUserGroup method" must {
    implicit val ac = AccessContext(owner, None)

    "fail with NoWriteAccessOnUserGroupException" in {
      implicit val ac = AccessContext(notOwnerUser, None)
      intercept[NoWriteAccessOnUserGroupException] {
        Await.result(access.userGroups.deleteUserGroup(userGroup._id), 1 second)
      }
    }

    "have deleted the user group" in {
      Await.result(access.userGroups.deleteUserGroup(userGroup._id), 1 second)
    }

    "fail with NoMatchingUserGroupException" in {
      implicit val ac = AccessContext(notOwnerUser, None)
      intercept[NoMatchingUserGroupException] {
        Await.result(access.userGroups.deleteUserGroup(userGroup._id), 1 second)
      }
    }
  }

}