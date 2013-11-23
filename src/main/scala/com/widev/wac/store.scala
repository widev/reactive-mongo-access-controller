package com.widev.wac

import com.typesafe.config.ConfigFactory
import reactivemongo.api.MongoDriver

object Store {
  import scala.concurrent.ExecutionContext.Implicits.global

  val config = ConfigFactory.load.getConfig("server.store")
  val driver = new MongoDriver
  val connection = driver.connection(List(config.getString("uri")))
  val db = connection(config.getString("db"))
}

trait Store {
  val config = Store.config
  protected val driver = Store.driver
  protected val connection = Store.connection
  protected val db = Store.db
}
