package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the generated script against stub build tools that echo what they were invoked with, so the
// tests check behavior (goal, scope, cache flag, tool choice, JDK, cwd), not the script's text.
class SyncScriptWriterTest {

  private static final String CACHE_OFF = "-Dmaven.build.cache.enabled=false";
  // --tests captures test launches without running the tests.
  private static final String CAPTURE = "test -Dlathe.capture.only=true -DfailIfNoTests=false";

  @TempDir Path tmp;
  private Path root;
  private Path stubs;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createDirectories(tmp.resolve("workspace"));
    stubs = Files.createDirectories(tmp.resolve("stubs"));
    stubTool(stubs.resolve("mvn"), "mvn");
    new SyncScriptWriter(new SystemStreamLog()).write(root);
  }

  @Test
  void write_scriptRunsGoalAndScope_withCacheOff_printingTheCommandFirst() throws Exception {
    final var full = run(Map.of());
    assertThat(full.stdout())
        .isEqualTo(
            "mvn cwd=%s JAVA_HOME= args=--no-transfer-progress %s process-test-classes"
                .formatted(root.toRealPath(), CACHE_OFF));
    assertThat(full.stderr())
        .isEqualTo(
            "lathe-sync: mvn --no-transfer-progress %s process-test-classes".formatted(CACHE_OFF));

    assertThat(run(Map.of(), "--tests").stdout()).endsWith("%s %s".formatted(CACHE_OFF, CAPTURE));
    assertThat(run(Map.of(), "app", "core").stdout())
        .endsWith("%s -pl app,core -am -amd process-test-classes".formatted(CACHE_OFF));
    assertThat(run(Map.of(), "--tests", "app").stdout())
        .endsWith("%s -pl app -am -amd %s".formatted(CACHE_OFF, CAPTURE));
  }

  @Test
  void write_scriptPrefersMvndThenWrapper_andRunsOnTheRecordedJdk() throws Exception {
    stubTool(root.resolve("mvnw"), "mvnw");
    assertThat(run(Map.of()).stdout()).startsWith("mvnw ");
    stubTool(stubs.resolve("mvnd"), "mvnd");
    assertThat(run(Map.of()).stdout()).startsWith("mvnd ");

    final Path recorded = fakeJdk("recorded");
    Files.writeString(
        root.resolve(LatheLayout.LATHE_DIR).resolve("java-home"), recorded.toString());
    assertThat(run(Map.of()).stdout()).contains("JAVA_HOME=%s ".formatted(recorded));
    final Path override = fakeJdk("override");
    assertThat(run(Map.of("LATHE_JAVA_HOME", override.toString())).stdout())
        .contains("JAVA_HOME=%s ".formatted(override));

    // A recorded home without bin/java is not exported; the build keeps the inherited JAVA_HOME.
    Files.writeString(
        root.resolve(LatheLayout.LATHE_DIR).resolve("java-home"), tmp.resolve("gone").toString());
    assertThat(run(Map.of()).stdout()).contains("JAVA_HOME= ");
  }

  @Test
  void write_unchangedScript_isNotRewritten() throws Exception {
    final Path script = root.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.SYNC_SCRIPT);
    final var stamp = FileTime.fromMillis(1_000_000L);
    Files.setLastModifiedTime(script, stamp);

    new SyncScriptWriter(new SystemStreamLog()).write(root);

    assertThat(Files.getLastModifiedTime(script)).isEqualTo(stamp);
    assertThat(script).isExecutable();
  }

  private record Result(String stdout, String stderr) {
    private Result {
      stdout = stdout.strip();
      stderr = stderr.strip();
    }
  }

  // Runs the script from an unrelated cwd with only the stubs and the base system tools on PATH, so
  // a real mvn/mvnd on the machine can never be picked up.
  private Result run(final Map<String, String> env, final String... args) throws Exception {
    final var command = new ArrayList<String>();
    command.add(root.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.SYNC_SCRIPT).toString());
    command.addAll(List.of(args));
    final var builder = new ProcessBuilder(command).directory(tmp.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "%s:/usr/bin:/bin".formatted(stubs));
    builder.environment().putAll(env);
    final Process process = builder.start();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
    return new Result(
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static void stubTool(final Path path, final String name) throws Exception {
    writeExecutable(path, "echo \"%s cwd=$(pwd -P) JAVA_HOME=$JAVA_HOME args=$*\"".formatted(name));
  }

  private Path fakeJdk(final String name) throws Exception {
    writeExecutable(Files.createDirectories(tmp.resolve(name).resolve("bin")).resolve("java"), "");
    return tmp.resolve(name);
  }

  private static void writeExecutable(final Path path, final String body) throws Exception {
    Files.writeString(path, "#!/bin/sh\n%s\n".formatted(body));
    assertThat(path.toFile().setExecutable(true)).isTrue();
  }
}
