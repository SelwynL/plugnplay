package org.selwyn.plugnplay

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.avro.generic.GenericRecord
import org.clapper.classutil.{ClassFinder, ClassInfo}

import scala.reflect.ClassTag
import scala.util.{Success, Try}

final case class PluginLoadingException(s: String) extends RuntimeException(s)

trait Plugin[I, O] {
  def name: String
  def version: String
  def author: String
  def process(in: I): O
}

trait SourcePlugin[O]  extends Plugin[Long, O]    {}
trait SinkPlugin[I]    extends Plugin[I, Boolean] {}
trait FlowPlugin[I, O] extends Plugin[I, O]       {}
trait FilterPlugin[F]  extends FlowPlugin[F, F]   {}

class DynamoDBSinkPlugin(config: Config) extends SinkPlugin[Seq[GenericRecord]] {
  override val name: String    = "dynamo-sink"
  override val version: String = "v0.1"
  override val author: String  = "selwyn lehmann"

  override def process(in: Seq[GenericRecord]): Boolean = {
    println("sinking...")
    true
  }
}

class KafkaSourcePlugin(config: Config) extends SourcePlugin[Seq[GenericRecord]] {
  override val name: String    = "kafka-source"
  override val version: String = "v0.1"
  override val author: String  = "selwyn lehmann"

  override def process(in: Long): Seq[GenericRecord] = {
    println("polling...")
    Seq.empty
  }
}

class GenericRecordFilterPlugin(config: Config) extends FilterPlugin[Seq[GenericRecord]] {
  override val name: String    = "dynamo-sink"
  override val version: String = "v0.1"
  override val author: String  = "selwyn lehmann"

  override def process(in: Seq[GenericRecord]): Seq[GenericRecord] = {
    println("filtering...")
    in
  }
}

object Entry extends App {

  // All "reference.conf" have substitutions resolved first, without "application.conf" in the stack, so the reference stack has to be self-contained
  val conf: Config = ConfigUtil.getConfig("plugnplay", ConfigFactory.load)

  // Instantiate PluginManager. Available plugins are loaded once upon initialization.
  val manager: PluginManager                                   = new PluginManager(ConfigUtil.getConfig("manager", conf))
  val loadablePlugins: Map[String, ClassAndInfo[Plugin[_, _]]] = manager.plugins
  println(s"AVAILABLE STANDARD PLUGINS: \n$loadablePlugins")

  def execute[T](source: (String, Config) => Either[Throwable, SourcePlugin[T]],
                 filter: (String, Config) => Either[Throwable, FlowPlugin[T, T]],
                 output: (String, Config) => Either[Throwable, SinkPlugin[T]]): Either[Throwable, Boolean] = {
    for {
      s <- source("org.selwyn.plugnplay.KafkaSourcePlugin", ConfigFactory.empty)
      f <- filter("org.selwyn.plugnplay.GenericRecordFilterPlugin", ConfigFactory.empty)
      o <- output("org.selwyn.plugnplay.DynamoDBSinkPlugin", ConfigFactory.empty)
    } yield o.process(f.process(s.process(100l)))
  }

  val output: Either[Throwable, Boolean] = execute[Seq[GenericRecord]](
    (s, c) => manager.makeSourcePlugin[Seq[GenericRecord]](s, c),
    (s, c) => manager.makeFlowPlugin[Seq[GenericRecord], Seq[GenericRecord]](s, c),
    (s, c) => manager.makeSinkPlugin[Seq[GenericRecord]](s, c),
  )
  println(s"COMPLETED: $output")
}

final case class ClassAndInfo[P <: Plugin[_, _]](clazz: ClassTag[P], info: ClassInfo)

class PluginManager(managerConfig: Config) {

  private val defaultPluginPathname = "plugin"
  private val pluginPath            = ConfigUtil.getStringOption("custom.pathname", managerConfig).getOrElse(defaultPluginPathname)

  // ClassFinder avoids both the class loader (for simplicity) and reflection (for speed)
  private val classpath: Seq[File]             = Seq(".", pluginPath).map(new File(_))
  private val finder: ClassFinder              = ClassFinder(classpath)
  private val classMap: Map[String, ClassInfo] = ClassFinder.classInfoMap(finder.getClasses)

  // All loadable plugins available for use
  val plugins: Map[String, ClassAndInfo[Plugin[_, _]]] =
    ClassFinder
      .concreteSubclasses(classOf[Plugin[_, _]], classMap)
      .foldLeft(Map[String, ClassAndInfo[Plugin[_, _]]]()) { (m, i) =>
        {
          // Filter out classes that are not in the current classloader
          Try(Class.forName(i.name)) match {
            case Success(p) => m + (i.name -> ClassAndInfo(ClassTag(p), i))
            case _          => m
          }
        }
      }

  /**
    * Creates a new "flow" plugin instance
    * @param name   Classname of the plugin to load
    * @param config Config to pass into the constructor of the plugin
    * @return
    */
  def makeFlowPlugin[I, O](name: String, config: Config): Either[Throwable, FlowPlugin[I, O]] =
    makePlugin[FlowPlugin[I, O]](name, config)

  /**
    * Creates a new "source" plugin instance
    * @param name   Classname of the plugin to load
    * @param config Config to pass into the constructor of the plugin
    * @return
    */
  def makeSourcePlugin[O](name: String, config: Config): Either[Throwable, SourcePlugin[O]] =
    makePlugin[SourcePlugin[O]](name, config)

  /**
    * Creates a new "sink" plugin instance
    * @param name   Classname of the plugin to load
    * @param config Config to pass into the constructor of the plugin
    * @return
    */
  def makeSinkPlugin[I](name: String, config: Config): Either[Throwable, SinkPlugin[I]] =
    makePlugin[SinkPlugin[I]](name, config)

  // TODO: Remove usage of 'asInstanceOf[P]'
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def makePlugin[P <: Plugin[_, _]](name: String, config: Config): Either[Throwable, P] =
    for {
      infoEntry   <- plugins.get(name).toRight(PluginLoadingException(s"No plugin by name '$name' found."))
      plugin      <- Try(infoEntry.clazz.runtimeClass.getConstructor(classOf[Config]).newInstance(config)).toEither
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
