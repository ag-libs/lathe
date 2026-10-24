plugins {
  `java-gradle-plugin`
  `maven-publish`
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
  plugins {
    create("lathe") {
      id = "io.github.ag-libs.lathe"
      implementationClass = "io.github.aglibs.lathe.gradle.LatheSettingsPlugin"
    }
  }
  testSourceSets(functionalTest)
}

// The plugin as a user's init script resolves it: from a repository, not TestKit's injected
// classpath, which reaches only plugins {} blocks.
val fixturesRepo = layout.buildDirectory.dir("fixtures-repo")

publishing { repositories { maven { name = "fixtures"; url = uri(fixturesRepo) } } }

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
      // parallel JVMs. One class's tests stay in one JVM, in order.
      maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
      dependsOn("publishAllPublicationsToFixturesRepository")
      systemProperty("lathe.gradle.fixturesRepo", fixturesRepo.get().asFile.path)
      systemProperty("lathe.gradle.latheRepo", latheRepo)
      systemProperty("lathe.version", project.version)
      systemProperty(
          "lathe.gradle.fixturesCache", layout.buildDirectory.dir("lathe-cache").get().asFile.path)
      enabled = !flag("skipTests")
      systemProperty(
          "lathe.gradle.fixturesHome",
          optional("lathe.gradle.fixturesHome").getOrElse(gradle.gradleHomeDir!!.path))
      optional("lathe.gradle.fixturesJavaHome").orNull?.let {
        systemProperty("lathe.gradle.fixturesJavaHome", it)
      }
    }

tasks.check { dependsOn(functionalTestTask) }
