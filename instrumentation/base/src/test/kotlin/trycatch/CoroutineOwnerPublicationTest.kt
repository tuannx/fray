package trycatch

import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.Runtime

class CoroutineOwnerPublicationTest {
  @Test
  fun ownerAndGraphPublicationAreVisibleToAnotherThreadAndClearedOnUnregister() {
    val field = Runtime::class.java.getDeclaredField("coroutineOwnerPublished")
    assertTrue(Modifier.isVolatile(field.modifiers))
    val owner = object : ClassLoader(javaClass.classLoader) {}
    val other = object : ClassLoader(javaClass.classLoader) {}
    val pool = Executors.newSingleThreadExecutor()
    var registered = false
    try {
      assertFalse(Runtime.hasCoroutineInstrumentationOwner())
      CoroutineInstrumentationOwners.register(owner)
      registered = true
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.register(other) }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.unregister(other) }
      assertTrue(Runtime.hasCoroutineInstrumentationOwner())
      assertTrue(
          pool
              .submit<Boolean> {
                Runtime.hasCoroutineInstrumentationOwner() &&
                    CoroutineInstrumentationOwners.owns(owner) &&
                    !CoroutineInstrumentationOwners.owns(other) &&
                    !CoroutineInstrumentationOwners.hasAppGraph(owner)
              }
              .get(5, TimeUnit.SECONDS)
      )
      CoroutineInstrumentationOwners.registerAppGraph(owner, mapOf("example/App" to "0".repeat(64)))
      assertTrue(
          pool
              .submit<Boolean> {
                Runtime.hasCoroutineInstrumentationOwner() &&
                    CoroutineInstrumentationOwners.hasAppGraph(owner) &&
                    !CoroutineInstrumentationOwners.hasAppGraph(other)
              }
              .get(5, TimeUnit.SECONDS)
      )
      CoroutineInstrumentationOwners.unregister(owner)
      registered = false
      assertTrue(
          pool
              .submit<Boolean> {
                !Runtime.hasCoroutineInstrumentationOwner() &&
                    !CoroutineInstrumentationOwners.owns(owner)
              }
              .get(5, TimeUnit.SECONDS)
      )
    } finally {
      if (registered) CoroutineInstrumentationOwners.unregister(owner)
      pool.shutdownNow()
    }
  }

  @Test
  fun replacementAfterWeakOwnerIsClearedResetsGraphAndKeepsHintConservative() {
    val old = object : ClassLoader(javaClass.classLoader) {}
    val replacement = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(old)
    var registered: ClassLoader? = old
    try {
      CoroutineInstrumentationOwners.registerAppGraph(old, mapOf("example/Old" to "0".repeat(64)))
      // Simulate collection of the weak reference deterministically; do not use old afterwards.
      val ownerField = CoroutineInstrumentationOwners::class.java.getDeclaredField("owner")
      ownerField.isAccessible = true
      (ownerField.get(null) as WeakReference<*>).clear()
      registered = null
      assertTrue(Runtime.hasCoroutineInstrumentationOwner()) // stale true is a safe slow path
      CoroutineInstrumentationOwners.register(replacement)
      registered = replacement
      assertTrue(Runtime.hasCoroutineInstrumentationOwner())
      assertTrue(CoroutineInstrumentationOwners.owns(replacement))
      assertFalse(CoroutineInstrumentationOwners.hasAppGraph(replacement))
    } finally {
      if (registered == null) {
        CoroutineInstrumentationOwners.register(replacement)
        registered = replacement
      }
      CoroutineInstrumentationOwners.unregister(registered)
    }
    assertFalse(Runtime.hasCoroutineInstrumentationOwner())
  }
}
