
name := "play-utils"

version := "0.1.0"

scalaVersion := "2.12.6"

playBuildRepoName in ThisBuild := "play-utils"


val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.5.4"
val playGuice = "com.typesafe.play" %% "play-guice" % "2.6.5"
val playScalaTest = "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % "test"

lazy val root = Project(
  id = "play-utils",
  base = file(".")
)
.enablePlugins(PlayLibrary)
.settings(libraryDependencies ++= Seq(playGuice, akkaActor, playScalaTest))