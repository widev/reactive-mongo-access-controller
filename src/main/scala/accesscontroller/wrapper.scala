package accesscontroller

import scala.concurrent.{Future, ExecutionContext}
import reactivemongo.core.commands._
import reactivemongo.bson._
import reactivemongo.api.collections.GenericQueryBuilder
import play.api.libs.iteratee._
import reactivemongo.api._
import scala.collection.generic.CanBuildFrom
import reactivemongo.api.indexes.IndexType
import reactivemongo.api.indexes.Index
import scala.Some
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.GetLastError
import reactivemongo.api.FailoverStrategy
import reactivemongo.api.QueryOpts

case class DB(uri: String, name: String)(users: Users, userGroups: UserGroups, sessions: Sessions)
{
  import scala.concurrent.ExecutionContext.Implicits.global

  private val driver = new MongoDriver
  private val connection = driver.connection(Seq(uri))
  private val db = connection(name)

  def apply(name: String, failoverStrategy: FailoverStrategy = db.failoverStrategy): AccessControlCollection = collection(name, failoverStrategy)

  def collection(name: String, failoverStrategy: FailoverStrategy = db.failoverStrategy): AccessControlCollection =
    AccessControlCollection(db[BSONCollection](name, failoverStrategy))(users, userGroups, sessions)

}

object Rights extends Enumeration {
  type Rights = Value
  val read, write, none = Value
}

object Wrapper {
  private[accesscontroller] def wrapExternalQuery[S](query: S)(implicit writer: BSONDocumentWriter[S]): BSONDocument = {
    def wrap(document: BSONDocument): BSONDocument =
      BSONDocument(document.elements.toTraversable.map {
        case (key, value: BSONDocument) if key(0) == '$' => (key, wrap(value))
        case (key, value) => ("model." + key, value)
      })
    wrap(writer.write(query))
  }

  private[accesscontroller] def wrapSelectorForReading[S](selector: S)(implicit writer: BSONDocumentWriter[S], ac: AccessContext): BSONDocument = {
    val ids = BSONArray(ac.user.groups + ac.user._id)
    wrapExternalQuery(selector) ++ BSONDocument(
      "$or" -> BSONArray(
        BSONDocument("accessLists.writers" -> BSONDocument("$all" -> ids)),
        BSONDocument("accessLists.readers" -> BSONDocument("$all" -> ids))
      )
    )
  }
  
  private[accesscontroller] def wrapSelectorForWriting[S](selector: S)(implicit writer: BSONDocumentWriter[S], ac: AccessContext): BSONDocument = {
    val ids = BSONArray(ac.user.groups + ac.user._id)
    wrapExternalQuery(selector) ++ BSONDocument("accessLists.writers" -> BSONDocument("$all" -> ids))
  }

  private[accesscontroller] def wrapUpdate[U](update: U)(implicit writer: BSONDocumentWriter[U]): BSONDocument = {
    val document = writer.write(update)
    def isOperands(doc: BSONDocument): Boolean = doc.elements.toTraversable.exists {
      case (key, _) if key(0) == '$' => true
      case (_, value: BSONDocument) => isOperands(value)
      case _ => false
    }
    if (!isOperands(document)) BSONDocument("$set" -> BSONDocument("model" -> document)) else wrapExternalQuery(update)
  }

  private[accesscontroller] def modelToAccessControl[T](document: T)(implicit ec: ExecutionContext, writer: BSONDocumentWriter[T], ac: AccessContext): AccessControl = {
    val doc = writer.write(document)
    val tryId = doc.getAsTry[BSONObjectID]("_id")
    AccessControl(
      model = if (tryId.isFailure) doc ++ BSONDocument("_id" -> BSONObjectID.generate) else doc,
      accessLists = AccessControlLists(writers = Set(ac.user._id))
    )
  }

