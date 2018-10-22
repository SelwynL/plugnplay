package org.selwyn.plugnplay

import java.io.{File, FileFilter}
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile

import com.sun.net.httpserver.Authenticator.Failure
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.avro.generic.GenericRecord
import org.selwyn.plugnplay.Plugin.PluginLoadingException

import scala.collection.JavaConverters._
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Success, Try}

object Entry extends App {
  val pluginPathname = "plugins"

  // All "reference.conf" have substitutions resolved first, without "application.conf" in the stack, so the reference stack has to be self-contained
  val conf = ConfigUtil.getConfig("plugnplay", ConfigFactory.load)

  val pluginClassNameInput      = "org.selwyn.plugnplay.GenericRecordPollPlugin"
  val pluginClassNameThroughput = "org.selwyn.plugnplay.FilterPlugin"
  val pluginClassNameSink       = "org.selwyn.plugnplay.DynamoSinkPlugin"

  val manager: PluginManager       = new PluginManager(ConfigUtil.getConfig("manager", conf))
  val standardPlugins: Seq[String] = manager.standardPlugins
  val customPlugins: Seq[String]   = manager.customPlugins

  println(s"AVAILABLE STANDARD PLUGINS: $standardPlugins")
  println(s"AVAILABLE CUSTOM PLUGINS: $customPlugins")

  val plugins: Either[Throwable, (SourcePlugin, ProcessPlugin, SinkPlugin)] = for {
    so <- manager.makeSourcePlugin("org.selwyn.plugnplay.KafkaSourcePlugin", ConfigFactory.empty)
    pr <- manager.makeProcessPlugin("org.selwyn.plugnplay.FilterPlugin", ConfigFactory.empty)
    si <- manager.makeSinkPlugin("org.selwyn.plugnplay.DynamoDBSinkPlugin", ConfigFactory.empty)
  } yield (so, pr, si)

  val success: Boolean = plugins match {
    case Right((sourcePlugin, processPlugin, sinkPlugin)) =>
      println(" >> PLUGINS INITIALIZED...")
      val input     = sourcePlugin.poll(Some(1000l))
      val processed = processPlugin.process(input)
      sinkPlugin.sink(processed)
    case Left(err: Throwable) =>
      println(s"FAILED TO LOAD: ${err.getMessage}")
      err.printStackTrace()
      false
  }

  println(" >> DONE!")
}

class PluginManager(managerConfig: Config) {

  private val defaultExtension = ".jar"
  private val defaultPathname  = "plugin"

  private val extension = ConfigUtil.getStringOption("custom.extension", managerConfig).getOrElse(defaultExtension)
  private val pathname  = ConfigUtil.getStringOption("custom.pathname", managerConfig).getOrElse(defaultPathname)

  private val pluginFilesEither = Plugin.loadFiles(pathname, extension)

  private val classLoaderEither = for {
    files  <- pluginFilesEither
    loader <- Plugin.loadClassLoader(files)
  } yield loader

  /**
    * Lists the loaded plugins available to be made with the methods provided
    */
  val customPlugins: Seq[String] = (for {
    files      <- pluginFilesEither
    jarFiles   <- Try(files.map(f => new JarFile(f))).toEither
    jarEntries <- Try(jarFiles.flatMap(_.entries().asScala)).toEither
  } yield jarEntries.map(_.getName)) match {
    case Right(cn: Seq[String]) => Plugin.filterClasses(cn, classTag[Plugin].runtimeClass)
    case Left(err: Throwable) =>
      println(s"Unable to load any custom plugins: ${err.getMessage}")
      Seq.empty
  }

  /**
    * Lists the standard plugins available to be made with the methods provided
    */
  val standardPlugins: Seq[String] = ServiceLoader
    .load(classTag[Plugin].runtimeClass, this.getClass.getClassLoader)
    .asScala
    .map({ case p: Plugin => p.getClass.getCanonicalName })
    .toSeq

  /**
    * Creates a new "process" plugin instance
    * @param name   Classname of the plugin to load
    * @param config Config to pass into the constructor of the plugin
    * @return
    */
  def makeProcessPlugin(name: String, config: Config): Either[Throwable, ProcessPlugin] =
    makePlugin[ProcessPlugin](name, config)

  /**
    * Creates a new "source" plugin instance
    * @param name   Classname of the plugin to load
    * @param config Config to pass into the constructor of the plugin
    * @return
    */
  def makeSourcePlugin(name: String, config: Config): Either[Throwable, SourcePlugin] =
    makePlugin[SourcePlugin](name, config)

