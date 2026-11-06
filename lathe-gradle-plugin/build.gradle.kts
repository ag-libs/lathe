import org.gradle.plugin.compatibility.compatibility

plugins {
  // Publishes to the Gradle Plugin Portal; brings in java-gradle-plugin and maven-publish.
  id("com.gradle.plugin-publish") version "2.2.1"
  // Applied, it makes plugin-publish sign every artifact it publishes.
  signing
}

// Inside a Maven module: the build output goes under target/, so `mvn clean` removes it too.
layout.buildDirectory = layout.projectDirectory.dir("target/gradle")

group = "io.github.ag-libs"
version = property("lathe.version") as String

val latheRepo = property("latheRepo") as String

repositories {
  // Lathe's own jars only from the repository the reactor fills, never from a stale ~/.m2.
  maven {
    url = uri(latheRepo)
    content { includeGroup("io.github.ag-libs") }
  }
  mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 17
  options.encoding = "UTF-8"
}

val functionalTest = sourceSets.create("functionalTest")

configurations[functionalTest.implementationConfigurationName].extendsFrom(
    configurations.implementation.get(), configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get())

dependencies {
  implementation("io.github.ag-libs:lathe-install:$version")
  testImplementation(platform("org.junit:junit-bom:${property("junit.version")}"))
  testImplementation(gradleApi())
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.assertj:assertj-core:${property("assertj.version")}")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  functionalTest.implementationConfigurationName(gradleTestKit())
}

gradlePlugin {
  website = "https://github.com/ag-libs/lathe/blob/main/docs/guide/gradle.md"
  vcsUrl = "https://github.com/ag-libs/lathe"
  plugins {
    create("lathe") {
      id = "io.github.ag-libs.lathe"
      implementationClass = "io.github.aglibs.lathe.gradle.LatheSettingsPlugin"
      displayName = "Lathe"
      description =
          "Sets a Gradle build up for Lathe, a Java language server and MCP server for AI agents " +
              "that gets its project setup from the build: no project import, no classpath " +
              "configuration."
      tags = listOf("java", "language-server", "lsp", "ide", "neovim", "mcp")
      compatibility {
        features {
          configurationCache = true
          isolatedProjects = false
        }
      }
    }
  }
  testSourceSets(functionalTest)
}

// The plugin as a user's init script resolves it: from a repository, not TestKit's injected
// classpath, which reaches only plugins {} blocks.
val fixturesRepo = layout.buildDirectory.dir("fixtures-repo")

publishing { repositories { maven { name = "fixtures"; url = uri(fixturesRepo) } } }

// Signed with the release's own GPG key, the one Maven Central's artifacts are signed with; a build
// without the key (a local or CI test build) signs nothing.
signing {
  val key = providers.environmentVariable("GPG_PRIVATE_KEY")
  isRequired = key.isPresent
  if (key.isPresent) {
    useInMemoryPgpKeys(key.get(), providers.environmentVariable("GPG_PASSPHRASE").orNull)
  }
}

fun flag(name: String) = providers.gradleProperty(name).map(String::toBoolean).getOrElse(false)

fun optional(name: String) = providers.gradleProperty(name).filter(String::isNotBlank)

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// -DskipTests skips both test tasks; -Dmaven.test.skip.exec skips the unit tests only, as on the
// Maven side, where it still runs the invoker fixtures.
tasks.test { enabled = !flag("skipTests") && !flag("skipUnitTests") }

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
      description = "Runs the TestKit fixture builds."
      group = "verification"
      testClassesDirs = functionalTest.output.classesDirs
      classpath = functionalTest.runtimeClasspath
      shouldRunAfter(tasks.test)
      // Each fixture is a real Gradle build, slow to start (most on Windows): test classes run in
      // parallel JVMs, one core left for the build itself. One class's tests stay in one JVM, in
      // order.
      maxParallelForks = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
      // Each test as it starts and ends: CI stamps every log line with its time, so a slow or stuck
      // run shows which test it is in while it runs. On CI each fixture build's own output too, so
      // a stuck test also shows where in its build it stopped; a local run stays quiet.
      val ci = providers.environmentVariable("CI").isPresent
      systemProperty("lathe.gradle.fixturesOutput", ci)
      testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = ci
      }
      dependsOn("publishAllPublicationsToFixturesRepository")
      systemProperty("lathe.gradle.fixturesRepo", fixturesRepo.get().asFile.path)
      systemProperty("lathe.gradle.latheRepo", latheRepo)
      systemProperty("lathe.version", project.version)
      // Both kept out of the build directory, so a clean build starts the fixtures warm. TestKit's
      // Gradle home (generated Gradle API jars, compiled fixture scripts) is shared by every
      // checkout: a Gradle user home, which Gradle locks. The fixtures' Lathe cache stays one per
      // checkout, since each sync installs that checkout's own SNAPSHOT server into it.
      systemProperty(
          "lathe.gradle.testKitDir", gradle.gradleUserHomeDir.resolve("lathe-testkit").path)
      systemProperty(
          "lathe.gradle.fixturesCache",
          layout.projectDirectory.dir(".gradle/lathe-cache").asFile.path)
      enabled = !flag("skipTests")
      systemProperty(
          "lathe.gradle.fixturesHome",
          optional("lathe.gradle.fixturesHome").getOrElse(gradle.gradleHomeDir!!.path))
      optional("lathe.gradle.fixturesJavaHome").orNull?.let {
        systemProperty("lathe.gradle.fixturesJavaHome", it)
      }
    }

tasks.check { dependsOn(functionalTestTask) }
