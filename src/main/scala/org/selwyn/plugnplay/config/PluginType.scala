package org.selwyn.plugnplay.config

import enumeratum.{Enum, EnumEntry}
import scala.collection.immutable.IndexedSeq

sealed abstract class PluginType(override val entryName: String) extends EnumEntry

object PluginType extends Enum[PluginType] {
  val values: IndexedSeq[PluginType] = findValues
  case object Source extends PluginType("source")
  case object Flow   extends PluginType("flow")
  case object Sink   extends PluginType("sink")
}
