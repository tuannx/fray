package org.pastalab.fray.core.delegates

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.pastalab.fray.core.RunContext
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.command.ExecutionInfo
import org.pastalab.fray.core.command.LambdaExecutor
import org.pastalab.fray.core.command.NetworkDelegateType
import org.pastalab.fray.core.command.SystemTimeDelegateType
import org.pastalab.fray.core.randomness.ControlledRandomProvider
import org.pastalab.fray.core.scheduler.RandomScheduler

class RuntimeDelegateThreadGuardTest {
  @Test
  fun threadCreateGuardCoversBookkeepingAndRestoresState() {
    val context =
        RunContext(
            Configuration(
                ExecutionInfo(LambdaExecutor({}), false, false, -1),
                Files.createTempDirectory("fray-guard"),
                1,
                30,
                RandomScheduler(),
                ControlledRandomProvider(),
                false,
                false,
                true,
                true,
                false,
                false,
                NetworkDelegateType.NONE,
                SystemTimeDelegateType.NONE,
                100_000L,
                true,
                false,
                true,
                false,
                false,
                false,
                null,
            )
        )
    val sync = DelegateSynchronizer(context)
    val delegate = RuntimeDelegate(context, sync)
    var lookups = 0
    var failLookup = false
    val map =
        object : ConcurrentHashMap<Long, ThreadContext>() {
          override fun containsKey(key: Long): Boolean {
            lookups++
            // Models a callback caused by the instrumented JDK implementation of containsKey.
            assertTrue(sync.entered.get(), "lookup must not reenter Fray scheduling")
            assertTrue(sync.checkEntered())
            if (failLookup) throw IllegalStateException("lookup failed")
            return super.containsKey(key)
          }
        }
    val field = RunContext::class.java.getDeclaredField("registeredThreads")
    field.isAccessible = true
    field.set(context, map)
    try {
      val unregistered = Thread()
      delegate.onThreadCreateDone(unregistered)
      assertEquals(1, lookups)
      assertFalse(sync.entered.get())
      assertEquals(null, map[unregistered.id])

      val current = Thread.currentThread()
      map[current.id] = ThreadContext(current, 0, context, -1)
      val system = Thread(null, {}, "JNA Cleaner")
      delegate.onThreadCreateDone(system)
      assertEquals(2, lookups)
      assertEquals(null, map[system.id])
      assertFalse(sync.entered.get())

      val normal = Thread()
      delegate.onThreadCreateDone(normal)
      assertEquals(3, lookups)
      assertTrue(map[normal.id] != null)
      assertFalse(sync.entered.get())

      sync.entered.set(true)
      delegate.onThreadCreateDone(Thread())
      assertEquals(3, lookups)
      assertTrue(sync.entered.get())
      sync.entered.set(false)

      failLookup = true
      assertFailsWith<IllegalStateException> { delegate.onThreadCreateDone(Thread()) }
      assertFalse(sync.entered.get())
    } finally {
      context.shutDown()
    }
  }
}
