package org.selwyn.plugnplay

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.avro.generic.GenericRecord
import org.clapper.classutil.{ClassFinder, ClassInfo}

import scala.util.{Success, Try}

case class PluginLoadingException(s: String) extends RuntimeException(s)

trait Plugin {
  def name: String
  def version: String
  def author: String
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

object Entry extends App {

  // All "reference.conf" have substitutions resolved first, without "application.conf" in the stack, so the reference stack has to be self-contained
  val conf = ConfigUtil.getConfig("plugnplay", ConfigFactory.load)

  // Instantiate PluginManager. Available plugins are loaded once upon initialization.
  val manager: PluginManager                                  = new PluginManager(ConfigUtil.getConfig("manager", conf))
  val loadablePlugins: Map[String, ClassAndInfo[_ >: Plugin]] = manager.plugins

  println(s"AVAILABLE STANDARD PLUGINS: \n$loadablePlugins")

  val output = for {
    source <- manager.makeSourcePlugin("org.selwyn.plugnplay.KafkaSourcePlugin", ConfigFactory.empty)
    filter <- manager.makeProcessPlugin("org.selwyn.plugnplay.FilterPlugin", ConfigFactory.empty)
    output <- manager.makeSinkPlugin("org.selwyn.plugnplay.DynamoDBSinkPlugin", ConfigFactory.empty)
  } yield output.sink(filter.process(source.poll(Some(100l))))

  println(s"COMPLETED: $output")
}

final case class ClassAndInfo[P <: Plugin](clazz: Class[P], info: ClassInfo)

class PluginManager(managerConfig: Config) {

  private val defaultPluginPathname = "plugin"
  private val pluginPath            = ConfigUtil.getStringOption("custom.pathname", managerConfig).getOrElse(defaultPluginPathname)

  // ClassFinder avoids both the class loader (for simplicity) and reflection (for speed)
  private val classpath: Seq[File]             = Seq(".", pluginPath).map(new File(_))
  private val finder: ClassFinder              = ClassFinder(classpath)
  private val classMap: Map[String, ClassInfo] = ClassFinder.classInfoMap(finder.getClasses)

  // All loadable plugins available for use
  val plugins: Map[String, ClassAndInfo[Plugin]] =
    ClassFinder.concreteSubclasses(classOf[Plugin], classMap).foldLeft(Map[String, ClassAndInfo[Plugin]]()) { (m, i) =>
      {
        // Filter out classes that are not in the current classloader
        Try(Class.forName(i.name)) match {
          case Success(p: Class[Plugin]) => m + (i.name -> ClassAndInfo(p, i))
          case _                         => m
        }
      }
    }

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

  // TODO: Remove usage of 'asInstanceOf[P]'
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def makePlugin[P <: Plugin](name: String, config: Config): Either[Throwable, P] =
    for {
      infoEntry   <- plugins.get(name).toRight(PluginLoadingException(s"No plugin by name '$name' found."))
      plugin      <- Try(infoEntry.clazz.getConstructor(classOf[Config]).newInstance(config)).toEither
      typedPlugin <- Try(plugin.asInstanceOf[P]).toEither
    } yield typedPlugin
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
