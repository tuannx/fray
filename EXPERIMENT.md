# Kotlin/JVM coroutine experiment

**Status: experimental and not ready for upstream.** This lab explores an
opt-in Kotlin/JVM subset using the real `Dispatchers.Default`. A disposable
worker has completed baseline and Mutex trials and replayed one controlled
race. An experimental opt-in CLI is available; see [CLI setup and supported
contract](docs/cooperative-cli.md). Trusted runtime distribution and broader
validation remain unfinished; these results do not establish general coroutine support.

Replay schema 5 fingerprints the whole observed JDK image before and after each child and requires
the same image across a CLI seed campaign. Its bounded identity includes paths, entry types, file
bytes, POSIX permissions, owner/group names and modification times; relative symlinks must remain
within the image.
Changing timestamps, including by copying without preservation, can invalidate replay. Schema 4
recordings and manifests without an explicit schema are rejected and must be re-recorded. This is
an identity check, not independent authentication: OS libraries, CPU state, ACLs and
extended attributes are not bound, and the caller must keep inputs trusted and immutable throughout
the run because replace-and-restore races can escape pre/post checks.

Backend cleanup verifies the direct worker only, not arbitrary descendants; cooperative workloads
exclude operating-system process creation. The lab watchdog's process-group `SIGKILL` is best effort:
descendants that change group/session and uninterruptible processes are not guaranteed to stop.
The signal is asynchronous, and cleanup is not guaranteed if the watchdog itself is killed or
crashes. Neither mechanism is a security sandbox.

The remaining blockers include independently authenticated framework/runtime inputs,
unsupported coroutine features, and unexplored workloads and replay cases.
Android ART, Kotlin/Native, JS and Wasm are outside this work.

## Why this is difficult