  /**
    * Creates a new "sink" plugin instance
    * @param name   Classname of the plugin to load
    * @param config Config to pass into the constructor of the plugin
    * @return
    */
  def makeSinkPlugin(name: String, config: Config): Either[Throwable, SinkPlugin] =
    makePlugin[SinkPlugin](name, config)

  private def makePlugin[P: ClassTag](name: String, config: Config): Either[Throwable, P] =
    for {
      loader   <- classLoaderEither
      instance <- Try(loader.loadClass(name).getConstructor(classOf[Config]).newInstance(config)).toEither
      plugin   <- GenericUtil.getTypedArg[P](instance)
    } yield plugin
}

object ConfigUtil {

  private def tryGet[T](key: String, get: String => T): Option[T] =
    Try(get(key)).toOption

  def getStringOption(key: String, config: Config): Option[String] =
    tryGet(key, k => config.getString(k))

  def getConfigOption(key: String, config: Config): Option[Config] =
    tryGet(key, k => config.getConfig(k))

  /**
    * Get configuration object. Defaults to empty return value
    */
  def getConfig(key: String, config: Config): Config =
    getConfigOption(key, config).getOrElse(ConfigFactory.empty())
}

object GenericUtil {

  /**
    * Utility function to replace "asInstanceOf[T]
    * @param any  The value to be cast
    * @tparam T   The generic type to cast to
    * @return
    */
  def getTypedArg[T: ClassTag](any: Any): Either[Throwable, T] = {
    any match {
      case Success(t: T) => Right(t)
      case _             => Left(PluginLoadingException(s"Type does not match class '${classTag[T].runtimeClass}'"))
    }
  }
}

object Plugin {
  case class PluginLoadingException(s: String) extends RuntimeException(s)
  type PluginOutcome = Either[Throwable, Seq[GenericRecord]]

  def loadFiles(pathname: String, fileExtension: String): Either[Throwable, Seq[File]] =
    Try {
      val file = new File(pathname)
      if (file.exists() && file.isDirectory) {
        file
          .listFiles(new FileFilter {
            override def accept(pathname: File): Boolean = pathname.getPath.toLowerCase.endsWith(fileExtension)
          })
          .toList
      } else {
        Seq.empty
      }
    }.toEither

  def loadClassLoader(files: Seq[File]): Either[Throwable, URLClassLoader] =
    Try(new URLClassLoader(files.map(f => f.toURI.toURL).toArray)).toEither

  def fromFiles[T](files: Seq[File], clazz: Class[T]): Either[Throwable, Seq[T]] =
    loadClassLoader(files).map(l => ServiceLoader.load(clazz, l).asScala.toList)

  def filterClasses[T](classNames: Seq[String], target: Class[T]): Seq[String] =
    classNames.filter(n =>
      Try(Class.forName(n)) match {
        case Success(c: Class[_]) => c.isInstance(target)
        case _                    => false
    })
}

trait Plugin {
  val name: String
  val version: String
  val author: String
}

trait SourcePlugin extends Plugin {
  def poll(timeout: Option[Long]): Seq[GenericRecord]
}

trait SinkPlugin extends Plugin {
  def sink(records: Seq[GenericRecord]): Boolean
}

trait ProcessPlugin extends Plugin {
  def process(records: Seq[GenericRecord]): Seq[GenericRecord]
}

class DynamoDBSinkPlugin(config: Config) extends SinkPlugin {
  override val name: String    = "dynamo-sink"
  override val version: String = "v0.1"
  override val author: String  = "selwyn lehmann"

  override def sink(records: Seq[GenericRecord]): Boolean = {
    println("sinking...")
    true
  }
}

class KafkaSourcePlugin(config: Config) extends SourcePlugin {
  override val name: String    = "kafka-source"
  override val version: String = "v0.1"
  override val author: String  = "selwyn lehmann"

  override def poll(timeout: Option[Long]): Seq[GenericRecord] = {
    println("polling...")
    Seq.empty
  }
}

class FilterPlugin(config: Config) extends ProcessPlugin {
  override val name: String    = "dynamo-sink"
  override val version: String = "v0.1"
  override val author: String  = "selwyn lehmann"

  override def process(records: Seq[GenericRecord]): Seq[GenericRecord] = {
    println("filtering...")
    records
  }

}

case class PluginData(dataType: PluginDataType, data: Array[Byte])

sealed abstract class PluginDataType extends EnumEntry
object PluginDataType extends Enum[PluginDataType] {
 val values: IndexedSeq[PluginDataType] = findValues
 case object Integer extends PluginDataType
 case object String  extends PluginDataType
 case object Avro    extends PluginDataType
}
