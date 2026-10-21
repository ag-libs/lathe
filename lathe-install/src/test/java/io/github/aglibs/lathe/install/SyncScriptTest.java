package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the rendered script against a stub tool that echoes how it was started, so the tests check
// behavior (tool, JDK, cwd, arguments), not the script's text. The stub and the JDK live under
// paths with a space and a quote, which the script must pass through intact.
class SyncScriptTest {

  private static final List<String> FIXED = List.of("--quiet", "-Dcache=false");

  @TempDir Path tmp;
  private Path root;
  private Path tool;
  private Path jdk;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createDirectories(tmp.resolve("workspace"));
    tool = Files.createDirectories(tmp.resolve("o'neil tools")).resolve("build");
    jdk = Files.createDirectories(tmp.resolve("my jdk"));
    Files.writeString(
        tool, "#!/bin/sh\necho \"build cwd=$(pwd -P) JAVA_HOME=$JAVA_HOME args=$*\"\n");
    assertThat(tool.toFile().setExecutable(true)).isTrue();
  }

  @Test
  void posix_scopedFrontEnd_runsTheBuildsToolAndJdk_withTestsGoalsAndModuleArgs() throws Exception {
    final var maven =
        script(
            List.of("sync"),
            List.of("test", "-Dcapture=true"),
            List.of("-pl", LatheFlags.SYNC_MODULE_TOKEN),
            List.of("-am"));

    final Result full = run(maven);
    assertThat(full.stdout())
        .isEqualTo(
            "build cwd=%s JAVA_HOME=%s args=--quiet -Dcache=false sync"
                .formatted(root.toRealPath(), jdk));
    assertThat(full.stderr())
        .isEqualTo("lathe-sync: %s --quiet -Dcache=false sync".formatted(tool));
    assertThat(run(maven, "--tests").stdout())
        .endsWith("args=--quiet -Dcache=false test -Dcapture=true");
    assertThat(run(maven, "app", "core").stdout())
        .endsWith("args=--quiet -Dcache=false -pl app -pl core -am sync");
    assertThat(run(maven, "--tests", "app").stdout())
        .endsWith("args=--quiet -Dcache=false -pl app -am test -Dcapture=true");

    // A front-end that resolves upstream itself (Gradle) has nothing after the modules.
    final var gradle =
        script(
            List.of("latheSync"),
            List.of("latheSync", "--tests"),
            List.of("--module", LatheFlags.SYNC_MODULE_TOKEN),
            List.of());
    assertThat(run(gradle, "app", "core").stdout())
        .endsWith("args=--quiet -Dcache=false --module app --module core latheSync");
  }

  @Test
  void posix_frontEndIgnoringArguments_warnsAndRunsTheWholeSync() throws Exception {
    final var openJdk = script(List.of("plugin:sync"), List.of(), List.of(), List.of());

    final Result result = run(openJdk, "--tests", "java.base");

    assertThat(result.stdout()).endsWith("args=--quiet -Dcache=false plugin:sync");
    assertThat(result.stderr()).startsWith("lathe-sync: arguments ignored;");
  }

  @Test
  void posix_toolGone_failsWithTheRegenerateHint() throws Exception {
    final var script = script(List.of("sync"), List.of(), List.of(), List.of());
    Files.delete(tool);

    final Process process = start(script);

    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isEqualTo(1);
    assertThat(read(process.getErrorStream().readAllBytes()))
        .contains("%s not found; run a Lathe-enabled build".formatted(tool));
  }

  // Runs only on Windows (B1b's CI row); here the text carries what B0 proved cmd.exe needs.
  @Test
  void cmd_crlfScriptRunsTheBuildsTool_withTestsAndModuleArgs_quotingAndDoublingPercent() {
    final var maven =
        new SyncScript(
            tool,
            jdk,
            List.of("--quiet", "-Dlog=100%"),
            List.of("sync"),
            List.of("test", "-Dcapture=true"),
            List.of("-pl", LatheFlags.SYNC_MODULE_TOKEN),
            List.of("-am"));
    final String script = maven.cmd();
    final List<String> lines = List.of(script.split("\r\n"));

    assertThat(script.replace("\r\n", "")).doesNotContain("\n");
    assertThat(lines)
        .startsWith("@echo off")
        .contains(
            "chcp 65001 >nul",
            "set \"tool=%s\"".formatted(tool),
            "set \"goal=sync\"",
            "if not \"%~1\"==\"--tests\" goto modules",
            "set \"goal=test -Dcapture=true\"",
            "set \"scope=%scope% -pl %~1\"",
            "if defined scope set \"scope=%scope% -am\"",
            "set \"JAVA_HOME=%s\"".formatted(jdk),
            "call \"%tool%\" \"--quiet\" \"-Dlog=100%%\" %scope% %goal%",
            "exit /b %ERRORLEVEL%");
    // A ')' in an expanded path (Program Files (x86)) would end a parenthesized block early.
    assertThat(lines).noneMatch(line -> line.contains("(") && line.contains("%"));

    final String openJdk = script(List.of("plugin:sync"), List.of(), List.of(), List.of()).cmd();
    assertThat(openJdk)
        .contains("lathe-sync: arguments ignored;")
        .contains("call \"%tool%\" \"--quiet\" \"-Dcache=false\" plugin:sync");
  }

  @Test
  void constructor_invalidComponents_throw() {
    final List<String> goals = List.of("sync");
    final List<String> module = List.of("-pl", LatheFlags.SYNC_MODULE_TOKEN);
    assertThatThrownBy(
            () ->
                new SyncScript(Path.of("mvn"), jdk, FIXED, goals, List.of(), List.of(), List.of()))
        .as("relative tool")
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("tool must be an absolute path");
    assertThatThrownBy(() -> new SyncScript(tool, jdk, FIXED, goals, List.of(), module, List.of()))
        .as("modules without --tests goals")
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> new SyncScript(tool, jdk, FIXED, goals, goals, List.of("-pl"), List.of()))
        .as("module args without the module token")
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("moduleArgs must contain {module}");
    assertThatThrownBy(
            () ->
                new SyncScript(
                    tool, jdk, FIXED, List.of("two words"), List.of(), List.of(), List.of()))
        .as("whitespace in an unquoted arg")
        .isInstanceOf(ValidationException.class);
  }

  private SyncScript script(
      final List<String> goals,
      final List<String> testsGoals,
      final List<String> moduleArgs,
      final List<String> afterModules) {
    return new SyncScript(tool, jdk, FIXED, goals, testsGoals, moduleArgs, afterModules);
  }

  private record Result(String stdout, String stderr) {
    private Result {
      stdout = stdout.strip();
      stderr = stderr.strip();
    }
  }

  private Result run(final SyncScript script, final String... args) throws Exception {
    final Process process = start(script, args);
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
    return new Result(
        read(process.getInputStream().readAllBytes()),
        read(process.getErrorStream().readAllBytes()));
  }

  // Writes the script where the build would and starts it from an unrelated cwd with a bare
  // environment, so nothing but the script's own literals can pick the tool or the JDK.
  private Process start(final SyncScript script, final String... args) throws Exception {
    final Path latheDir = Files.createDirectories(root.resolve(LatheLayout.LATHE_DIR));
    final Path file = latheDir.resolve(LatheLayout.SYNC_SCRIPT);
    Files.writeString(file, script.posix());
    assertThat(file.toFile().setExecutable(true)).isTrue();
    final var command = new ArrayList<String>();
    command.add(file.toString());
    command.addAll(List.of(args));
    final var builder = new ProcessBuilder(command).directory(tmp.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    return builder.start();
  }

  private static String read(final byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
