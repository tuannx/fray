#!/usr/bin/env python3
"""Prepare the opt-in local worker probe from already-built lab artifacts.

Run under coroutine-lab-run.py for an outer timeout. The caller supplies
JDK25, JDK11, GRADLE_USER_HOME and COROUTINES_JAR; no credentials are read.
"""
import os
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
if len(sys.argv) < 2:
    raise SystemExit("usage: cooperative-worker-test.py COMMAND [ARG ...]")
coroutines = Path(os.environ["COROUTINES_JAR"]).resolve(strict=True)
core_jar = (root / "core/build/libs/fray-core-0.9.1-SNAPSHOT.jar").resolve(strict=True)
terminal_providers = {
    "mordant-jvm-ffm-jvm.jar",
    "mordant-jvm-graal-ffi-jvm.jar",
    "mordant-jvm-jna-jvm.jar",
}
# The bounded worker does not use terminal-provider service discovery.
dependencies = [p for p in sorted((root / "core/build/dependency").glob("*.jar"))
                if p.name not in terminal_providers]
jars = [core_jar, *(p.resolve(strict=True) for p in dependencies)]
if coroutines not in jars:
    jars.append(coroutines)
if len(jars) != len(set(jars)):
    raise SystemExit("duplicate runtime JAR path")

def unique_owner(entry):
    owners = []
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            if entry in archive.namelist():
                owners.append(jar)
    if len(owners) != 1:
        raise SystemExit(f"expected exactly one owner of {entry}, got {len(owners)}")
    return owners[0]

unique_owner("org/pastalab/fray/core/coroutines/CoroutineEpochWorker.class")
stdlib = unique_owner("kotlin/Unit.class")
if unique_owner("kotlinx/coroutines/JobSupport.class") != coroutines:
    raise SystemExit("unexpected coroutine archive")

env = os.environ.copy()
env.update({
    "FRAY_COOPERATIVE_INTEGRATION": "true",
    "FRAY_COOPERATIVE_FIXTURE_DIR": str(root / "integration-test/build/classes/kotlin/main"),
    "FRAY_COOPERATIVE_JAVA": str(root / "instrumentation/jdk/build/java-inst/bin/java"),
    "FRAY_COOPERATIVE_AGENT": str(root / "instrumentation/agent/build/libs/fray-instrumentation-agent-0.9.1-SNAPSHOT.jar"),
    "FRAY_COOPERATIVE_JVMTI": str(root / "jvmti/build/native-libs/libjvmti.so"),
    "FRAY_COOPERATIVE_STDLIB": str(stdlib),
    "FRAY_COOPERATIVE_COROUTINES": str(coroutines),
    "FRAY_COOPERATIVE_JARS": os.pathsep.join(map(str, jars)),
})
os.execvpe(sys.argv[1], sys.argv[1:], env)
