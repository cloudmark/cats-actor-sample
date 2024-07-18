// build.sbt
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

ThisBuild / crossScalaVersions := Seq("2.13.14", "3.3.0") // Add your desired Scala versions

lazy val root = (project in file("."))
  .settings(
    name := "cats-actor-sample",
    idePackagePrefix := Some("com.suprnation"),
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "3.10.2",
      "org.typelevel" %% "cats-effect" % "3.1.1",
      "org.typelevel" %% "cats-core" % "2.12.0",
      "com.github.suprnation.cats-actors" %% "cats-actors" % "2.0.0-RC2"
    ),
    resolvers += "jitpack" at "https://jitpack.io"
  )
