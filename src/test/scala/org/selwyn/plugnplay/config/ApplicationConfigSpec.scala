package org.selwyn.plugnplay.config

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import org.scalatest.{Matchers, WordSpecLike}

class ApplicationConfigSpec extends WordSpecLike with Matchers {

  "ApplicationConfig" must {

    val filename = "test-app.conf"

    "generate from Config" in {
      val conf: Config = SafeConfig.getConfig("plugnplay", ConfigFactory.load(filename))
      val resultEither = ApplicationConfig.from(conf.root().render(ConfigRenderOptions.concise()))
      resultEither.isRight should be(true)
      resultEither.right.get.workflow.name should be("myTestWorkflow")
    }
  }
}
