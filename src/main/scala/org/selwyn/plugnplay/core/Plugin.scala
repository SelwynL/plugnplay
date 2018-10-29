package org.selwyn.plugnplay.core

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
