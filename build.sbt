ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .settings(
    name := "cats-actor-sample",
    idePackagePrefix := Some("com.suprnation")
  )

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies += "com.github.suprnation" % "cats-actors" % "1.0.2"
