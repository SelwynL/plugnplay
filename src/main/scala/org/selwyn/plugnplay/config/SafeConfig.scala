package org.selwyn.plugnplay.config

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}

import scala.util.Try

object SafeConfig {

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

//  import org.json4s.DefaultFormats
//  import org.json4s.native.JsonMethods._
//  implicit val format = DefaultFormats
//  def convert[T](c: Config): Either[Throwable, T] =
//    Try(parse(c.root().render(ConfigRenderOptions.concise())).extract[T]).toEither

}
