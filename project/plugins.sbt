logLevel := Level.Warn

resolvers ++= Seq(
  "SBT plugin releases" at "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"
)

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.4.0")
