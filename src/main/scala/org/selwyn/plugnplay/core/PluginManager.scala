package org.selwyn.plugnplay.core

import java.io.File
import java.lang.reflect.Method

import com.typesafe.config.Config
import org.clapper.classutil.{ClassFinder, ClassInfo}
import org.selwyn.plugnplay.config.SafeConfig
import org.selwyn.plugnplay.model.ClassAndInfo

import scala.util.{Failure, Success, Try}

final case class PluginLoadingException(s: String) extends RuntimeException(s)

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
  private val pluginPath            = SafeConfig.getStringOption("custom.pathname", managerConfig).getOrElse(defaultPluginPathname)

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
