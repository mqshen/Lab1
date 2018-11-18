import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt.Keys._
import sbt._
import scoverage.ScoverageSbtPlugin
import xerial.sbt.Pack._

object Build extends Build {
  def mappingContainsAnyPath(mapping: (File, String), paths: Seq[String]): Boolean = {
    paths.foldLeft(false)(_ || mapping._1.getPath.contains(_))
  }

  lazy val root = Project("Lab1", file("."))
    .aggregate(core, mapReduce, test, glutinous)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)

  lazy val core = Project("core", file("core"))
    .settings(basicSettings: _*)
    .settings(packAutoSettings)
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)

  lazy val mapReduce = Project("mapreduce", file("mapreduce"))
    .settings(basicSettings: _*)
    .settings(packAutoSettings)
    .settings(packMain := Map("data-server" -> "com.ynet.dataservice.service.DataSourceServiceMain"))
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)
    .dependsOn(core)

  lazy val glutinous = Project("glutinous", file("glutinous"))
    .settings(basicSettings: _*)
    .settings(packAutoSettings)
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)

  lazy val test = Project("test", file("test"))
    .settings(basicSettings: _*)
    .settings(packAutoSettings)
    .settings(formatSettings: _*)
    .settings(releaseSettings: _*)
    .settings(libraryDependencies ++= Dependencies.all)

  lazy val noPublishing = Seq(
    publish := (),
    publishLocal := (),
    // required until these tickets are closed https://github.com/sbt/sbt-pgp/issues/42,
    // https://github.com/sbt/sbt-pgp/issues/36
    publishTo := None
  )

  lazy val basicSettings = Seq(
    organization := "com.ynet",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.11.2",
    crossScalaVersions := Seq("2.10.4", "2.11.8"),
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-encoding", "UTF-8"),
    javacOptions ++= Seq("-encoding", "UTF-8"),
    resolvers ++= Seq(
      "Local repository" at "http://192.168.65.235:8081/nexus/content/repositories/releases/",
      "Local repository snapshots" at "http://192.168.65.235:8081/nexus/content/repositories/snapshots/",
      "Spray repository" at "http://repo.spray.io/"),
    ScoverageSbtPlugin.ScoverageKeys.coverageHighlighting := (
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 10)) => false
        case _ => true
      })
  )

  lazy val releaseSettings = Seq(
    publishTo := {
      val nexus = "http://192.168.65.235:8081/nexus/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { (repo: MavenRepository) => false },
    pomExtra := (
      <url>https://github.com/mqshen/spray-socketio</url>
        <licenses>
          <license>
            <name>The Apache Software License, Version 2.0</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:wandoulabs/spray-socketio.git</url>
          <connection>scm:git:git@github.com:wandoulabs/spray-socketio.git</connection>
        </scm>
        <developers>
          <developer>
            <id>mqshen</id>
            <name>miaoqi shen</name>
            <email>goldratio87@gmail.com</email>
          </developer>
          <developer>
            <id>gaolin</id>
            <name>lin gao</name>
            <email>gaolin@belink.com</email>
          </developer>
        </developers>
      )
  )

  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test := formattingPreferences)

  import scalariform.formatter.preferences._
  def formattingPreferences =
    FormattingPreferences()
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(IndentSpaces, 2)

}
object Dependencies {
  val branch = "master"//Process("git" :: "rev-parse" :: "--abbrev-ref" :: "HEAD" :: Nil).!!.trim
  val suffix = if (branch == "master") "" else "-SNAPSHOT"

  val libVersion = "6.34.0" + suffix
  val utilVersion = "6.33.0" + suffix
  val ostrichVersion = "9.17.0" + suffix
  val scroogeVersion = "4.6.0" + suffix

  val jettyVersion = "9.3.24.v20180605"

  val slf4jVersion = "1.7.7"
  val logbackVersion = "1.1.7"

  val testLibs = Seq(
    "org.mockito" % "mockito-core" % "1.9.5" % "test",
    "org.scalatest" %% "scalatest" % "2.2.3" % "test",
    "org.specs2" %% "specs2" % "2.3.12" % "test"
  )

  val all = Seq(
    "commons-io" % "commons-io" % "2.4",
    "org.apache.commons" % "commons-lang3" % "3.8.1",
    "org.apache.commons" % "commons-crypto" % "1.0.0",
    "commons-dbcp" % "commons-dbcp" % "1.4",
    "org.apache.commons" % "commons-compress" % "1.5",
    "org.apache.commons" % "commons-email" % "1.3.1",
    "org.apache.httpcomponents" % "httpclient" % "4.3",
    "mysql" % "mysql-connector-java" % "5.1.29",
    "junit" % "junit" % "4.11" % "test",
    "org.slf4j" % "slf4j-api" % slf4jVersion,
    "ch.qos.logback" % "logback-core" % logbackVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "org.scala-lang.modules" %% "scala-async" % "0.9.5",
    "org.javassist" % "javassist" % "3.22.0-GA",
    "log4j" % "log4j" % "1.2.17",
    "com.google.code.findbugs" % "jsr305" % "3.0.2",
    "com.google.guava" % "guava" % "14.0.1",
    "io.netty" % "netty-all" % "4.1.31.Final",
    "io.dropwizard.metrics" % "metrics-core" % "4.0.3",
    "javax.servlet" % "javax.servlet-api" % "3.1.0" % "provided",
    "org.scala-lang.modules" %% "scala-xml" % "1.1.1",
    "org.eclipse.jetty" % "jetty-util" % jettyVersion,
    "org.eclipse.jetty" % "jetty-servlet" % jettyVersion,
    "org.eclipse.jetty" % "jetty-client" % jettyVersion,
    "org.eclipse.jetty" % "jetty-proxy" % jettyVersion,
    "javax.ws.rs" % "javax.ws.rs-api" % "2.0.1",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
    "org.json4s" %% "json4s-jackson" % "3.6.2"
  )

}
