package org.selwyn.plugnplay

import java.io.File
import java.lang.reflect.Method

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.clapper.classutil.{ClassFinder, ClassInfo}

import scala.util.{Failure, Success, Try}

final case class ClassAndInfo(clazz: Class[_], info: ClassInfo, input: Array[Class[_]], output: Class[_])
final case class PluginLoadingException(s: String) extends RuntimeException(s)

abstract class Plugin[I, O] {
  def name: String
  def version: String
  def author: String
  def process(in: I): O
}

abstract class SourcePlugin[O]  extends Plugin[Long, O]    {}
abstract class SinkPlugin[I]    extends Plugin[I, Boolean] {}
abstract class FlowPlugin[I, O] extends Plugin[I, O]       {}
abstract class FilterPlugin[F]  extends FlowPlugin[F, F]   {}

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

class StringToIntFlowPlugin(config: Config) extends FlowPlugin[String, Int] {
  override def name: String    = "string-to-int"
  override def version: String = "v0.1"
  override def author: String  = "selwyn lehmann"

  override def process(in: String): Int = {
    println("flowing s->i ...")
    0
  }
}

class StringToGenericRecordFlowPlugin(config: Config) extends FlowPlugin[String, GenericRecord] {
  override def name: String    = "string-to-generic-record"
  override def version: String = "v0.1"
  override def author: String  = "selwyn lehmann"

  override def process(in: String): GenericRecord = {
    println("flowing s->gr ...")

    val schema: Schema = SchemaBuilder
      .record(in)
      .fields()
      .requiredString("myString")
      .requiredInt("myInt")
      .endRecord()

    val record = new GenericData.Record(schema)
    record.put("myString", "myValue")
    record.put("myInt", 0)

    record
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
  val manager: PluginManager                     = new PluginManager(ConfigUtil.getConfig("manager", conf))
  val loadablePlugins: Map[String, ClassAndInfo] = manager.plugins
  println(s"AVAILABLE STANDARD PLUGINS: \n${loadablePlugins.keys}")

  // Validate input and output are as expected
  val pluginName = "org.selwyn.plugnplay.StringToGenericRecordFlowPlugin"

  val pluginClassInfo: Unit = loadablePlugins
    .get(pluginName)
    .foreach(i => {
      println(s"PLUGIN IN: ${i.input.foldLeft(List[String]()) { (l, i) =>
        { l :+ i.getName }
      }}")
      println(s"PLUGIN OUT: ${i.output}")

      assert(i.input.sameElements(Array(classOf[String])))
      assert(i.output.equals(classOf[GenericRecord]))
    })

  // Validate plugins message passing
  val output: Either[Throwable, Boolean] = for {
    s <- manager.makeSourcePlugin[Seq[GenericRecord]]("org.selwyn.plugnplay.KafkaSourcePlugin", ConfigFactory.empty)
    f <- manager.makeFlowPlugin[Seq[GenericRecord], Seq[GenericRecord]](
      "org.selwyn.plugnplay.GenericRecordFilterPlugin",
      ConfigFactory.empty)
    o <- manager.makeSinkPlugin[Seq[GenericRecord]]("org.selwyn.plugnplay.DynamoDBSinkPlugin", ConfigFactory.empty)
  } yield {
    o.process(f.process(s.process(100l)))
  }
  assert(output.isRight)

  println(s"COMPLETED: $output")
}

object PluginManager {

  def getMethod(c: Class[_], methodName: String): Either[Throwable, Method] =
    Try {
      c.getMethods.filter(m =>
        m.getName.contains(methodName) && !m.getReturnType.getCanonicalName.equalsIgnoreCase("java.lang.Object"))
    } match {
      case Success(methods) =>
        methods.headOption.toRight(
          PluginLoadingException(s"No appropriate method for 'process' found in '${c.getCanonicalName}'"))
      case Failure(err) => Left(err)
    }

  def foldClassAndInfo(m: Map[String, ClassAndInfo], i: ClassInfo): Map[String, ClassAndInfo] =
    Try(Class.forName(i.name)) match {
      case Success(c) =>
        // Grab the input and output class of the plugin's "process" method
        getMethod(c, "process") match {
          case Right(method) => m + (i.name -> ClassAndInfo(c, i, method.getParameterTypes, method.getReturnType))
          case Left(err) =>
            println(s"Unable to load plugin '${i.name}': ${err.getMessage}")
            m
        }
      case Failure(err) =>
        println(s"Unable to load plugin '${i.name}': ${err.getMessage}")
        m
    }
}

class PluginManager(managerConfig: Config) {
  import PluginManager._

  private val defaultPluginPathname = "plugin"
  private val pluginPath            = ConfigUtil.getStringOption("custom.pathname", managerConfig).getOrElse(defaultPluginPathname)

  // ClassFinder avoids both the class loader (for simplicity) and reflection (for speed)
  private val classpath: Seq[File]             = Seq(".", pluginPath).map(new File(_))
  private val finder: ClassFinder              = ClassFinder(classpath)
  private val classMap: Map[String, ClassInfo] = ClassFinder.classInfoMap(finder.getClasses)

  // All loadable plugins available for use
  val plugins: Map[String, ClassAndInfo] =
    ClassFinder
      .concreteSubclasses(classOf[Plugin[_, _]], classMap)
      .foldLeft(Map[String, ClassAndInfo]()) { (m, i) =>
        {
          // Filter out classes that are not in the current classloader
          foldClassAndInfo(m, i)
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
