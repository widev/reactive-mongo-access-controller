package accesscontroller

import reactivemongo.bson.BSONDocument

trait AccessControllerException extends Exception {
  val message: String

  override def getMessage: String = "AccessControllerError['" + message + "']"
}

object AccessControllerException {
  def apply[M <: ModelIdentity](model: M): AccessControllerException = WriteAccessException[M](model)
  def apply(query: BSONDocument): AccessControllerException = ReadAccessException(query)
}

case class WriteAccessException[M <: ModelIdentity](model: M) extends AccessControllerException {
  val message: String = "couldn't write the model with this id:'" + model.id.stringify + "'"
}

case class ReadAccessException(query: BSONDocument) extends AccessControllerException {
  val message: String = "couldn't read the result of this query:'" + query.toString() + "'"
}

case class InternalAccessException(message: String = "Internal access error") extends AccessControllerException

case class NoMatchingUserException(user: String = "") extends AccessControllerException {
  val message = s"No matching user" + (if (user.length > 0) s" [$user]" else "") + "."
}

case class NotValidAccessContextException(message: String = "The access context is not valid.") extends AccessControllerException

case class NoMatchingSessionException(session: String = "") extends AccessControllerException {
  val message = s"No matching session" + (if (session.length > 0) s" [$session]" else "") + "."
}

case class NotValidSessionException(session: String = "") extends AccessControllerException {
  val message = s"The stored session " + (if (session.length > 0) s" [$session]" else "") + " was not valid and has been deleted."
}

case class ExpiredSessionException(token: String) extends AccessControllerException {
  val message = s"The session with token [$token] is expired."
}

case class NoMatchingUserGroupException(group: String = "") extends AccessControllerException {
  val message = s"No matching session" + (if (group.length > 0) s" [$group]" else "") + "."
}

case class AlreadyExistsException(id: String) extends AccessControllerException {
  val message = s"The model with id [$id] already exists."
}

case class NoWriteAccessOnUserGroupException(group: String, user: String) extends AccessControllerException {
  val message = s"The user group with id [$group] is not owned by [$user]."
}

case class OwnerCannotBeDeletedFromItsUserGroupException(group: String, user: String) extends AccessControllerException {
  val message = s"The user with id [$user] of the user group with id [$group] cannot be deleted from its own group."
}