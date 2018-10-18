import sbt._

object Dependencies {
  val resolutionRepos = Seq(
    "Sonatype OSS Releases"   at "http://oss.sonatype.org/content/repositories/releases/",
    "Typesafe"                at "http://repo.typesafe.com/typesafe/releases/",
    "Artima Maven Repository" at "http://repo.artima.com/releases"
  )

  object V {
    // Test
    val scalatest            = "3.0.5"
  }

  val Libraries = Seq(
    // Test
    "org.scalatest"              %% "scalatest"              % V.scalatest   % "test"
  )
}