  private[accesscontroller] def secureDocumentToAccessControl[T](document: T)(users: Users)(implicit ec: ExecutionContext, writer: BSONDocumentWriter[T], ac: AccessContext): Future[AccessControl] =
    users.checkUsers(Set(ac.user._id)).flatMap {
      case true => Future.successful(modelToAccessControl(document))
      case _ => Future.failed(NoMatchingUserException(ac.user._id.stringify))
    }

}

sealed case class AccessControlCursor[T](private val cursor: Cursor[AccessControl])(implicit reader: BSONDocumentReader[T]) {
  def enumerate(maxDocs: Int = Int.MaxValue, stopOnError: Boolean = false)(implicit ec: ExecutionContext): Enumerator[T] =
    cursor.enumerate(maxDocs, stopOnError = stopOnError).through[T](Enumeratee.map[AccessControl] {
      accessControl => reader.read(accessControl.model)
    })

  def enumerateBulks(maxDocs: Int = Int.MaxValue, stopOnError: Boolean = false)(implicit ctx: ExecutionContext): Enumerator[Iterator[T]] =
    cursor.enumerateBulks(maxDocs, stopOnError = stopOnError).through[Iterator[T]](Enumeratee.map[Iterator[AccessControl]] { it =>
      it.map { accessControl =>
        reader.read(accessControl.model)
      }
    })

  def collect[M[_]](upTo: Int = Int.MaxValue, stopOnError: Boolean = true)(implicit cbf: CanBuildFrom[M[_], T, M[T]], ctx: ExecutionContext): Future[M[T]] =
    cursor.collect[Iterable](upTo, stopOnError).flatMap { collected =>
      val builder = cbf.apply()
      collected.foreach { accessControl =>
        builder += reader.read(accessControl.model)
      }
      Future.successful(builder.result())
    }

  def headOption(implicit ec: ExecutionContext): Future[Option[T]] = cursor.headOption.flatMap {
    case Some(accessControl) =>
      Future { Some[T](reader.read(accessControl.model)) }
    case _ => Future[Option[T]](None)
  }

}

sealed case class AccessControlQueryBuilder(private val queryBuilder: GenericQueryBuilder[BSONDocument, BSONDocumentReader, BSONDocumentWriter]) {
  def cursor[T](implicit reader: BSONDocumentReader[T] = queryBuilder.structureReader, ec: ExecutionContext): AccessControlCursor[T] = AccessControlCursor[T](queryBuilder.cursor[AccessControl])

  def cursor[T](readPreference: ReadPreference)(implicit reader: BSONDocumentReader[T] = queryBuilder.structureReader, ec: ExecutionContext): AccessControlCursor[T] =
    AccessControlCursor[T](queryBuilder.cursor[AccessControl](readPreference))

  def one[T](implicit reader: BSONDocumentReader[T], ec: ExecutionContext): Future[Option[T]] = cursor(reader, ec).headOption

  def one[T](readPreference: ReadPreference)(implicit reader: BSONDocumentReader[T], ec: ExecutionContext): Future[Option[T]] = cursor(readPreference)(reader, ec).headOption

  def query[Qry](selector: Qry)(implicit writer: BSONDocumentWriter[Qry], ac: AccessContext): AccessControlQueryBuilder = copy(queryBuilder.query(Wrapper.wrapSelectorForReading(selector)))

  def sort(document: BSONDocument): AccessControlQueryBuilder = copy(queryBuilder.sort(Wrapper.wrapExternalQuery(document)))

  def options(options: QueryOpts): AccessControlQueryBuilder = copy(queryBuilder.options(options))

  def projection[Pjn](p: Pjn)(implicit writer: BSONDocumentWriter[Pjn], ac: AccessContext): AccessControlQueryBuilder = copy(queryBuilder.projection(Wrapper.wrapExternalQuery(p)))

  def projection(p: BSONDocument, ac: AccessContext): AccessControlQueryBuilder = copy(queryBuilder.projection(Wrapper.wrapExternalQuery(p)))

  def hint(document: BSONDocument): AccessControlQueryBuilder = copy(queryBuilder.hint(Wrapper.wrapExternalQuery(document)))

  def snapshot(flag: Boolean = true): AccessControlQueryBuilder = copy(queryBuilder.snapshot(flag))

  def comment(message: String): AccessControlQueryBuilder = copy(queryBuilder.comment(message))
}

