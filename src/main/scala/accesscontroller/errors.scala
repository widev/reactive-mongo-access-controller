package accesscontroller

import reactivemongo.bson.{BSONDocumentWriter, BSONDocument}


trait AccessControllerException extends Exception {
  val message: String

  override def getMessage: String = "AccessControllerError['" + message + "']"
}

case class InternalAccessException(message: String = "Internal access error") extends AccessControllerException

case class NoWriteAccessOnSelectedDataException[T](query: T)(implicit writer: BSONDocumentWriter[T]) extends AccessControllerException {
  val message = s"The query [${BSONDocument.pretty(writer.write(query))}] selected only documents on which you can't write."
}

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