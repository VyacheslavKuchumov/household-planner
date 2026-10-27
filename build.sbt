ThisBuild / scalaVersion := "3.3.7"
ThisBuild / organization := "app.householdplanner"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val backend = (project in file("backend"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "household-planner-api",
    Compile / mainClass := Some("app.householdplanner.Main"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % "3.11.6",
      "dev.zio" %% "zio-json" % "0.9.2",
      "com.zaxxer" % "HikariCP" % "7.1.0",
      "org.postgresql" % "postgresql" % "42.7.14",
      "com.nimbusds" % "nimbus-jose-jwt" % "10.5",
      "com.password4j" % "password4j" % "1.8.4",
      "com.sun.mail" % "jakarta.mail" % "2.0.1",
      "dev.zio" %% "zio-test" % "2.1.26" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
      "org.flywaydb" % "flyway-core" % "11.14.1" % Test,
      "org.flywaydb" % "flyway-database-postgresql" % "11.14.1" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true
  )
