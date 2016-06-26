//enablePlugins(JavaServerAppPackaging)

name := "elastic-transform-reindex"

version := "0.1"

organization := "com.brevitaz.esreindex"

scalaVersion := "2.11.5"

libraryDependencies += "com.sksamuel.elastic4s" % "elastic4s-core_2.11" % "1.5.17"
libraryDependencies += "org.json4s" % "json4s-jackson_2.11" % "3.3.0"
libraryDependencies += "com.typesafe" % "config" % "1.3.0"

resolvers ++= Seq("Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
                  "Spray Repository"    at "http://repo.spray.io")

// Assembly settings
mainClass in Global := Some("com.brevitaz.esreindex.Main")

