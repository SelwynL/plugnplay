package org.selwyn.plugnplay.config

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray

import scala.util.Try

final case class ApplicationConfig(manager: PluginManagerConfig, workflow: PluginDagConfig)

object ApplicationConfig {
  import org.selwyn.plugnplay.codec.CodecImplicits._

  def from(json: String): Either[Throwable, ApplicationConfig] = {
    val jsonBytes: Array[Byte] = json.getBytes("UTF-8")
    Try(readFromArray[ApplicationConfig](jsonBytes)).toEither
  }
}