case class AccessControlCollection(private val collection: BSONCollection)(users: Users, userGroups: UserGroups, sessions: Sessions) {

  {
    import scala.concurrent.ExecutionContext.Implicits.global

    collection.indexesManager.ensure(
      Index(List("model._id" -> IndexType.Ascending), unique = true)
    )
  }

  private def setRights[S, U](selector: S, id: BSONObjectID, rights: Rights.Value)(implicit swriter: BSONDocumentWriter[S], ec: ExecutionContext, ac: AccessContext) =
    collection.update(Wrapper.wrapSelectorForWriting(selector),
      rights match {
        case Rights.read =>
          BSONDocument("$addToSet" -> BSONDocument("accessLists.readers" -> id), "$pull" -> BSONDocument("accessLists.writers" -> id))
        case Rights.write =>
          BSONDocument("$addToSet" -> BSONDocument("accessLists.writers" -> id), "$pull" -> BSONDocument("accessLists.readers" -> id))
        case _ =>
          BSONDocument("$pull" -> BSONDocument("accessLists.readers" -> id), "$pull" -> BSONDocument("accessLists.writers" -> id))
      }).flatMap {
      case result if result.updated > 0 => Future.successful()
      case _ => Future.failed(NoWriteAccessOnSelectedDataException(selector))
    }

  def setUserRights[S, U](selector: S, userId: BSONObjectID, rights: Rights.Value)(implicit swriter: BSONDocumentWriter[S], ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    users.checkUsers(Set(userId)).flatMap {
      case true => setRights(selector, userId, rights)
      case _ => Future.failed(NoMatchingUserException(userId.stringify))
    }

  def setUserGroupRights[S](selector: S, userGroupId: BSONObjectID, rights: Rights.Value)(implicit swriter: BSONDocumentWriter[S], ac: AccessContext, ec: ExecutionContext): Future[Unit] =
    userGroups.checkGroups(Set(userGroupId)).flatMap {
      case true => setRights(selector, userGroupId, rights)
      case _ => Future.failed(NoMatchingUserGroupException(userGroupId.stringify))
    }

  def find[S, P](selector: S, projection: P)(implicit swriter: BSONDocumentWriter[S], pwriter: BSONDocumentWriter[P], ac: AccessContext): GenericQueryBuilder[BSONDocument, BSONDocumentReader, BSONDocumentWriter] =
    collection.find(Wrapper.wrapSelectorForReading(selector), Wrapper.wrapExternalQuery(projection))

  def find[S](selector: S)(implicit swriter: BSONDocumentWriter[S], ac: AccessContext, ec: ExecutionContext): AccessControlQueryBuilder =
    AccessControlQueryBuilder(collection.find(Wrapper.wrapSelectorForReading(selector)))

  def stats(scale: Int)(implicit ec: ExecutionContext): Future[CollStatsResult] = collection.stats(scale)

  def stats()(implicit ec: ExecutionContext): Future[CollStatsResult] = collection.stats()

  def bulkInsert[T](enumerator: Enumerator[T], bulkSize: Int, bulkByteSize: Int)(implicit writer: BSONDocumentWriter[T], ec: ExecutionContext, ac: AccessContext): Future[Int] =
    users.checkUsers(Set(ac.user._id)).flatMap {
      case true => collection.bulkInsert(enumerator.through[AccessControl](Enumeratee.map[T] { el => Wrapper.modelToAccessControl(el) }), bulkSize, bulkByteSize)
      case _ => Future.failed(NoMatchingUserException(ac.user._id.stringify))
    }

  def bulkInsertIteratee[T](bulkSize: Int, bulkByteSize: Int)(implicit writer: BSONDocumentWriter[T], ec: ExecutionContext, ac: AccessContext): Iteratee[T, Int] =
    Enumeratee.map { model: T => Wrapper.modelToAccessControl(model) } &>> collection.bulkInsertIteratee(bulkSize, bulkByteSize)

  def insert[T](document: T, writeConcern: GetLastError = GetLastError())(implicit writer: BSONDocumentWriter[T], ec: ExecutionContext, ac: AccessContext): Future[LastError] =
    Wrapper.secureDocumentToAccessControl(document)(users).flatMap { accessControl =>
      collection.insert(accessControl, writeConcern)
    }

  def insert(document: BSONDocument, writeConcern: GetLastError)(implicit ec: ExecutionContext, ac: AccessContext): Future[LastError] =
    Wrapper.secureDocumentToAccessControl(document)(users).flatMap { accessControl =>
      collection.insert(accessControl, writeConcern)
    }

  def insert(document: BSONDocument)(implicit ec: ExecutionContext, ac: AccessContext): Future[LastError] = insert(document, GetLastError())

  def remove[T](query: T, writeConcern: GetLastError = GetLastError(), firstMatchOnly: Boolean = false)(implicit writer: BSONDocumentWriter[T], ec: ExecutionContext, ac: AccessContext): Future[LastError] =
    collection.remove(Wrapper.wrapSelectorForWriting(query), writeConcern, firstMatchOnly).flatMap {
      case result if result.updated > 0 => Future.successful(result)
      case _ => Future.failed(NoWriteAccessOnSelectedDataException(query))
    }

  def save[T](doc: T, writeConcern: GetLastError)(implicit ec: ExecutionContext, writer: BSONDocumentWriter[T], ac: AccessContext): Future[LastError] =
    Wrapper.secureDocumentToAccessControl(doc)(users).flatMap { accessControl =>
      collection.save(accessControl, writeConcern)
    }

  def save(doc: BSONDocument, writeConcern: GetLastError)(implicit ec: ExecutionContext, ac: AccessContext): Future[LastError] =
    Wrapper.secureDocumentToAccessControl(doc)(users).flatMap { accessControl =>
      collection.save(accessControl, writeConcern)
    }

  def save(doc: BSONDocument)(implicit ec: ExecutionContext, ac: AccessContext): Future[LastError] =
    Wrapper.secureDocumentToAccessControl(doc)(users).flatMap { accessControl =>
      collection.save(accessControl)
    }

  def uncheckedInsert[T](document: T)(implicit writer: BSONDocumentWriter[T], ec: ExecutionContext, ac: AccessContext): Unit =
    Wrapper.secureDocumentToAccessControl(document)(users).foreach { accessControl =>
      collection.uncheckedInsert(accessControl)
    }

  def uncheckedRemove[T](query: T, firstMatchOnly: Boolean)(implicit writer: BSONDocumentWriter[T], ec: ExecutionContext, ac: AccessContext): Unit =
    collection.uncheckedRemove(Wrapper.wrapSelectorForWriting(query), firstMatchOnly)

  def uncheckedUpdate[S, U](selector: S, update: U, upsert: Boolean, multi: Boolean)(implicit selectorWriter: BSONDocumentWriter[S], updateWriter: BSONDocumentWriter[U], ac: AccessContext): Unit =
    collection.uncheckedUpdate(Wrapper.wrapSelectorForWriting(selector), Wrapper.wrapUpdate(update), upsert, multi)

  def update[S, U](selector: S, update: U, writeConcern: GetLastError = GetLastError(), upsert: Boolean = false, multi: Boolean = false)(implicit selectorWriter: BSONDocumentWriter[S], updateWriter: BSONDocumentWriter[U], ec: ExecutionContext, ac: AccessContext): Future[LastError] =
    collection.update(Wrapper.wrapSelectorForWriting(selector), Wrapper.wrapUpdate(update), writeConcern, upsert, multi).flatMap {
      case result if result.updated > 0 => Future.successful(result)
      case _ => Future.failed(NoWriteAccessOnSelectedDataException(selector))
    }
}