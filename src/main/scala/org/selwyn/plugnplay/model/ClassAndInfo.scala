package org.selwyn.plugnplay.model

import org.clapper.classutil.ClassInfo

final case class ClassAndInfo(clazz: Class[_], info: ClassInfo, input: Array[Class[_]], output: Class[_])
