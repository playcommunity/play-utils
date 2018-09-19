
name := "play-utils"

version := "0.1.0"

scalaVersion := "2.12.6"

organization := "cn.playscala"

organizationName := "cn.playscala"

organizationHomepage := Some(url("https://github.com/playcommunity"))

homepage := Some(url("https://github.com/playcommunity/play-utils"))

playBuildRepoName in ThisBuild := "play-utils"

version in ThisBuild := "0.1.0"

val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.5.4"
val playGuice = "com.typesafe.play" %% "play-guice" % "2.6.5"
val playScalaTest = "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % "test"

val buildSettings = Seq(
  organization := "cn.playscala",
  organizationName := "cn.playscala",
  organizationHomepage := Some(url("https://github.com/playcommunity")),
  scalaVersion := "2.12.6",
  crossScalaVersions := Seq("2.11.7", "2.12.6"),
  //scalacOptions in Compile := scalacOptionsVersion(scalaVersion.value),
  //scalacOptions in Test := scalacOptionsTest,
  //scalacOptions in IntegrationTest := scalacOptionsTest,
)

lazy val root = Project(
  id = "play-utils",
  base = file(".")
)
.enablePlugins(PlayLibrary)
.settings(buildSettings)
.settings(libraryDependencies ++= Seq(playGuice, akkaActor, playScalaTest))