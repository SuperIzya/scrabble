#!/usr/bin/env amm

import java.io.{BufferedInputStream, BufferedReader, File, FileReader, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths}

import $ivy.`dev.zio::zio:1.0.3`
import $ivy.`dev.zio::zio-streams:1.0.3`
import java.util.stream.{Stream => JStream}

import scala.collection.JavaConverters._
import zio._
import zio.stream._
import zio.console._

val getCsv: String => UIO[File] = l => ZIO.effectTotal(new File(s"./languages/$l.csv"))
val getLetterFile: (String, String) => UIO[File] = (lang, letter) => for {
  file <- ZIO.effectTotal(new File(s"./letters/$lang/$letter.slvs"))
  _ <- ZIO.ifM(ZIO.effectTotal(file.exists()))(ZIO.unit, ZIO.effectTotal(file.createNewFile()))
} yield file

val template: UIO[File] = ZIO.effectTotal(new File("./letter.tmpl"))

val lettersDir: String => UIO[File] = lang => for {
  parent <- ZIO.effectTotal(Paths.get("./letters/"))
  _ <- if(parent.toFile.exists()) ZIO.unit else ZIO.effectTotal(Files.createDirectory(parent))
  dir <- ZIO.effectTotal(parent.resolve(lang))
  _ <- if (dir.toFile.exists()) ZIO.unit else ZIO.effectTotal(Files.createDirectory(dir))
} yield dir.toFile

val getReader: File => UManaged[ZStream[Any, Throwable, String]] = file => for {
  fileReader <- ZManaged.fromAutoCloseable(ZIO.effectTotal(new FileReader(file)))
  reader <- ZManaged.fromAutoCloseable(ZIO.effectTotal(new BufferedReader(fileReader)))
  stream <- ZManaged.fromAutoCloseable(ZIO.effectTotal(reader.lines()))
} yield ZStream.fromJavaStream(stream)

val getWriter: File => UManaged[PrintWriter] = file =>
  ZManaged.fromAutoCloseable(ZIO.effectTotal(new PrintWriter(file, "UTF-8")))

def checkLocale(locale: String): URIO[Console, Boolean] = {
  if (locale.isEmpty) putStrLn("Locale should be provided. Run the script as following: ./populate.sc ru") >>> UIO(false)
  else ZIO.ifM(getCsv(locale).map(_.exists()))(
    UIO(true),
    putStrLn("Locale does not exists in folder 'languages'. Consult README.md and try again.") >>> UIO(false)
  )
}

def applyTmpl(letter: String, points: Int): (ZStream[Any, Throwable, String], PrintWriter) => Task[Unit] = (stream, writer) => {
  stream
    .map(_.replace("<letter>", letter)
          .replace("<points>", points.toString)
    ).mapM(l => ZIO.effectTotal(writer.println(l)))
    .runDrain
}

def populate(lang: String): (String, Int) => Task[Unit] = (letter, points) => {
  val resources = for {
    tmpl <- ZManaged.fromEffect(template)
    stream <- getReader(tmpl)
    file <- ZManaged.fromEffect(getLetterFile(lang, letter))
    writer <- getWriter(file)
  } yield (stream, writer)

  resources.use(applyTmpl(letter, points).tupled)
}

def populateAll(locale: String): RIO[Console, Unit] = {
  val resource = for {
    csv <- ZManaged.fromEffect(getCsv(locale))
    stream <- getReader(csv)
  } yield stream

  resource.use { stream =>
    lettersDir(locale) *>
      stream
        .map(_.split(","))
        .mapM {
          case Array(l, p) => ZIO.effectTotal(l -> p.toInt) <* putStrLn(s"Populating letter: $l")
        }
        .mapM(populate(locale).tupled)
        .runDrain
  }
}

@main
def main(language: String) = {
  val action: RIO[Console, Unit] = ZIO.ifM(checkLocale(language))(
    populateAll(language),
    ZIO.unit
  )

  Runtime.default.unsafeRun(action.provideLayer(Console.live))
}