// build.sbt
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

ThisBuild / crossScalaVersions := Seq("2.13.12", "3.3.0") // Add your desired Scala versions

lazy val root = (project in file("."))
  .settings(
    name := "cats-actor-sample",
    idePackagePrefix := Some("com.suprnation"),
    libraryDependencies ++= Seq(
      "com.github.suprnation.cats-actors" %% "cats-actors" % "2.0.0-RC2"
    ),
    resolvers += "jitpack" at "https://jitpack.io"
  )
