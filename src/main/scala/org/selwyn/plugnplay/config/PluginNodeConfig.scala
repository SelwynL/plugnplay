package org.selwyn.plugnplay.config

import collection.JavaConverters._
import com.typesafe.config.{Config, ConfigFactory}

final case class PluginNodeConfig(name: String, pluginType: PluginType, classpath: String, conf: Map[String, String]) {
  val config: Config = ConfigFactory.parseMap(conf.asJava)
}
