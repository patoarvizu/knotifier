name := """knotifier"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  javaJdbc,
  javaEbean,
  cache,
  javaWs,
  "com.amazonaws" % "aws-java-sdk" % "1.8.8" withSources() withJavadoc(),
  "org.json" % "json" % "20140107" withSources() withJavadoc()
)
