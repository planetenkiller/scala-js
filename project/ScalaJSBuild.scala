import sbt._
import Keys._
import Process.cat

import ch.epfl.lamp.sbtscalajs._
import ScalaJSPlugin._
import ScalaJSKeys._
import SourceMapCat.catJSFilesAndTheirSourceMaps

object ScalaJSBuild extends Build {

  val scalajsScalaVersion = "2.10.1"

  val commonSettings = Defaults.defaultSettings ++ Seq(
      organization := "ch.epfl.lamp",
      version := "0.1-SNAPSHOT",

      normalizedName ~= { _.replace("scala.js", "scalajs") }
  )

  val defaultSettings = commonSettings ++ Seq(
      scalaVersion := scalajsScalaVersion,
      scalacOptions ++= Seq(
          "-deprecation",
          "-unchecked",
          "-feature",
          "-encoding", "utf8"
      )
  )

  lazy val root = Project(
      id = "scalajs",
      base = file("."),
      settings = defaultSettings ++ Seq(
          name := "Scala.js",
          publishArtifact in Compile := false,
          packageJS in Compile <<= (
              target,
              packageJS in (corejslib, Compile),
              packageJS in (javalib, Compile),
              packageJS in (scalalib, Compile),
              packageJS in (libraryAux, Compile),
              packageJS in (library, Compile)
          ) map { (target, corejslib, javalib, scalalib, libraryAux, library) =>
            val minimalRuntimeJSFiles = corejslib ++ javalib ++ scalalib.filter(_.getName.contains("-minimal")) ++ libraryAux ++ library
            val fullRuntimeJSFiles = corejslib ++ javalib ++ scalalib.filterNot(_.getName.contains("-minimal")) ++ libraryAux ++ library
            val outputFull = target / ("scalajs-runtime.js")
            val outputMinimal = target / ("scalajs-runtime-minimal.js")
            target.mkdir()
            catJSFilesAndTheirSourceMaps(fullRuntimeJSFiles, outputFull)
            catJSFilesAndTheirSourceMaps(minimalRuntimeJSFiles, outputMinimal)
            outputFull :: outputMinimal  :: Nil
          },

          clean <<= clean.dependsOn(
              // compiler, library and sbt-plugin are aggregated
              clean in corejslib, clean in javalib, clean in scalalib,
              clean in libraryAux, clean in examples,
              clean in exampleHelloWorld, clean in exampleReversi)
      )
  ).aggregate(
      compiler, plugin, library
  )

  lazy val compiler = Project(
      id = "scalajs-compiler",
      base = file("compiler"),
      settings = defaultSettings ++ Seq(
          name := "Scala.js compiler",
          libraryDependencies ++= Seq(
              "org.scala-lang" % "scala-compiler" % scalajsScalaVersion,
              "org.scala-lang" % "scala-reflect" % scalajsScalaVersion
          ),
          mainClass := Some("scala.tools.nsc.scalajs.Main"),
          exportJars := true
      )
  )

  lazy val plugin = Project(
      id = "scalajs-sbt-plugin",
      base = file("sbt-plugin"),
      settings = commonSettings ++ Seq(
          name := "Scala.js sbt plugin",
          sbtPlugin := true,
          scalaVersion := "2.9.2",
          scalaBinaryVersion <<= scalaVersion,
          libraryDependencies ++= Seq(
              "com.google.javascript" % "closure-compiler" % "v20130603"
          )
      )
  )

  lazy val corejslib = Project(
      id = "scalajs-corejslib",
      base = file("corejslib"),
      settings = defaultSettings ++ Seq(
          name := "Scala.js core JS runtime",
          publishArtifact in Compile := false,

          packageJS in Compile <<= (
              baseDirectory, target in Compile
          ) map { (baseDirectory, target) =>
            // hard-coded because order matters!
            val fileNames =
              Seq("scalajsenv.js", "javalangObject.js", "RefTypes.js")

            val allJSFiles = fileNames map (baseDirectory / _)
            val output = target / ("scalajs-corejslib.js")
            target.mkdir()
            catJSFilesAndTheirSourceMaps(allJSFiles, output)
            output :: Nil
          }
      )
  )

  lazy val javalib = Project(
      id = "scalajs-javalib",
      base = file("javalib"),
      settings = defaultSettings ++ baseScalaJSSettings ++ Seq(
          name := "Java library for Scala.js",
          publishArtifact in Compile := false
      )
  ).dependsOn(compiler, library)

  lazy val scalalib = Project(
      id = "scalajs-scalalib",
      base = file("scalalib"),
      settings = defaultSettings ++ baseScalaJSSettings ++ Seq(
          name := "Scala library for Scala.js",
          publishArtifact in Compile := false,

          // The Scala lib is full of warnings we don't want to see
          scalacOptions ~= (_.filterNot(
              Set("-deprecation", "-unchecked", "-feature") contains _)),

          // add minimal runtime package
          packageJsFiles <<= (packageJsFiles, classDirectory in Compile, moduleName) { (fullRuntime, classDir, modName) =>
            val packages = List("collection", "concurrent", "io", "parallel", "ref", "sys", "testing", "text", "xml")
            val excludesPart1 = packages.map(scalaPackage => classDir / "scala" / scalaPackage ** "*.js").reduce(_ +++ _)
            val exclucesPart2 = List("parsing", "regexp", "matching", "logging", "automata", "grammer", "hashing").map(scalaPackage => classDir / "scala" / "util" / scalaPackage ** "*.js").reduce(_ +++ _)
            val files = (classDir ** "*.js") --- excludesPart1 --- exclucesPart2
            fullRuntime :+ (files, modName + "-minimal.js")
          }

      )
  ).dependsOn(compiler)

  lazy val libraryAux = Project(
      id = "scalajs-library-aux",
      base = file("library-aux"),
      settings = defaultSettings ++ baseScalaJSSettings ++ Seq(
          name := "Scala.js aux library",
          publishArtifact in Compile := false
      )
  ).dependsOn(compiler)

  lazy val library = Project(
      id = "scalajs-library",
      base = file("library"),
      settings = defaultSettings ++ baseScalaJSSettings ++ Seq(
          name := "Scala.js library"
      )
  ).dependsOn(compiler)

  // Examples

  lazy val examples = Project(
      id = "examples",
      base = file("examples"),
      settings = defaultSettings ++ Seq(
          name := "Scala.js examples"
      )
  ).aggregate(exampleHelloWorld, exampleReversi)

  lazy val exampleSettings = defaultSettings ++ baseScalaJSSettings ++ Seq(
      /* Add the library classpath this way to escape the dependency between
       * tasks. This avoids to recompile the library every time we compile an
       * example. This is all about working around the lack of dependency
       * analysis.
       */
      unmanagedClasspath in Compile <+= (
          classDirectory in (library, Compile)
      ) map { classDir =>
        Attributed.blank(classDir)
      }
  )

  lazy val exampleHelloWorld = Project(
      id = "helloworld",
      base = file("examples") / "helloworld",
      settings = exampleSettings ++ Seq(
          name := "Hello World - Scala.js example",
          moduleName := "helloworld"
      )
  ).dependsOn(compiler)

  lazy val exampleReversi = Project(
      id = "reversi",
      base = file("examples") / "reversi",
      settings = exampleSettings ++ Seq(
          name := "Reversi - Scala.js example",
          moduleName := "reversi"
      )
  ).dependsOn(compiler)
}
