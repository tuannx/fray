# Experimental Kotlin/JVM coroutine probes

These opt-in probes exercise `runBlocking`, real `Dispatchers.Default`, `async`/`launch`,
an intentionally racy counter, and a `Mutex` counterpart. They are not a claim of Kotlin
coroutine or Android support. They use Fray's `TestRunner` directly; by default memory-operation
interleaving is off, matching the current JUnit extension setting. `--memory` enables it explicitly.
The race probe expects an application `lost update` failure, and rejects Fray internal errors;
no observed race in a bounded search is not evidence of correctness. Reports are written to
`integration-test/build/coroutine-poc/<case>/` and schedule recording is enabled.

The probes are opt-in because the baseline has been observed to hang in Fray. On POSIX, run one
at a time with the external timeout below. The timeout includes Gradle build time; check the log
for `:integration-test:test` before interpreting a timeout as a test hang. Dependencies must first
be available in the Gradle cache (`--offline` is used by the script). Set `JAVA_HOME` to JDK 25
and `JDK11` to a JDK 11 installation if Gradle cannot discover one.

```sh
python3 integration-test/coroutine-poc/run.py baseline --timeout 90
python3 integration-test/coroutine-poc/run.py mutex --timeout 90
python3 integration-test/coroutine-poc/run.py race --timeout 90
python3 integration-test/coroutine-poc/run.py baseline --memory --timeout 90
```

Tested locally at Fray `e39d21675293164602756bb8210c7f4e6584b879` with Corretto 25.0.1,
Kotlin 2.2.21, kotlinx-coroutines 1.10.1 and Gradle 9.0.0. The same baseline and mutex code
completed on a normal JVM without the Fray agent. The Fray baseline did not complete within a
90-second bounded command with default memory interleaving; a thread dump showed threads parked
in Fray while executing coroutine scheduler and continuation code. The mutex and race probes each timed
out after a 75-second bounded command. A separate run with memory interleaving enabled hung and
a thread dump showed Fray parking during coroutine scheduler worker creation.
This is an observation of this configuration, not a diagnosis of all coroutine behavior. No
application race or replay was demonstrated by these observations.

For a control outside Fray, first compile the fixtures (`./gradlew :integration-test:classes`),
then run them with the same JDK and dependencies. The cache paths vary by Gradle user home:

```sh
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
COROUTINES_JAR=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.1" -name '*.jar' | head -1)
STDLIB_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.2.21" -name '*.jar' | head -1)
"$JAVA_HOME/bin/java" -cp "integration-test/build/classes/kotlin/main:$COROUTINES_JAR:$STDLIB_JAR" org.pastalab.fray.poc.CoroutineControl
```

The control runs only the baseline and mutex workloads; it does not test Fray or the race probe.
