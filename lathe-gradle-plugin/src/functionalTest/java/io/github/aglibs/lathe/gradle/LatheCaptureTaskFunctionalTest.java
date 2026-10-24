package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.IOException;
import java.nio.file.Path;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheCaptureTaskFunctionalTest {

  private static final String SETTINGS = GradleFixture.latheSettings("app");

  @TempDir Path dir;

  // One project, captured, wiped, and captured again with the compile up to date; the
  // configuration cache is stored and then reused.
  @Test
  void capture_javaProject_writesParamsMirrorsAndStampsAndRefillsAWipedLathe() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS)
            .file("build.gradle.kts", GradleFixture.JAVA)
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n")
            .file(
                "src/test/java/com/example/AppTest.java",
                "package com.example; class AppTest {}\n");

    final var first = fixture.runner("testClasses", "--configuration-cache").build();
    FileUtil.deleteDir(dir.resolve(".lathe"));
    final var second = fixture.runner("testClasses", "--configuration-cache").build();

    assertThat(first.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(second.getOutput()).contains("Configuration cache entry reused.");
    assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    assertThat(second.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(dir.resolve(".lathe/classes/com/example/App.class")).exists();
    assertThat(dir.resolve(".lathe/test-classes/com/example/AppTest.class")).exists();
    assertThat(dir.resolve(".lathe").resolve(LatheLayout.compiledStampsFileName("classes")))
        .exists();
    final var main = params("", LatheLayout.CLASSES_DIR);
    assertThat(main.sourceRoots()).containsExactly(path("src/main/java"));
    assertThat(main.outputDir()).isEqualTo(path("build/classes/java/main"));
    assertThat(main.release()).containsOnlyDigits();
    assertThat(main.proc()).isEqualTo("none");
    assertThat(main.compilerArgs()).containsExactly("-proc:none");
    assertThat(params("", LatheLayout.TEST_CLASSES_DIR).classpath())
        .contains(path("build/classes/java/main"));
  }

  // A processor built in the same build: its output is mirrored for the editor, and a library
  // sibling reaches the app's classpath as its classes directory.
  @Test
  void capture_multiProjectWithProcessor_mirrorsGeneratedSources() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS + "include(\"lib\", \"processor\", \"app\")\n")
            .file(
                "lib/build.gradle.kts",
                "plugins { `java-library` }\ntasks.compileJava { options.release = 17 }\n")
            .file(
                "lib/src/main/java/com/example/lib/Lib.java",
                "package com.example.lib; public class Lib {}\n")
            .file("processor/build.gradle.kts", GradleFixture.JAVA)
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
                "app/build.gradle.kts",
                """
                plugins { java }
                java { sourceCompatibility = JavaVersion.VERSION_17 }
                dependencies {
                  implementation(project(":lib"))
                  annotationProcessor(project(":processor"))
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
        .isEqualTo(path("app/build/generated/sources/annotationProcessor/java/main"));
    assertThat(app.classpath()).contains(path("lib/build/classes/java/main"));
  }

  @Test
  void capture_failedCompileAfterAGoodOne_keepsTheLastGoodCapture() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS)
            .file("build.gradle.kts", GradleFixture.JAVA)
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n");

    fixture.runner("classes").build();
    fixture.file("src/main/java/com/example/App.java", "package com.example; class App {\n");
    final var failed = fixture.runner("classes", "--continue").buildAndFail();

    assertThat(failed.task(":latheCaptureMain")).isNull();
    assertThat(dir.resolve(".lathe/classes/com/example/App.class")).exists();
  }

  // The cache-key rule: Lathe never changes the build's own tasks, so a cache filled by a build
  // without Lathe serves the compile to a build with it.
  @Test
  void capture_buildCacheFilledWithoutLathe_compileComesFromTheCache() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file(
                "settings.gradle.kts",
                SETTINGS + "buildCache { local { directory = file(\"build-cache\") } }\n")
            .file("build.gradle.kts", GradleFixture.JAVA)
            .file("src/main/java/com/example/App.java", "package com.example; class App {}\n");

    fixture.runner("classes", "--build-cache", "-Plathe.disabled=true").build();
    FileUtil.deleteDir(dir.resolve("build"));
    final var withLathe = fixture.runner("classes", "--build-cache").build();

    assertThat(withLathe.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
    assertThat(withLathe.task(":latheCaptureMain").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
  }

  // Gradle's rule: a module on the compile classpath goes on the module path, required or not; a
  // plain jar stays on the classpath. A modular project compiles against its siblings' jars.
  @Test
  void capture_modularMain_splitsModulesFromPlainJars() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS + "include(\"lib\", \"plain\", \"app\")\n")
            .file("lib/build.gradle.kts", "plugins { `java-library` }\n")
            .file(
                "lib/src/main/java/module-info.java",
                "module com.example.lib { exports com.example.lib; }\n")
            .file(
                "lib/src/main/java/com/example/lib/Lib.java",
                "package com.example.lib; public class Lib {}\n")
            .file("plain/build.gradle.kts", GradleFixture.JAVA)
            .file(
                "plain/src/main/java/com/example/plain/Plain.java",
                "package com.example.plain; public class Plain {}\n")
            .file(
                "app/build.gradle.kts",
                """
                plugins { java }
                dependencies {
                  implementation(project(":lib"))
                  implementation(project(":plain"))
                }
                """)
            .file(
                "app/src/main/java/module-info.java",
                "module com.example.app { requires com.example.lib; }\n")
            .file(
                "app/src/main/java/com/example/app/App.java",
                "package com.example.app; class App { com.example.lib.Lib lib; }\n");

    fixture.runner(":app:classes").build();

    final var app = params("app", LatheLayout.CLASSES_DIR);
    assertThat(app.modulepath()).containsExactly(path("lib/build/libs/lib.jar"));
    assertThat(app.classpath()).containsExactly(path("plain/build/libs/plain.jar"));
  }

  // As Gradle reports it: the real path, which differs from the temp directory's on macOS, where
  // /var is a link to /private/var.
  private String path(final String relative) throws IOException {
    return dir.toRealPath().resolve(relative).toString();
  }

  private ModuleConfigData params(final String module, final String sourceTree) throws IOException {
    return Json.read(
        dir.resolve(".lathe").resolve(module).resolve(LatheLayout.paramsFileName(sourceTree)),
        ModuleConfigData.class);
  }
}
