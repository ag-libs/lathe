package io.github.aglibs.lathe.gradle;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.aglibs.lathe.itsupport.LspSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The language server on a workspace a Gradle sync wrote, as an editor uses it: the TestKit tests
// check what the sync writes, this that the server reads it. One build and one session, so the
// checks share their cost.
class LatheLspSmokeFunctionalTest {

  private static final String SETTINGS =
      "%sinclude(\"lib\", \"app\")\n%s"
          .formatted(GradleFixture.latheSettings("root"), GradleFixture.latheRepositories());

  private static final String APP_BUILD =
      """
      plugins { id 'java' }
      sourceSets {
        integrationTest {
          compileClasspath += sourceSets.main.output
          runtimeClasspath += sourceSets.main.output
        }
      }
      dependencies {
        implementation project(':lib')
        testImplementation platform('org.junit:junit-bom:5.11.4')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
      }
      test { useJUnitPlatform() }
      """;

  private static final String APP =
      """
      package com.example.app;

      public class App {
        String greeting() {
          return com.example.lib.Lib.greet("app");
        }
      }
      """;

  @TempDir Path dir;

  // The server is ready on the Gradle-written .lathe/; a broken file is diagnosed; definition goes
  // from app into lib; the integrationTest source set compiles against its own classpath (main's
  // classes, not test's); and a test the capture-only sync captured replays through the server,
  // each method's result landing on it.
  @Test
  void server_gradleSyncedWorkspace_diagnosesNavigatesAndReplaysCapturedTests() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle", SETTINGS)
            .file("build.gradle", "")
            .file("lib/build.gradle", "plugins { id 'java-library' }\n")
            .file(
                "lib/src/main/java/com/example/lib/Lib.java",
                """
                package com.example.lib;

                public class Lib {
                  public static String greet(final String name) {
                    return "Hello, " + name + "!";
                  }
                }
                """)
            .file("app/build.gradle", APP_BUILD)
            .file("app/src/main/java/com/example/app/App.java", APP)
            .file(
                "app/src/test/java/com/example/app/AppTest.java",
                """
                package com.example.app;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                class AppTest {
                  @org.junit.jupiter.api.Test
                  void passes() {
                    assertEquals("Hello, app!", new App().greeting());
                  }

                  @org.junit.jupiter.api.Test
                  void fails() {
                    assertEquals("Hello, world!", new App().greeting());
                  }
                }
                """)
            .file(
                "app/src/integrationTest/java/com/example/app/AppIT.java",
                "package com.example.app; class AppIT { App app; }\n");
    fixture.runner("latheSync", "-Plathe.capture.only=true").build();
    final Path root = dir.toRealPath();
    final Path app = root.resolve("app/src/main/java/com/example/app/App.java");
    final Path appIt = root.resolve("app/src/integrationTest/java/com/example/app/AppIT.java");
    final Path tests = root.resolve("app/src/test/java/com/example/app");

    final LspSession session = LspSession.start(root, new ClientCapabilities());
    try {
      final String appUri = app.toUri().toString();
      session.openDoc(appUri, APP);
      assertThat(session.nextDiagnostics(appUri)).as("a clean file").isEmpty();
      final var definition =
          session
              .server
              .getTextDocumentService()
              .definition(
                  new DefinitionParams(
                      new TextDocumentIdentifier(appUri),
                      LspSession.findToken(APP, "Lib.greet", "greet")))
              .get(60, SECONDS);
      assertThat(definition.getLeft())
          .extracting(Location::getUri)
          .singleElement()
          .asString()
          .as("into the lib project")
          .endsWith("lib/src/main/java/com/example/lib/Lib.java");

      session.saveDoc(appUri, APP.replace("Lib.greet", "Lib.greeet"));
      assertThat(session.nextDiagnostics(appUri))
          .extracting(LatheLspSmokeFunctionalTest::message)
          .anyMatch(message -> message.contains("greeet"));
      session.saveDoc(appUri, APP);

      final String itUri = appIt.toUri().toString();
      session.openDoc(
          itUri, "package com.example.app; class AppIT { App app; AppTest notVisible; }\n");
      assertThat(session.nextDiagnostics(itUri))
          .as("main's classes are on integrationTest's classpath, test's are not")
          .extracting(LatheLspSmokeFunctionalTest::message)
          .singleElement()
          .asString()
          .contains("AppTest");

      final JsonObject run =
          session
              .execute("lathe.runnables.dir", Map.of("uri", tests.toUri().toString()))
              .getAsJsonObject();
      final JsonObject outcome =
          session
              .execute(
                  "lathe.run.test",
                  Map.of(
                      "moduleRel", run.get("moduleRel"),
                      "selections", run.get("selections"),
                      "token", "gradle-smoke"))
              .getAsJsonObject();

      assertThat(outcome.get("launched").getAsBoolean()).isTrue();
      assertThat(results(outcome))
          .extracting(
              result -> result.get("positionId").getAsString(),
              result -> result.get("status").getAsString())
          .contains(
              tuple("com.example.app.AppTest#passes()", "passed"),
              tuple("com.example.app.AppTest#fails()", "failed"));
    } finally {
      session.stop();
    }
  }

  private static String message(final Diagnostic diagnostic) {
    return diagnostic.getMessage().isLeft()
        ? diagnostic.getMessage().getLeft()
        : diagnostic.getMessage().getRight().getValue();
  }

  private static List<JsonObject> results(final JsonObject outcome) {
    return StreamSupport.stream(outcome.getAsJsonArray("testResults").spliterator(), false)
        .map(JsonElement::getAsJsonObject)
        .toList();
  }
}
