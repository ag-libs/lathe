package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerInstallerTest {

  @Test
  void renderLaunchers_resolveJdkThenExpandJvmOptsBeforeFixedArgs() {
    assertLaunchContract(ServerInstaller.renderLauncherScript("/abs/module-path"));
    assertLaunchContract(ServerInstaller.renderMcpLauncherScript("/abs/classpath.jar"));
  }

  // Only the classpath MCP launcher grants javac internals itself; the editor launcher leaves all
  // grants (javac plugins, the pinned formatter) to the workspace's jvm.args.
  @Test
  void renderLaunchers_allUnnamedAccess_onlyInMcpLauncher() {
    assertThat(ServerInstaller.renderLauncherScript("/abs/module-path"))
        .doesNotContain("=ALL-UNNAMED")
        .doesNotContain("com.google.googlejavaformat");
    assertThat(ServerInstaller.renderMcpLauncherScript("/abs/classpath.jar"))
        .contains("jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
  }

  @Test
  void newestVersionDir_releaseBeatsOlderSnapshot_picksHighestVersion(@TempDir final Path servers)
      throws IOException {
    Files.createDirectories(servers.resolve("0.1.9"));
    Files.createDirectories(servers.resolve("0.1.16-SNAPSHOT"));
    Files.createDirectories(servers.resolve("0.1.15"));
    Files.createDirectories(servers.resolve("0.1.16"));

    assertThat(ServerInstaller.newestVersionDir(servers)).isEqualTo(servers.resolve("0.1.16"));
  }

  @Test
  void newestVersionDir_missingServersDir_returnsNull(@TempDir final Path root) {
    assertThat(ServerInstaller.newestVersionDir(root.resolve("absent"))).isNull();
  }

  // Both launchers resolve the JDK (LATHE_JAVA_HOME, else .lathe/java-home, else PATH java) and
  // then exec it with the stdout guard, the workspace's jvm.args @argfile, and LATHE_JVM_OPTS (so
  // user flags win), all before Lathe's fixed arguments.
  private static void assertLaunchContract(final String script) {
    assertThat(script).contains("${LATHE_JAVA_HOME:-}", ".lathe/java-home", "java_bin=java");
    assertThat(script).contains("if [ -r .lathe/jvm.args ]; then", "jvm_args=@.lathe/jvm.args");
    assertThat(script)
        .contains(
            "exec \"$java_bin\" -XX:+DisplayVMOutputToStderr -Xlog:disable"
                + " -Xlog:all=warning:stderr $jvm_args ${LATHE_JVM_OPTS:-} \\");
    assertThat(script.indexOf(".lathe/java-home")).isLessThan(script.indexOf("exec \"$java_bin\""));
    assertThat(script.indexOf("exec \"$java_bin\""))
        .isLessThan(script.indexOf("--add-modules java.net.http"));
  }
}
