# Experimental cooperative Kotlin/JVM CLI

This opt-in CLI runs one explicitly admitted static, public, no-argument `void` JVM method in a
fresh subprocess per seed. The method may use the supported structured `runBlocking`, real
`Dispatchers.Default`, and Mutex subset. It does not accept a lambda, JUnit test, method arguments,
custom dispatcher, detached work, external callbacks, host interop, or arbitrary dependencies.
Android ART, Kotlin/Native, JS and Wasm are outside its scope.

Admission is deliberately restrictive because Fray does not schedule field accesses in JVM
constructors or class initializers. It accepts only narrow compiler-shaped constructors that copy
arguments into the new instance and a narrow singleton initializer. Initializers with shared-field
access, helper calls, constructor chaining, branches, or other shapes are unsupported; even valid
ordinary Kotlin programs may be rejected. This is a cooperative-code contract, not a general proof
of object provenance or a hostile-bytecode security boundary.

The runtime, agent chain and manifest are trusted inputs. The process boundary is not a sandbox;
filesystem, network, native code or malicious bytecode can have effects outside the process. The
current admission checks and fixed runtime pins restrict the tested subset, but do not establish
general coroutine support or provenance for arbitrary code.

The CLI does **not** classify arbitrary exceptions as application bugs. To record or replay a
controlled failure, supply its exact exception class, top application class and method, and a
nonempty message prefix as `expectedFailure`. Unexpected exceptions, an escaping cancellation exception, runtime errors,
timeouts and incomplete cleanup are inconclusive. A structured cancellation that is fully handled
and joins its jobs may complete; only the bounded fixture has been tested. A successful replay checks the recorded prefix,
strict state and integer random stream for the selected fresh-process case; it does not prove
replay for other workloads or warm pools.

Run the public entrypoint with a trusted Fray runtime classpath on a JVM that can load the Fray
core and dependencies:

```sh
java -cp "$FRAY_CLI_CLASSPATH" org.pastalab.fray.core.coroutines.CooperativeCli --help
java -cp "$FRAY_CLI_CLASSPATH" org.pastalab.fray.core.coroutines.CooperativeCli run --config config.json
java -cp "$FRAY_CLI_CLASSPATH" org.pastalab.fray.core.coroutines.CooperativeCli replay --config replay-config.json --artifact-dir result/recording
```

The config is UTF-8 JSON. Every `sha256` is a **caller-supplied, reviewed lower-case SHA-256**, not
a hash the CLI treats as trusted merely because it was read from a file. Use the pinned
kotlinx-coroutines-core 1.10.1 and Kotlin 2.2.21 runtime expected by the experimental resolver.
The app-only JAR must contain every application class needed by the entrypoint, including its
Kotlin-generated continuation classes. The class inventory uses internal slash-separated names;
archive and class hashes must match those bytes exactly. Paths in the config are absolute or
relative to the config file. Supply the agent, JVMTI library, instrumented Java image and an ordered,
jar-only trusted runtime classpath separately from the app JAR.

```json
{
  "schema": 1,
  "runtime": {
    "java": {"path": "runtime/bin/java", "sha256": "<64 lowercase hex>"},
    "modules": {"path": "runtime/lib/modules", "sha256": "<64 lowercase hex>"},
    "agent": {"path": "lib/fray-agent.jar", "sha256": "<64 lowercase hex>"},
    "jvmti": {"path": "lib/libjvmti.so", "sha256": "<64 lowercase hex>"},
    "jars": [
      {"path": "lib/fray-core.jar", "sha256": "<64 lowercase hex>"},
      {"path": "lib/fray-dependencies.jar", "sha256": "<64 lowercase hex>"},
      {"path": "lib/kotlin-stdlib.jar", "sha256": "<64 lowercase hex>"},
      {"path": "lib/kotlinx-coroutines-core-jvm-1.10.1.jar", "sha256": "<64 lowercase hex>"}
    ],
    "stdlibJar": "lib/kotlin-stdlib.jar",
    "coroutineJar": "lib/kotlinx-coroutines-core-jvm-1.10.1.jar",
    "maxHeapMb": 384,
    "activeProcessors": 4
  },
  "app": {
    "archive": {"path": "app-only.jar", "sha256": "<64 lowercase hex>"},
    "classes": {"example/MyWorkload": "<64 lowercase hex>"}
  },
  "entrypoint": {"className": "example.MyWorkload", "methodName": "run"},
  "outputDirectory": "result",
  "timeoutMillis": 60000,
  "seeds": [1, 2, 3],
  "expectedFailure": null
}
```

Replace the placeholders, include *all* runtime dependency JARs and all app classes, and review
all sources before use. The sample names do not define a complete packaged runtime. The caller
must ensure the JDK image, framework classes and fixed transformer chain are trusted and are not
concurrently changed; SHA checks do not prevent a replacement-and-restore race. Unlisted runtime
classes and unsupported resource lookups are rejected. Seed lists contain 1–100 distinct integers;
the timeout covers input hashing as well as the child. Iterations run sequentially and stop at the
first result other than completed.

Replay manifest schema 5 binds a bounded fingerprint of the complete JDK image, including file
contents, paths, entry types, POSIX permissions, owner/group names and modification times. Discovery
and replay check the image before and after each child, and all seeds in one CLI command must use
the same image.
Relative symlinks must resolve inside the image; unsupported or escaping links and filesystems
without POSIX metadata are rejected. The fingerprint is observed from the caller's trusted image;
it is not an independent authenticity check. Touching files or copying the image without preserving
modification times can make replay inconclusive even if bytes are unchanged. Schema 4 and manifests
without an explicit schema are rejected; re-record them using schema 5.

The JDK fingerprint does not bind operating-system libraries, CPU state, ACLs or
extended attributes. Inputs still need to be trusted and immutable during the entire command: a
replacement followed by restoration between checks can evade pre/post scans.

`outputDirectory` must not already exist, its parent must exist, and it must not overlap an input,
replay directory, or the JDK image in either direction. The CLI creates it without replacing
existing files. For replay, make a separate config with a new output directory; the saved manifest
determines the original seed and expected failure, and its app/runtime identity and artifacts must
still match. Changes in pinned process policy, including relevant environment values such as locale
or timezone, can make replay inconclusive. The CLI does not overwrite the input artifacts.

Backend cleanup targets and verifies the direct worker process; it cannot verify all descendants.
The admitted cooperative workload excludes operating-system process creation. The separate lab
watchdog sends process-group `SIGKILL` on a best-effort basis; descendants that change process
group/session and uninterruptible processes are not guaranteed to stop. The signal is asynchronous,
and cleanup is not guaranteed if the watchdog itself is killed or crashes. Neither mechanism is a
security sandbox.

Each command prints one JSON result with status, reason, attempted/completed counts, optional seed,
recording directory and strict-replay flag. Exit codes are `0` for COMPLETED, `10` for
APPLICATION_BUG, `20` for UNSUPPORTED and `30` for INCONCLUSIVE. APPLICATION_BUG is returned only
after the three recording files and manifest are copied to the new output directory and their
hashes are verified. Child logs are local diagnostics under the output directory; review them for
personal data before sharing. A timeout is inconclusive, not evidence that a specific suspend site
was reached.
