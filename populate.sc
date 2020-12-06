#!/usr/bin/env amm

import java.io.{BufferedInputStream, BufferedReader, File, FileInputStream, FileReader, FileWriter, InputStreamReader, PrintWriter}
import java.nio.file.{Files, Paths}
import $ivy.`dev.zio::zio:1.0.3`
import $ivy.`dev.zio::zio-streams:1.0.3`

import java.util.stream.{Stream => JStream}
import scala.collection.JavaConverters._
import zio._
import zio.stream._
import zio.console._

import java.nio.charset.{Charset, CharsetDecoder, CharsetEncoder}
import scala.sys.process._

val firstLine = new String("±²³SolveSpaceREVa".getBytes("UTF-8"), "iso-8859-1")
val getCsv: String => UIO[File] = l => ZIO.effectTotal(new File(s"./languages/$l.csv"))
val getLetterFile: (String, String) => UIO[Seq[File]] = (lang, letter) => for {
  files <- ZIO.effectTotal(Seq(new File(s"./letters/$lang/$letter.slvs"), new File(s"./letters/$lang/$letter-comp.slvs")))
  _ <- ZIO.collectAll {
    files.map { file =>
      ZIO.ifM(ZIO.effectTotal(file.exists()))(ZIO.unit, ZIO.effectTotal(file.createNewFile()))
    }
  }
} yield files

val template: UIO[File] = ZIO.effectTotal(new File("./template.slvs"))
val complimentar: UIO[File] = ZIO.effectTotal(new File("./template-compl.slvs"))

val lettersDir: String => UIO[File] = lang => for {
  parent <- ZIO.effectTotal(Paths.get("./letters/"))
  _ <- if (parent.toFile.exists()) ZIO.unit else ZIO.effectTotal(Files.createDirectory(parent))
  dir <- ZIO.effectTotal(parent.resolve(lang))
  _ <- if (dir.toFile.exists()) ZIO.unit else ZIO.effectTotal(Files.createDirectory(dir))
} yield dir.toFile

def getEncReader(encoding: CharsetDecoder): File => UManaged[ZStream[Any, Throwable, String]] = file => for {
  fileStream <- ZManaged.fromAutoCloseable(ZIO.effectTotal(new FileInputStream(file)))
  fileReader <- ZManaged.fromAutoCloseable(ZIO.effectTotal(new InputStreamReader(fileStream, encoding)))
  reader <- ZManaged.fromAutoCloseable(ZIO.effectTotal(new BufferedReader(fileReader)))
  stream <- ZManaged.fromAutoCloseable(ZIO.effectTotal(reader.lines()))
} yield ZStream.fromJavaStream(stream)

val getReader = getEncReader(Charset.forName("iso-8859-1").newDecoder())

val getWriter: File => UManaged[PrintWriter] = file =>
  ZManaged.fromAutoCloseable(ZIO.effectTotal(new PrintWriter(file, "iso-8859-1")))

val mesh: Seq[File] => UIO[Unit] = files => ZIO.collectAll{
  files.map { file =>
    val path = file.getPath
    val commands = Seq(
      //"regenerate",
      "export-mesh -o %.stl"
    ).map(c => s"solvespace.cli $c $path")
      .map(s => ZIO.effectTotal(s.!))
    ZIO.collectAll(commands).unit
  }
}.unit


def printFirstLine(file: File): Task[Unit] = {
  ZManaged.fromAutoCloseable(ZIO.effectTotal(new PrintWriter(file, "ASCII")))
    .use(writer => ZIO.effectTotal(writer.println(firstLine)))
}

def checkLocale(locale: String): URIO[Console, Boolean] = {
  if (locale.isEmpty) putStrLn("Locale should be provided. Run the script as following: ./populate.sc ru") >>> UIO(false)
  else ZIO.ifM(getCsv(locale).map(_.exists()))(
    UIO(true),
    putStrLn("Locale does not exists in folder 'languages'. Consult README.md and try again.") >>> UIO(false)
  )
}

def applyTmpl(stream: ZStream[Any, Throwable, String], writer: PrintWriter): (String, Int) => RIO[Console, Unit] = (letter, points) => {
  stream
    .map(_.replace("Request.str=A", s"Request.str=$letter")
      .replace("Request.str=1", s"Request.str=$points")
      .replace("Group.impFileRel=template.slvs", s"Group.impFileRel=$letter.slvs")
    ).mapM(l => ZIO.effectTotal(writer.println(l)))
    .runDrain
}

def populate(lang: String): (String, Int) => RIO[Console, Seq[File]] = (letter, points) => {
  type Writer = (String, Int) => RIO[Console, Unit]
  val resources = for {
    tmpl <- ZManaged.fromEffect(template)
    compl <- ZManaged.fromEffect(complimentar)
    streams <- ZManaged.collectAll(Seq(tmpl, compl).map(getReader))
    files <- ZManaged.fromEffect(getLetterFile(lang, letter))
    writers <- ZManaged.collectAll(files.map(getWriter))
    templateWriters <- ZManaged.collectAll(writers.zip(streams).map{
      case (writer, stream) => ZManaged.fromFunction[Any, Writer](_ => applyTmpl(stream, writer))
    })
  } yield (templateWriters, files)
  val properLetter = new String(letter.getBytes("UTF-8"), "iso-8859-1")
  resources.use {
    case (templateWriters, files) =>
      for {
        _ <- ZIO.collectAll(files.map(printFirstLine))
        _ <- ZIO.collectAll(templateWriters.map(_(properLetter, points)))
      } yield files
  }
}

def populateAll(locale: String): RIO[Console, Unit] = {
  val resource = for {
    csv <- ZManaged.fromEffect(getCsv(locale))
    stream <- getEncReader(Charset.forName("UTF-8").newDecoder())(csv)
  } yield stream

  resource.use { stream =>
    lettersDir(locale) *>
      stream
        .map(_.split(","))
        .mapM {
          case Array(l, p) => ZIO.effectTotal(l -> p.toInt) <* putStrLn(s"Populating letter: $l <-> $p")
        }
        .mapM(populate(locale).tupled)
        .mapM(mesh)
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
