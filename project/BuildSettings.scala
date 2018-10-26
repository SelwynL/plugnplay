// SBT
import sbt._
import Keys._

object BuildSettings {

  lazy val compilerOptions = Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused-import",
    "-Xfuture",
    "-Xlint"
  )

  lazy val javaCompilerOptions = Seq(
    "-source", "1.8",
    "-target", "1.8"
  )

  // Makes our SBT app settings available from within the app
  lazy val scalifySettings = Seq(
    sourceGenerators in Compile += Def.task {
      val file = (sourceManaged in Compile).value / "settings.scala"
      IO.write(file, """package org.selwyn.generated
        |object Settings {
        |  val organization = "%s"
        |  val version = "%s"
        |  val name = "%s"
        |}
        |""".stripMargin.format(organization.value, version.value, name.value))
      Seq(file)
    }.taskValue
  )

  // scalafmt settings
  import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
  lazy val scalaFmtSettings = Seq(
    scalafmtOnCompile := true
  )

  // sbt-assembly settings for building an executable
  import sbtassembly.AssemblyPlugin.autoImport._
  lazy val sbtAssemblySettings = Seq(
    assemblyJarName in assembly       := { s"${name.value}-${version.value}.jar" },
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )

  import wartremover.Wart
  import wartremover.Wart._
  import wartremover.WartRemover.autoImport._
  lazy val wartremoverSettings = Seq(
    // only apply wartremover during compile, not test
    wartremoverErrors in(Compile, compile) := Warts.allBut(Wart.Any, Wart.Nothing)
  )

}
