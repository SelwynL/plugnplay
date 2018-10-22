import sbt._

object Dependencies {
  val resolutionRepos = Seq(
    "Sonatype OSS Releases"   at "http://oss.sonatype.org/content/repositories/releases/",
    "Typesafe"                at "http://repo.typesafe.com/typesafe/releases/",
    "Artima Maven Repository" at "http://repo.artima.com/releases"
  )

  object V {
    // Compile
    val avro       = "1.8.2"
    val enumeratum = "1.5.13"
    val typesafe   = "1.3.2"

    // Test
    val scalatest  = "3.0.5"
  }

  val Libraries = Seq(
    // Compile
    "com.beachape"    %% "enumeratum" % V.enumeratum,
    "org.apache.avro" %  "avro"       % V.avro,
    "com.typesafe"    % "config"      % V.typesafe,

    // Test
    "org.scalatest" %% "scalatest" % V.scalatest % "test"
  )
}