Kotlin/JVM is Kotlin code running on the JVM; coroutine suspension adds another
layer to JVM threads. Kotlin specifies that suspend functions are compiled into
[state machines](https://kotlinlang.org/spec/asynchronous-programming-with-coroutines.html),
and the real [Default dispatcher uses a shared thread pool](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-default.html).
The pool's workers can outlive one test iteration, whereas Fray tears down its
per-iteration scheduler state. `runBlocking` also has its own
[event loop](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html).
Waiting for a method to return or a queue to be empty does not establish that
all suspended work or callbacks have finished.

## Measured results

All rows are local lab experiments, not upstream test results or supported API
claims. Later source edits mean a past pass is not a pass of the current tree.
The environment was macOS AArch64, Corretto 25.0.1+8, Temurin 11.0.32.1+1,
Gradle 9.0.0, Kotlin 2.2.21 and kotlinx-coroutines-core 1.10.1.

| Check | Result and evidence |
| --- | --- |
| Full repository `check`, schema 5 | Passed in 4m18s; 13 tasks executed, 171 up-to-date. Core 106 passed/2 skipped, instrumentation 92 passed, integration 77 passed/23 skipped, JUnit 16 passed/3 skipped. `fix-full-check.log` |
| Earlier full repository `check` | Passed on repeat in 57 seconds; 19 tasks executed and 165 up-to-date from a preceding run. `ownership-gate-full-check-repeat.log` |
| Ownership regression | The new focused test failed before the fix and passed after it; both publication tests passed. `legacy-ownership-regression-red.log`, `ownership-gate-focused.log` |
| Fixed JUnit | 16 passed, 3 skipped, twice in fresh workers with a rebuilt JDK. `ownership-gate-junit-run1.log`, `ownership-gate-junit-run2.log` |
| Core and instrumentation after fix | Core 89 passed / 2 skipped; instrumentation 92 passed, including the new gate tests. `ownership-gate-full-check.log` |
| Integration after fix | Integration 77 passed / 23 skipped; runner script passed. JUnit 16 passed / 3 skipped. Logs included `TargetTerminateException` uncaught-handler messages. `ownership-gate-full-check.log` |
| Earlier relevant module checks | Formatting passed; 80 core, 86 instrumentation and 77 integration tests passed, with 25 opt-in tests skipped. The integration log includes `TargetTerminateException` uncaught-handler output despite the passing task. `final-relevant-check.log` |
| Fresh child baseline and Mutex, Nov 1 12:05 | 10/10 baseline and 10/10 Mutex passed; admission fixture 2/2 and selected Java regression 1/1 passed. `generation-fresh-v1-xml` |
| Seeded fresh child cases, Nov 1 12:07 | 20/20 baseline and 20/20 Mutex passed with seeds 1–20. Each selected case used one iteration in a fresh child. `generation-seeded-v1.xml` |
| Initial actual worker | Baseline and Mutex each completed once in separate JVMs; host-bridge and non-entry class-shadow negatives were rejected. This first run supplied an extra ASM JAR for a packaging gap, which is being corrected. `worker-e2e-v5.xml` |
| Worker and CLI, schema 5 | Two opt-in tests passed in 2m30s: baseline and Mutex 10/10 each; seed 74231 found one expected race and replayed 5/5 times in fresh JVMs. CLI discovery/replay and negative controls passed. `fix-worker-e2e-final-results` |
| Worker and CLI, rebuilt JDK image | Baseline and Mutex each completed once; race and strict replay each completed once, with CLI discovery/replay, in 42s. `fix-worker-final-jdk-results` |
| Worker and CLI, earlier schema 4 tree | Two opt-in JUnit tests passed: baseline and Mutex each completed 10/10 fresh child JVMs, seed 74231 found one expected race and replayed 5/5 times in fresh JVMs with strict trace, random stream and artifact checks. The actual CLI completed baseline and discovery/persist/replay, and refused output reuse. `ownership-gate-worker-xml` |
| Worker, packaged runtime | Baseline and Mutex each completed 10/10 fresh JVMs with seeds 1–10. A race found at seed 74231 replayed in five fresh JVMs; both strict verifiers and integer consumption passed, original artifacts stayed unchanged, and host-bridge, non-entry shadow and truncated-trace controls were rejected. `worker-full-e2e-v2-xml` |
| Worker cancellation and suspension, schema 5 | The selected structured cancellation case completed with teardown; detached `GlobalScope` was rejected. The suspended case was `INCONCLUSIVE` after runner start; its exact suspension point was not observed. `fix-worker-e2e-final-results` |
| Strict loader with real agent, schema 5 tree | Six synthetic child tests passed, including real field read/write hooks and rejection of absent agent, excluded class, unknown class, early boundary and wrong digest. `fix-strict-agent.log` |
| Race and boundaries | With partial symmetric framework bootstrap, the same seeded race was found and replayed in a fresh JVM 5/5 times; both strict trace verdicts and recorded integer consumption passed. Earlier preload-only replay failed 5/5 at index 9. Cancellation and three selected detached-job rejection tests passed (4/4). `symmetric-replay-v2.xml`, `generation-replay-preload-v1.xml` |
| Earlier formatting check | Failed before formatting was applied; the later formatted lab validation passed. `generation-format-v1.log` |
| Integration aggregate | 77 passed, 17 opt-in tests skipped, 0 failed in 20 seconds. The log contains `TargetTerminateException` uncaught-handler messages. `formatted-integration-aggregate-v1.log` |
| Upstream network control, Nov 1 11:51 | Two selected network cases passed on the unchanged upstream control checkout. This does not establish why the earlier aggregate failed. `upstream-network-control.xml` |

The first schema 5 end-to-end run passed its baseline, Mutex, replay and CLI
checks but failed the suspended-work test: the old 5-second budget expired in
post-run image verification. The test now allows 15 seconds and distinguishes a
verification deadline from an image mismatch. Its final result still does not
prove that the exact suspension site was reached. The watchdog regression suite
passed 11/11 on macOS; it exercises timeout, surviving group children,
cancellation, signal masks and an unrelated process group.

The earlier full check first lacked the core fat JAR, then `:core:shadowJar check`
failed at `:junit:test`. The first captured failure was a `StackOverflowError` in
JUnit `ExceptionUtils` class initialization: class loading and Fray scheduling-skip
callbacks reentered one another. The empty skip stack was later teardown fallout.
A fresh build of commit `8d14cd9` passed JUnit (16 passed, 3 skipped). With the
same experimental JDK image, disabling only the new unconditional ownership
probe made JUnit pass twice. The focused regression test showed the old code
called coroutine ownership hooks even for legacy class loads.

The fix adds a volatile, default-off hint in the bootstrap runtime. It is a
negative filter only: an active owner still goes through the existing locked
identity and graph checks. It is published after registration state is ready and
cleared at unregister; the lifecycle still requires registration before class
definition and quiescence before unregister. With a freshly rebuilt JDK, the
focused regression and publication tests passed, and JUnit passed twice. The
JUnit output still contained `TargetTerminateException` uncaught-handler
messages, which were also observed in the passing upstream control. Relevant
local evidence: `junit-control-8d-fresh.log`,
`junit-no-graph-probe-ablation.log`, `junit-no-graph-probe-ablation-repeat.log`,
`legacy-ownership-regression-red.log`, `ownership-gate-focused.log`, and
`ownership-gate-junit-run1.log` / `ownership-gate-junit-run2.log`.

The earlier mixed-image control timed out at 150 seconds. A proposed reentrancy
guard led to a `LinkageError` and a 120-second timeout; that unproven guard was
removed. Earlier failures and the unchanged-upstream control are preserved in
`final-full-check-v3.log`, `final-full-check-v4.log`, `lab-junit-repeat.log`,
`junit-rebuilt-jdk.log`, `upstream-junit-control.log`,
`lab-upstream-image-control.log` and `guard-junit.log`.

In an **earlier** lab version, baseline and Mutex each completed 10 iterations
in one child; cancellation and three selected detached-job cases passed. An
older controlled lost update (`expected 20, got 17`) was found and replayed
in fresh JVMs 5/5 times with a legacy trace and recorded integer stream. The
later worker replay result is for one controlled fixture and seed.
Legacy recording omitted complete runnable candidates, resource identity and
callsites; even the stricter trace does not prove replay for arbitrary workloads
or a warm pool. The saved worker artifact has 974 schedule steps and 121
integer draws, with no doubles. Earlier divergent attempts are preserved in
the evidence directory.

An adversarial test showed a parent-loaded host object retaining a child
callback, which ran **after** reported teardown; that test failed. A separate
foreign-loaded unfinished job was rejected. This demonstrates that job counts
and classloader isolation alone are insufficient. Subsequent gate and admission
experiments do not yet prove every callback source or event-loop path is safe.

The original POC without the lab isolation timed out: baseline at external
70 and 90 seconds, and Mutex and race at 75 seconds each. The interrupted
120-second aggregate reported two network-fixture failures and has no complete
final count; a later aggregate also timed out. Their root causes remain unknown.
A subsequent integration aggregate passed as shown above. The first full repository
check could not start because the offline cache lacked `commons-compress:1.28.0`;
the declared artifact was then resolved from the existing configured repository.
The selected upstream controls passed
separately, so they do not explain the earlier aggregate outcome. An earlier
worker cancellation run was inconclusive because the loader poisoned
coroutine's `android.os.Build` absence probe. The current JVM-only path permits
that exact verified-absent probe; it does not support Android ART.

## Candidate design and limits

Use an explicitly admitted static no-argument entrypoint and a disposable JVM
per iteration. Pin the runtime and dependencies, bound time/output, track the
owned coroutine jobs and scheduler tasks, and verify teardown before reporting
success. Unsupported entrypoints, unknown dependencies, host interop, external
callbacks and unsupported coroutine operations must fail closed. A separate
process isolates Java references between iterations, but it is **not a security
sandbox** for arbitrary bytecode, native code, files or network side effects.

The lab includes a worker protocol, syntactic admission experiments, an owned
coroutine loader and lifecycle hooks, and stricter trace experiments. The draft
worker connects these for bounded baseline/Mutex and expected-failure replay
probes. Syntactic admission alone does not prove receiver or callback
provenance. Task identity, event-loop paths,
worker termination, cancellation, escaped work and per-iteration cleanup need
end-to-end validation. The strict trace and random stream must be bound to the
replay artifact; mismatches must remain failures rather than silently dropping
application scheduling points.

The worker accepts only an explicitly expected error at an admitted app site;
it rejects runtime errors, cancellation, termination, causes and suppressed
errors rather than classifying them as application bugs. This conservative
classifier is not a general exception classifier.
The latest graph check validates pinned class snapshots and exact application
member declarations, including inherited-method and parent-prefix negatives;
it passes on the three real Kotlin fixture entrypoints. Its manifest is
caller-supplied and does not independently establish trust in the framework or
agent chain; arbitrary external callbacks and host interop remain unsupported.

An experimental frozen-graph loader now defines application classes from
verified bytes and requires a one-use transformer acknowledgment; it denies
unknown classes and resource lookups under an explicit trusted runtime policy.
An independent review found that initializer field accesses were omitted by
Fray instrumentation; the admission gate now accepts only narrow compiler-shaped
initializers and rejects shared access or other shapes. These checks are
conservative and are not a general provenance proof. Its unit tests passed,
but a caller-supplied framework manifest and fixed transformer chain remain
trust preconditions; a later transformer could alter bytes after acknowledgment.
The worker and CLI runs through this loader are noted above; broader validation
remains necessary. An independent review also found that a simultaneous internal
error could be hidden as UNSUPPORTED; the worker now checks helper and uncaught
failures and the rejection identity first, returning INCONCLUSIVE on conflict.
The runner can retain only its first error, so this does not prove all concurrent
failures are observed.

## Reproduce local checks

The included watchdog `scripts/coroutine-lab-run.py`, the lab sources, both JDKs and
an already populated offline Gradle cache are required. Export `JDK25`, `JDK11`
and `GRADLE_USER_HOME` to suit the machine. Raw evidence is in the adjacent
`fray-lifecycle-evidence` directory; logs can contain local paths and should be
reviewed and sanitized before sharing. The commands below use a 240- or 300-second outer limit, including Gradle startup;
most integration children have a 60-second limit. The suspended-work negative
probe uses a 15-second limit that also covers image hashing.

```bash
export JAVA_HOME="$JDK25"
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4'
common=(./gradlew --offline --no-daemon --max-workers=1
  '-Dorg.gradle.jvmargs=-Xmx1200m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8'
  -Pkotlin.compiler.execution.strategy=in-process)
python3 scripts/coroutine-lab-run.py ../fray-lifecycle-evidence/unit-repeat.log 240 \
  "${common[@]}" :core:test :instrumentation:base:test :integration-test:compileTestJava
```

To run the actual worker probe, rebuild the lab JDK after runtime changes and
run the following build. Set `COROUTINES_JAR` to the pinned 1.10.1 JAR, and
choose a new empty local evidence directory.

```bash
"${common[@]}" :core:copyDependencies :integration-test:classes \
  :instrumentation:jdk:build :jvmti:build
```

```bash
FRAY_COOPERATIVE_SEEDS=1,2,3,4,5,6,7,8,9,10 \
FRAY_COOPERATIVE_REPLAY=true FRAY_COOPERATIVE_REPLAY_COUNT=5 \
FRAY_COOPERATIVE_CLI=true FRAY_COOPERATIVE_NEGATIVES=true \
FRAY_COOPERATIVE_EVIDENCE_DIR="$PWD/../fray-lifecycle-evidence/worker-artifacts-repeat" \
python3 scripts/coroutine-lab-run.py ../fray-lifecycle-evidence/worker-repeat.log 300 \
  python3 scripts/cooperative-worker-test.py "${common[@]}" \
  :core:test --rerun --tests org.pastalab.fray.core.coroutines.CooperativeEpochIntegrationTest \
  --tests org.pastalab.fray.core.coroutines.CooperativeNegativeWorkerTest
```

The explicit `--rerun` matters: Gradle does not track these environment variables
or the externally rebuilt JDK as test inputs. The wrapper pins the local dependency
set for this experiment and excludes optional terminal providers. Set `FRAY_COOPERATIVE_NEGATIVES=true` and select
`CooperativeNegativeWorkerTest` to run the separate 15-second watchdog and
cancellation/detached controls. A watchdog result alone does not prove the
exact suspension point was reached.

Use the same wrapper and `"${common[@]}"`, replacing the tasks with these
exact task arguments for each separate experiment:

```bash
:integration-test:test --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.freshProcessBaseline --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.freshProcessMutex --tests org.pastalab.fray.poc.CooperativeDefaultAdmissionTest --tests org.pastalab.fray.test.FrayTestCase.testOne -Dfray.coroutine.experiment=true
:integration-test:test --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.seededFreshBaseline --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.seededFreshMutex -Dfray.coroutine.experiment=true
:integration-test:test --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.raceAndReplay --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.cancellation --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.rejectsDetachedSuspended --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.rejectsDetachedCompleted --tests org.pastalab.fray.poc.CoroutineIsolationExperiment.rejectsDetachedLazy -Dfray.coroutine.experiment=true
```

For the partial-bootstrap replay result set `FRAY_SYMMETRIC_BOOTSTRAP=1` when
invoking the wrapper. Record and replay use separate reports; the bootstrap is
not fully identical and replay uses `isReplay=false` solely for the experiment.
For the earlier preload result set `FRAY_RACE_PRELOAD=1` when invoking the
wrapper. The sentinel uses system properties only as a diagnostic and runs
under Fray's existing class-initializer policy. Set `FRAY_MUTEX_TRACE=1` for
bounded park and tracker diagnostics or `FRAY_RESPECT_TIMED_BLOCKS=1` for the
timed-block control; diagnostic instrumentation can change timing.

To repeat the cold JUnit control, run `"${common[@]}" :junit:test --rerun` under a 120-second watchdog. To exercise the gate regression and publication tests, run `"${common[@]}" :instrumentation:base:test --tests trycatch.LegacyOwnershipProbeTest --tests trycatch.CoroutineOwnerPublicationTest`.

For the full repository check, without the optional offline flag and with the existing configured repositories, use `./gradlew --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1200m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process :core:shadowJar check` under a 360-second watchdog. The schema 5 run passed, reusing completed tasks; the earlier schema 4 run required a repeat after its 240-second watchdog.

For formatting, add `--continue` to the common Gradle arguments and run
`:core:spotlessCheck :runtime:spotlessCheck :instrumentation:base:spotlessCheck :integration-test:spotlessCheck`.
For the upstream control, use a separate clean checkout at
`e39d21675293164602756bb8210c7f4e6584b879`, set `LAB` and `UPSTREAM` to the
two checkout paths, and verify that no server is listening on localhost port
12345. Copy the provided selector, which leaves the original fixtures intact:

```bash
cp "$LAB/scripts/upstream-network-control/UpstreamNetworkControl.java" \
  "$UPSTREAM/integration-test/src/test/java/org/pastalab/fray/test/UpstreamNetworkControl.java"
cd "$UPSTREAM"
python3 "$LAB/scripts/coroutine-lab-run.py" "$LAB/../fray-lifecycle-evidence/upstream-repeat.log" 240 \
  "${common[@]}" :integration-test:test --tests org.pastalab.fray.test.UpstreamNetworkControl
```

These commands rerun current sources and may differ from the dated outcomes.

The lab-generated `instrumentation/jdk/build/java-inst` can be stale after
runtime or instrumentation edits; rebuild that generated image before relevant
integration runs. Never delete or modify an installed host JDK.
