package org.selwyn.plugnplay.config

final case class PluginDagConfig(name: String, nodes: Seq[PluginNodeConfig], edges: Seq[PluginEdgeConfig])
