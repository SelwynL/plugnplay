package org.selwyn.plugnplay.codec

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import org.selwyn.plugnplay.config.{ApplicationConfig, PluginType}

@SuppressWarnings(
  Array("org.wartremover.warts.NonUnitStatements",
        "org.wartremover.warts.Null",
        "org.wartremover.warts.Var",
        "org.wartremover.warts.While"))
object CodecImplicits {
  implicit val plugintypecodec: JsonValueCodec[PluginType] = new JsonValueCodec[PluginType] {
    override def decodeValue(in: JsonReader, default: PluginType): PluginType =
      PluginType.withName(in.readString("source"))

    override def encodeValue(x: PluginType, out: JsonWriter): Unit =
      out.writeVal(x.entryName)

    override def nullValue: PluginType =
      PluginType.Source
  }
  implicit val applicationconfigcodec: JsonValueCodec[ApplicationConfig] =
    JsonCodecMaker.make[ApplicationConfig](CodecMakerConfig())
}
