package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheCaptureTaskFunctionalTest {

  private static final String SETTINGS = GradleFixture.latheSettings("app");

  @TempDir Path dir;

  // One project through the builds a developer's day brings. A build cache filled by a build
  // without Lathe serves the compile to one with it, as Lathe never changes the build's own tasks,
  // and the capture still runs; a wiped .lathe/ is refilled with the compile up to date, the
  // configuration cache stored and then reused; a compile that fails keeps the last good capture.
  @Test
  void capture_javaProject_capturesFromCacheRefillsAWipedLatheAndKeepsTheLastGoodOne()
      throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file(
                "settings.gradle",
                SETTINGS + "buildCache { local { directory = file(\"build-cache\") } }\n")
            .file("build.gradle", GradleFixture.JAVA)
            // A file declaring a type of another name: a normal build stops on a compile error,
            // so the capture trusts it and mirrors, without asking for a class per source.
            .file(
                "src/main/java/com/example/Helpers.java", "package com.example; class Helper {}\n")
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n")
            .file(
                "src/test/java/com/example/AppTest.java",
                "package com.example; class AppTest {}\n");

    fixture.runner("testClasses", "--build-cache", "-Plathe.disabled=true").build();
    FileUtil.deleteDir(dir.resolve("build"));
    final var fromCache =
        fixture.runner("testClasses", "--build-cache", "--configuration-cache").build();
    FileUtil.deleteDir(dir.resolve(".lathe"));
    final var refilled =
        fixture.runner("testClasses", "--build-cache", "--configuration-cache").build();
    final boolean refilledClasses =
        Files.exists(dir.resolve(".lathe/classes/com/example/App.class"));
    fixture.file("src/main/java/com/example/App.java", "package com.example; class App {\n");
    final var failed = fixture.runner("classes", "--continue").buildAndFail();

    assertThat(fromCache.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
    assertThat(fromCache.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(refilled.getOutput()).contains("Configuration cache entry reused.");
    assertThat(refilled.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    assertThat(refilled.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(refilledClasses).isTrue();
    assertThat(dir.resolve(".lathe/classes/com/example/Helper.class")).exists();
    assertThat(failed.task(":latheCaptureMain")).isNull();
    assertThat(dir.resolve(".lathe/classes/com/example/App.class")).exists();
    assertThat(dir.resolve(".lathe/test-classes/com/example/AppTest.class")).exists();
    assertThat(dir.resolve(".lathe").resolve(LatheLayout.compiledStampsFileName("classes")))
        .exists();
    final var main = params("", LatheLayout.CLASSES_DIR);
    assertThat(main.sourceRoots()).containsExactly(fixture.path("src/main/java"));
    assertThat(main.outputDir()).isEqualTo(fixture.path("build/classes/java/main"));
    // No --release recorded: Gradle compiled with -source/-target, as nothing set options.release.
    assertThat(main.release()).isNull();
    assertThat(main.proc()).isEqualTo("none");
    assertThat(main.compilerArgs()).containsExactly("-proc:none");
    assertThat(params("", LatheLayout.TEST_CLASSES_DIR).classpath())
        .contains(fixture.path("build/classes/java/main"));
  }

  // The capture is best-effort, as Maven's: a module lock it cannot take skips it, never the build.
  @Test
  void capture_moduleLockUnavailable_buildSucceedsWithoutACapture() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle", SETTINGS)
            .file("build.gradle", GradleFixture.JAVA)
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n");
    // A directory where the lock file goes: writing the lock fails.
    Files.createDirectories(
        dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.MODULE_LOCK_FILE));

    final var result = fixture.runner("classes").build();

    assertThat(result.getOutput()).contains("skipping .lathe/ capture");
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.paramsFileName("classes")))
        .doesNotExist();
  }

  // A build that points every compile, the test one too, at one generated-sources directory, as
  // Apache Fineract does. The compiles stand in for a processor, each writing a source there; the
  // test compile cleans the directory on a full compile, so the main capture must copy it first.
  @Test
  void capture_testCompileSharingMainsGeneratedSourcesDir_capturesMainsBeforeTheTestCompile()
      throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle", SETTINGS)
            .file(
                "build.gradle",
                GradleFixture.JAVA
                    + """
                    def shared = layout.buildDirectory.dir('generated/sources/annotationProcessor/java/main')
                    tasks.withType(JavaCompile).configureEach {
                      options.generatedSourceOutputDirectory = shared
                    }
                    tasks.named('compileJava') {
                      doLast { shared.get().file('MainGen.java').asFile.text = 'class MainGen {}' }
                    }
                    tasks.named('compileTestJava') {
                      doLast { shared.get().file('TestGen.java').asFile.text = 'class TestGen {}' }
                    }
                    """)
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n")
            .file(
                "src/test/java/com/example/AppTest.java",
                "package com.example; class AppTest {}\n");

    final var build = fixture.runner("testClasses").build();

    final List<String> order = build.getTasks().stream().map(BuildTask::getPath).toList();
    assertThat(order.indexOf(":latheCaptureMain")).isLessThan(order.indexOf(":compileTestJava"));
    assertThat(build.task(":latheCaptureTest").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    final Path mainMirror =
        dir.resolve(".lathe").resolve(LatheLayout.generatedSourcesDir(LatheLayout.CLASSES_DIR));
    assertThat(mainMirror.resolve("MainGen.java")).exists();
    assertThat(mainMirror.resolve("TestGen.java")).doesNotExist();
    final String shared = fixture.path("build/generated/sources/annotationProcessor/java/main");
    assertThat(params("", LatheLayout.CLASSES_DIR).generatedSourcesDir()).isEqualTo(shared);
    assertThat(params("", LatheLayout.TEST_CLASSES_DIR).generatedSourcesDir()).isEqualTo(shared);
  }

  // A task that runs after the classes and writes into the processed resources, as Apache
  // Fineract's OpenAPI spec does: the classes depend on the capture, so the task runs after it and
  // the next build mirrors its file; a resource processResources expands is captured when the
  // property it expands changes, and an unchanged build leaves the capture up to date.
  @Test
  void capture_taskWritingIntoProcessedResourcesAfterClasses_runsAfterTheCaptureAndIsMirrored()
      throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle", SETTINGS)
            .file(
                "build.gradle",
                GradleFixture.JAVA
                    + """
                    def spec = tasks.register('spec') {
                      dependsOn 'classes'
                      def out = layout.buildDirectory.dir('resources/main/static')
                      outputs.dir(out)
                      doLast { out.get().file('api.json').asFile.text = '{}' }
                    }
                    tasks.named('jar') { dependsOn spec }
                    tasks.named('processResources') {
                      def v = providers.gradleProperty('v')
                      inputs.property('v', v)
                      expand(v: v.get())
                    }
                    """)
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n")
            .file("src/main/resources/app.properties", "v=${v}\n");

    final var first = fixture.runner("jar", "-Pv=1").build();
    final var second = fixture.runner("jar", "-Pv=1").build();
    final var unchanged = fixture.runner("jar", "-Pv=1").build();
    final var expanded = fixture.runner("jar", "-Pv=2").build();

    final List<String> order = first.getTasks().stream().map(BuildTask::getPath).toList();
    assertThat(order.indexOf(":latheCaptureMain")).isLessThan(order.indexOf(":spec"));
    assertThat(second.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(dir.resolve(".lathe/classes/static/api.json")).exists();
    assertThat(unchanged.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    assertThat(expanded.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(dir.resolve(".lathe/classes/app.properties")).content().isEqualTo("v=2\n");
  }

  // A processor built in the same build: its output is mirrored for the editor, and a library
  // sibling reaches the app's classpath as its classes directory.
  @Test
  void capture_multiProjectWithProcessor_mirrorsGeneratedSources() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle", SETTINGS + "include(\"lib\", \"processor\", \"app\")\n")
            .file(
                "lib/build.gradle",
                "plugins { id 'java-library' }\ncompileJava.options.release = 17\n")
            .file(
                "lib/src/main/java/com/example/lib/Lib.java",
                "package com.example.lib; public class Lib {}\n")
            .file("processor/build.gradle", GradleFixture.JAVA)
            .file(
                "processor/src/main/java/com/example/processor/GreeterProcessor.java",
                """
                package com.example.processor;

                import java.io.IOException;
                import java.io.UncheckedIOException;
                import java.io.Writer;
                import java.util.Set;
                import javax.annotation.processing.AbstractProcessor;
                import javax.annotation.processing.RoundEnvironment;
                import javax.annotation.processing.SupportedAnnotationTypes;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;

                @SupportedAnnotationTypes("*")
                public class GreeterProcessor extends AbstractProcessor {
                  private boolean done;

                  @Override
                  public SourceVersion getSupportedSourceVersion() {
                    return SourceVersion.latestSupported();
                  }

                  @Override
                  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (done) {
                      return false;
                    }
                    done = true;
                    try (Writer out = processingEnv.getFiler().createSourceFile("com.example.app.Greeter").openWriter()) {
                      out.write("package com.example.app; public class Greeter {}\\n");
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                    return false;
                  }
                }
                """)
            .file(
                "processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor",
                "com.example.processor.GreeterProcessor\n")
            .file(
                "app/build.gradle",
                """
                plugins { id 'java' }
                compileJava.options.release = 17
                dependencies {
                  implementation project(':lib')
                  annotationProcessor project(':processor')
                }
                """)
            .file(
                "app/src/main/java/com/example/app/App.java",
                "package com.example.app; class App { Greeter greeter; com.example.lib.Lib lib; }\n");

    fixture.runner(":app:classes").build();

    assertThat(dir.resolve(".lathe/app/generated-sources/com/example/app/Greeter.java")).exists();
    assertThat(dir.resolve(".lathe/lib/classes/com/example/lib/Lib.class")).exists();
    final var app = params("app", LatheLayout.CLASSES_DIR);
    assertThat(app.release()).isEqualTo("17");
    assertThat(app.processorPath()).isNotEmpty();
    assertThat(app.proc()).isNull();
    assertThat(app.compilerArgs()).doesNotContain("-proc:none");
    assertThat(app.generatedSourcesDir())
        .isEqualTo(fixture.path("app/build/generated/sources/annotationProcessor/java/main"));
    assertThat(app.classpath()).contains(fixture.path("lib/build/classes/java/main"));
  }

  // Gradle's rule, for the compile and the main run: a module goes on the module path, required or
  // not, a plain jar on the classpath; the run leads with the app's own classes and names its
  // module. A modular project compiles against its siblings' jars, and its tests run as Gradle
  // runs them.
  @Test
  void capture_modularMain_splitsModulesFromPlainJarsForCompileAndRun() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file(
                "settings.gradle",
                SETTINGS
                    + "include(\"lib\", \"plain\", \"app\")\n"
                    + GradleFixture.latheRepositories())
            .file("lib/build.gradle", "plugins { id 'java-library' }\n")
            .file(
                "lib/src/main/java/module-info.java",
                "module com.example.lib { exports com.example.lib; }\n")
            .file(
                "lib/src/main/java/com/example/lib/Lib.java",
                "package com.example.lib; public class Lib {}\n")
            .file("plain/build.gradle", GradleFixture.JAVA)
            .file(
                "plain/src/main/java/com/example/plain/Plain.java",
                "package com.example.plain; public class Plain {}\n")
            .file(
                "app/build.gradle",
                """
                plugins { id 'java' }
                dependencies {
                  implementation project(':lib')
                  implementation project(':plain')
                  testImplementation platform('org.junit:junit-bom:5.11.4')
                  testImplementation 'org.junit.jupiter:junit-jupiter'
                  testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                test { useJUnitPlatform() }
                """)
            .file(
                "app/src/test/java/com/example/app/AppTest.java",
                "package com.example.app; class AppTest { @org.junit.jupiter.api.Test void runs() {} }\n")
            .file(
                "app/src/main/java/module-info.java",
                "module com.example.app { requires com.example.lib; }\n")
            .file(
                "app/src/main/java/com/example/app/App.java",
                "package com.example.app; class App { com.example.lib.Lib lib; }\n");

    fixture.runner(":app:classes", ":app:latheMainLaunch", ":app:test").build();

    final var app = params("app", LatheLayout.CLASSES_DIR);
    assertThat(app.modulepath()).containsExactly(fixture.path("lib/build/libs/lib.jar"));
    assertThat(app.classpath()).containsExactly(fixture.path("plain/build/libs/plain.jar"));
    final MainLaunchData launch =
        Json.read(
            dir.resolve(".lathe/app").resolve(LatheLayout.MAIN_LAUNCH_FILE), MainLaunchData.class);
    assertThat(launch.mode()).isEqualTo(LaunchMode.MODULE);
    assertThat(launch.mainModule()).isEqualTo("com.example.app");
    assertThat(launch.modulePath())
        .containsExactly(
            fixture.path("app/build/classes/java/main"), fixture.path("lib/build/libs/lib.jar"));
    assertThat(launch.classPath()).contains(fixture.path("plain/build/libs/plain.jar"));
    // Its whitebox tests (no module-info of their own) run on the classpath, as Gradle runs them,
    // with no --patch-module: a replay like any other.
    final TestLaunchData tests =
        Json.read(
            dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE), TestLaunchData.class);
    assertThat(tests.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(tests.patchModules()).isEmpty();
    assertThat(tests.classPath())
        .contains(
            fixture.path("app/build/classes/java/test"),
            fixture.path("app/build/classes/java/main"),
            fixture.path("lib/build/libs/lib.jar"));
  }

  private ModuleConfigData params(final String module, final String sourceTree) throws IOException {
    return Json.read(
        dir.resolve(".lathe").resolve(module).resolve(LatheLayout.paramsFileName(sourceTree)),
        ModuleConfigData.class);
  }
}
