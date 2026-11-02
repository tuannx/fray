# Kotlin/JVM coroutine investigation

This is an opt-in investigation, not a claim that Fray supports Kotlin coroutines. The probes use
`runBlocking` and the real `Dispatchers.Default` with `async` or `launch`, plus an intentional
shared counter race and a `Mutex`-protected counterpart. Android ART, Kotlin/Native, JS and Wasm
are outside its scope. These non-isolated probes have not demonstrated race detection or coroutine
replay. A newer disposable-worker experiment found and replayed one controlled race; see
[the lab report](../../EXPERIMENT.md) for its results and limits.

Kotlin is a language; Kotlin/JVM produces JVM code. A suspending function is compiled using
continuations and a state machine, and can resume on a different thread. `Dispatchers.Default` uses
a shared JVM worker pool; `Mutex` suspends a coroutine rather than simply blocking a thread. Fray
currently schedules JVM threads and intercepts selected JVM operations, so the interaction with
that pool and suspension machinery needs separate testing. See the official
[Kotlin coroutine specification](https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html),
[dispatcher documentation](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html),
and [shared-state guide](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html).

## Run the probes

The probes can hang. On POSIX, run one at a time with the external process timeout below. The
limit includes Gradle startup and build time; verify the log reached `:integration-test:test`
before treating a timeout as a test hang. The script exits 124 on timeout. Set `JAVA_HOME` and
`JDK25` to a JDK 25, and `JDK11` to a JDK 11. The script uses `--offline`, so dependencies must
already be in the Gradle cache.

```sh
python3 integration-test/coroutine-poc/run.py baseline --timeout 90
python3 integration-test/coroutine-poc/run.py mutex --timeout 75
python3 integration-test/coroutine-poc/run.py race --timeout 75
python3 integration-test/coroutine-poc/run.py baseline --memory --timeout 90
```

The workload now lives in `org.pastalab.fray.test.poc`, a namespace Fray instruments. The race
uses separate `AtomicInteger.get` and `set` operations, with the same operations inside `Mutex` for
the protected case. The runner uses `interleaveMemoryOps=false` by default, matching the JUnit
extension; `--memory` enables it. The baseline and mutex probes also require a completion counter:
`TestRunner.run() == null` alone does not prove completion, because a scheduling step limit can
stop exploration without recording an application bug. A bounded failure to find a race likewise
does not prove correctness. Reports are written to `integration-test/build/coroutine-poc/<case>/`.

For a normal JVM control without the Fray agent, first compile the fixtures and use the same JDK
and cached dependencies:

```sh
./gradlew :integration-test:classes
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
COROUTINES_JAR=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.1" -name '*.jar' | head -1)
STDLIB_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.2.21" -name '*.jar' | head -1)
"$JAVA_HOME/bin/java" -cp "integration-test/build/classes/kotlin/main:$COROUTINES_JAR:$STDLIB_JAR" org.pastalab.fray.test.poc.CoroutineControl
```

This control runs only baseline and mutex; it does not test Fray or the race probe.

## What the investigation found

The environment was macOS arm64, Corretto 25.0.1, Gradle 9.0.0, Kotlin 2.2.21 and
`kotlinx-coroutines-core` 1.10.1, based on Fray `e39d21675293164602756bb8210c7f4e6584b879`.

- The **original** POC placed its workload in `org.pastalab.fray.poc`, a namespace Fray excluded
  from application instrumentation. Its normal-JVM baseline and mutex completed; under Fray,
  baseline, mutex and race reached the test task but exceeded 90, 75 and 75 second external
  deadlines respectively. These are historical observations of the excluded fixture, not race
  coverage for the corrected fixture.
- A corrected, instrumentable fixture still reached the test task and exceeded a 120-second
  deadline when only the thread-bookkeeping guard had been changed. There was no completed
  coroutine test or demonstrated replay in that run.
- A controlled class-initialization reproducer showed a JVM initialization-lock deadlock: a Fray
  scheduling point inside an excluded initializer let a second thread wait on the same class.
  An isolated experiment applying Fray's existing class-initialization scheduling guard to the
  reproducer completed. This proves that interaction in the reproducer, not that every coroutine
  timeout has the same cause.
- An excluded Java helper calling `LockSupport.parkNanos` on a managed worker exceeded a 60-second
  deadline; selectively applying Fray's existing timed-wait call-site transform to that helper
  completed the same sentinel-checked test. The coroutine scheduler has an analogous timed park,
  but a timed-park-only coroutine experiment did not complete and hit a liveness cutoff or an
  out-of-memory error. It is not a sufficient fix.
