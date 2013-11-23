import sbt._
import sbt.Keys._

object build extends Build {
  lazy val root = Project(
    id = "main",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "ReactiveMongoAccessController",
      organization := "org.widev",
      version := "0.1",
      scalaVersion := "2.10.+",
      resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      resolvers += "Sonatype staging" at "http://oss.sonatype.org/content/repositories/snapshots",
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.2.+",
        "com.typesafe.akka" %% "akka-testkit" % "2.2.+" % "test",
        "org.reactivemongo" %% "reactivemongo" % "0.10.0-SNAPSHOT",
        "joda-time" %  "joda-time" % "2.1",
        "org.joda" % "joda-convert" % "1.2",
        "ch.qos.logback" % "logback-classic" % "1.0.10",
        "org.scalatest" %% "scalatest" % "2.+" % "test"
      )
  )
  )
}