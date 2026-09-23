package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunOverlayTest {

  private static final Path WORKSPACE = Path.of("/workspace");

  @TempDir private Path tmp;

  private static MainLaunchData classpathTemplate() {
    return new MainLaunchData(
        "1",
        LaunchMode.CLASSPATH,
        "/jdk",
        "",
        List.of(),
        List.of("/workspace/app/target/classes"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "app");
  }

  @Test
  void applyToMain_overlay_appliesEnvCwdAndArgv() {
    final var item =
        new RunItem(
            null,
            "app",
            RunKind.MAIN,
            null,
            null,
            List.of("--flag"),
            List.of("-Dp=1"),
            Map.of("APP_ENV", "prod"),
            null,
            "run-dir",
            List.of("config"),
            List.of());

    final ResolvedLaunch resolved =
        RunOverlay.applyToMain(
            classpathTemplate(), WORKSPACE, "com.example.app.Main", item, JdwpOptions.NONE);

    assertThat(resolved.env()).containsEntry("APP_ENV", "prod");
    assertThat(resolved.cwd()).isEqualTo(Path.of("/workspace/run-dir"));
    assertThat(resolved.argv()).contains("-Dp=1").endsWith("com.example.app.Main", "--flag");
    assertThat(resolved.argv()).anyMatch(arg -> arg.contains("/workspace/config"));
  }

  @Test
  void applyToMain_absoluteCwdAndAppend_passThroughUnchanged() {
    final var item =
        new RunItem(
            null,
            "app",
            RunKind.MAIN,
            null,
            null,
            null,
            null,
            Map.of(),
            null,
            "/abs/dir",
            List.of("/abs/cp"),
            List.of());

    final ResolvedLaunch resolved =
        RunOverlay.applyToMain(
            classpathTemplate(), WORKSPACE, "com.example.app.Main", item, JdwpOptions.NONE);

    assertThat(resolved.cwd()).isEqualTo(Path.of("/abs/dir"));
    assertThat(resolved.argv()).anyMatch(arg -> arg.contains("/abs/cp"));
  }

  @Test
  void applyToMain_noCwd_defaultsToModuleBasedir() {
    final ResolvedLaunch resolved =
        RunOverlay.applyToMain(
            classpathTemplate(),
            WORKSPACE,
            "com.example.app.Main",
            RunItem.empty("app", RunKind.MAIN),
            JdwpOptions.NONE);

    // No overlay cwd → default to the module basedir from the template's workingDir.
    assertThat(resolved.cwd()).isEqualTo(Path.of("/workspace/app"));
    assertThat(resolved.env()).isEmpty();
  }

  @Test
  void applyToMain_envFile_loadedAsBaseWithInlineEnvOverriding() throws IOException {
    Files.writeString(tmp.resolve("app.env"), "A=fromFile\nB=fromFile\n# a comment\n");
    final var item =
        new RunItem(
            null,
            "app",
            RunKind.MAIN,
            null,
            null,
            null,
            null,
            Map.of("B", "inline"),
            "app.env",
            null,
            null,
            null);

    final ResolvedLaunch resolved =
        RunOverlay.applyToMain(
            classpathTemplate(), tmp, "com.example.app.Main", item, JdwpOptions.NONE);

    assertThat(resolved.env()).containsEntry("A", "fromFile").containsEntry("B", "inline");
  }

  @Test
  void applyToMain_missingEnvFile_isIgnored() {
    final var item =
        new RunItem(
            null,
            "app",
            RunKind.MAIN,
            null,
            null,
            null,
            null,
            Map.of("X", "1"),
            "nope.env",
            null,
            null,
            null);

    final ResolvedLaunch resolved =
        RunOverlay.applyToMain(
            classpathTemplate(), tmp, "com.example.app.Main", item, JdwpOptions.NONE);

    assertThat(resolved.env()).containsEntry("X", "1");
  }
}
