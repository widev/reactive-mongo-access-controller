import sbt._
import sbt.Keys._

object build extends Build {

  lazy val defaultSettings = Seq(
    name := "ReactiveMongoAccessController",
    organization := "com.github.trupin",
    version := "0.1-SNAPSHOT",
    scalaVersion := "2.10.2",
    crossScalaVersions := Seq("2.10.2"),
    crossVersion := CrossVersion.binary,
    javaOptions in test ++= Seq("-Xmx512m", "-XX:MaxPermSize=1024m"),
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
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

  lazy val publishSettings = Seq(
    publishMavenStyle := true,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    publishTo := Some("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"),
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    licenses := Seq("MIT-style" -> url("http://opensource.org/licenses/mit-license.php")),
    homepage := Some(url("https://github.com/trupin/ReactiveMongoAccessController")),
    pomExtra :=
      <scm>
        <url>git@github.com:trupin/ReactiveMongoAccessController.git</url>
        <connection>scm:git:git@github.com:trupin/ReactiveMongoAccessController.git</connection>
      </scm>
        <developers>
          <developer>
          <id>trupin</id>
            <name>Theophane Rupin</name>
          </developer>
        </developers>
  )

  lazy val root = Project(
    id = "ReactiveMongoAccessController",
    base = file("."),
    settings = Project.defaultSettings ++ defaultSettings ++ publishSettings
  )
}