- In a separate combined instrumentation experiment the `runBlocking` workload reached its
  completion marker, but teardown remained blocked on the shared `Dispatchers.Default` workers
  and the run eventually exhausted memory. Fray's existing pool teardown recognizes its own
  replacement `ForkJoinPool`, not this long-lived shared pool. Ignoring every daemon or shutting
  down the global dispatcher would risk missing live work or contaminating later iterations. A
  safe worker ownership, quiescence and iteration-isolation design is still needed before claiming
  support. The experiment was not a complete successful Fray test.

The diagnostic observations identify several distinct interactions; they do not establish the
root cause of every timeout. A newer experiment uses a disposable worker per iteration, with
bounded results and remaining lifecycle and replay limitations described in the lab report.

## Historical focused fixes and checks

The earlier change guarded Fray's thread-bookkeeping lookup against reentrant scheduling. Its
focused unit regression passed 1/1; the same test failed on the pre-fix runtime. It also applied
the existing class-initialization scheduling guard to `kotlinx.coroutines` initializers without
enabling general coroutine-library instrumentation. Its bounded, deterministic regression passed
1/1; the pre-fix reproduction timed out. These fixes address the measured mechanisms; they do not
resolve the shared dispatcher lifecycle or establish coroutine support. After both fixes, the
corrected baseline command `python3 integration-test/coroutine-poc/run.py baseline --timeout 90`
reached the test task and exited 124 at its external deadline, which included build time; it did
not produce a completed test result. These checks predate the later worker and ownership changes;
they do not validate the current tree. See the lab report for later checks.

```sh
./gradlew --offline --no-daemon :core:test \
  --tests org.pastalab.fray.core.delegates.RuntimeDelegateThreadGuardTest
./gradlew --offline --no-daemon :instrumentation:base:spotlessApply \
  :integration-test:spotlessApply :integration-test:test \
  --tests org.pastalab.fray.poc.CoroutineClinitTest
./gradlew --offline --no-daemon :core:test \
  :instrumentation:base:spotlessCheck :integration-test:spotlessCheck
./gradlew --offline --no-daemon :instrumentation:base:spotlessCheck \
  :instrumentation:base:test --tests trycatch.CoroutineInitializerTransformTest
./gradlew --offline --no-daemon :instrumentation:base:test
./gradlew --offline --no-daemon :integration-test:test \
  --tests org.pastalab.fray.test.FrayTestCase.testOne \
  --tests org.pastalab.fray.poc.CoroutineClinitTest
```

The core and formatting command passed all 15 core tests without skips and both formatting
checks. The focused instrumentation command passed 2/2, covering explicit prefix exclusions and
normal and exceptional initializer exits; the full instrumentation base suite passed 5/5 without
skips. The selected integration command passed 2/2 with no
skips; `FrayTestCase.testOne` does
not assert the runner's result. An aggregate integration run at that stage reached
the tests but exited 124 at an external 120-second deadline, including startup. Partial output
showed `AsyncClientConnectNoServerException` failing at `FrayTestCase.java:47`; there was no final
count or fresh XML. Its cause is unknown.

## Earlier regression checks

The following commands ran with JDK 25 as `JAVA_HOME` and `JDK25`, JDK 11 as `JDK11`, and a
populated isolated Gradle cache. These checks predate the subsequent guard and initializer edits;
they are not a substitute for validation of the current tree.

```sh
./gradlew --offline --no-daemon :integration-test:test \
  --tests org.pastalab.fray.test.FrayTestCase.testOne \
  :core:test --tests org.pastalab.fray.core.scheduler.ReplaySchedulerTest
./gradlew --offline --no-daemon :integration-test:spotlessCheck :core:test
```

The selected integration regression completed 1/1 and the selected replay scheduler tests 2/2;
that integration test does not assert the runner result, and the scheduler tests do not demonstrate
coroutine replay. Formatting and all 14 core tests passed.

The aggregate integration command is `./gradlew --offline --no-daemon :integration-test:test`.
The earlier run was stopped at an external
120-second deadline (exit 124, including startup). Two network fixtures,
`AsyncClientConnectNoServerException` and `SyncClientExceptionWithoutServer`, failed before the
interruption; there was no final count or fresh XML. Both failures also reproduced on unchanged
upstream `e39d216` when `FrayTestCase.testCases` was stopped after a 240-second external deadline.
Those fixtures expect no server at `localhost:12345`; a listener was observed during the test, but
the root cause of the failures has not been proven. A full repository build/check was not run at
that stage; the lab report describes later checks.
