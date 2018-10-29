package org.selwyn.plugnplay

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.avro.{Schema, SchemaBuilder}
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.selwyn.plugnplay.config.SafeConfig
import org.selwyn.plugnplay.core._
import org.selwyn.plugnplay.model.ClassAndInfo

object Entry extends App {

  // All "reference.conf" have substitutions resolved first, without "application.conf" in the stack, so the reference stack has to be self-contained
  val conf: Config = SafeConfig.getConfig("plugnplay", ConfigFactory.load)

  // Instantiate PluginManager. Available plugins are loaded once upon initialization.
  val manager: PluginManager                     = new PluginManager(SafeConfig.getConfig("manager", conf))
  val loadablePlugins: Map[String, ClassAndInfo] = manager.plugins

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

//
// Below are test plugin implementations
//

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
