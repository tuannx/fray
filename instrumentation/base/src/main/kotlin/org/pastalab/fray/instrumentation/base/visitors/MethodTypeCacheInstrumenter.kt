package org.pastalab.fray.instrumentation.base.visitors

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.pastalab.fray.runtime.Runtime

/** Experimental: JVM method-type interning is not an application scheduling boundary. */
class MethodTypeCacheInstrumenter(cv: ClassVisitor) :
    SkipMethodInstrumenter(
        cv,
        Runtime::onSkipPrimitive,
        Runtime::onSkipPrimitiveDone,
        "java.lang.invoke.MethodType",
        "java.lang.invoke.LambdaFormEditor",
    ) {
  override fun instrumentMethod(
      mv: MethodVisitor,
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      exceptions: Array<out String>?,
  ): MethodVisitor =
      if (
          (className == "java/lang/invoke/MethodType" && name == "makeImpl") ||
              (className == "java/lang/invoke/LambdaFormEditor" && name == "putInCache")
      )
          super.instrumentMethod(mv, access, name, descriptor, signature, exceptions)
      else mv
}
