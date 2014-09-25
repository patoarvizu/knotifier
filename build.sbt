name := """knotifier"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  cache,
  "com.amazonaws" % "aws-java-sdk" % "1.8.8" withSources() withJavadoc(),
  "org.json" % "json" % "20140107" withSources() withJavadoc()
)

scalacOptions ++= Seq("-feature")
