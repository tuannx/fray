import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem

plugins {
  id("java")
  alias(libs.plugins.kotlin.jvm)
}

group = "org.pastalab.fray.test"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

kotlin {
  jvmToolchain(25)
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(24) }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
  testImplementation(project(":core"))
  testImplementation(project(":instrumentation:base"))
  testImplementation(libs.classgraph)
  testRuntimeOnly(libs.junit.platform.launcher)
  testCompileOnly(project(":runtime"))
  testCompileOnly(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.engine)
  testImplementation(libs.junit.platform.testkit)
}

tasks.test {
  systemProperty("fray.coroutine.poc", System.getProperty("fray.coroutine.poc", "false"))
  systemProperty("fray.coroutine.memory", System.getProperty("fray.coroutine.memory", "false"))
  systemProperty(
      "fray.coroutine.experiment",
      System.getProperty("fray.coroutine.experiment", "false"),
  )
  useJUnitPlatform()
  maxParallelForks = 1
  val jvmti = project(":jvmti")
  val jdk = project(":instrumentation:jdk")
  val agent = project(":instrumentation:agent")
  val soSuffix = if (getCurrentOperatingSystem().toFamilyName() == "windows") "dll" else "so"
  val javaExec = if (getCurrentOperatingSystem().toFamilyName() == "windows") "java.exe" else "java"
  executable(
      "${jdk.layout.buildDirectory.get().asFile}${File.separator}java-inst${File.separator}bin${File.separator}$javaExec"
  )
  dependsOn(":instrumentation:jdk:build")
  dependsOn(":instrumentation:agent:build")
  dependsOn(":jvmti:build")
  doFirst {
    jvmArgs("-ea")
    jvmArgs("-verify")
    jvmArgs(
        "-agentpath:${jvmti.layout.buildDirectory.get().asFile}${File.separator}native-libs${File.separator}libjvmti.$soSuffix"
    )
    jvmArgs(
        "-javaagent:${agent.layout.buildDirectory.get().asFile}${File.separator}libs${File.separator}" +
            "${agent.base.archivesName.get()}-${agent.version}.jar"
    )
    jvmArgs("-Dfray.debug=false")
  }
}

if (getCurrentOperatingSystem().toFamilyName() != "windows") {
  tasks.register(
      "testRunnerScript",
      Action {
        val integrationTestJar = project.tasks.jar.get().archiveFile.get().asFile.absolutePath
        dependsOn(":core:genRunner")
        doLast {
          val process =
              ProcessBuilder(
                      "./fray",
                      "-cp",
                      integrationTestJar,
                      "org.pastalab.fray.test.core.success.threadpool.ScheduledThreadPoolWorkSteal",
                  )
                  .redirectErrorStream(true)
                  .directory(
                      file(
                          "${rootProject.projectDir.absolutePath}${File.separator}bin${File.separator}"
                      )
                  )
                  .start()
          process.waitFor()
          val output = process.inputStream.bufferedReader().readText()
          if (!output.contains("[INFO]: Error: org.pastalab.fray.runtime.DeadlockException")) {
            throw GradleException("Runner script test failed. Output:\n$output")
          }
        }
      },
  )

  tasks.named("check") { dependsOn("testRunnerScript") }
}
