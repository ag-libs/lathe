package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServerInstallerTest {

  @Test
  void renderLaunchers_resolveJdkThenExpandJvmOptsBeforeFixedArgs() {
    assertLaunchContract(ServerInstaller.renderLauncherScript("/abs/module-path"));
    assertLaunchContract(ServerInstaller.renderMcpLauncherScript("/abs/classpath.jar"));
  }

  // Both launchers resolve the JDK (LATHE_JAVA_HOME, else .lathe/java-home, else PATH java) and
  // then
  // exec it with LATHE_JVM_OPTS expanded, all before Lathe's fixed arguments.
  private static void assertLaunchContract(final String script) {
    assertThat(script).contains("${LATHE_JAVA_HOME:-}", ".lathe/java-home", "java_bin=java");
    assertThat(script).contains("exec \"$java_bin\" ${LATHE_JVM_OPTS:-} \\");
    assertThat(script.indexOf(".lathe/java-home")).isLessThan(script.indexOf("exec \"$java_bin\""));
    assertThat(script.indexOf("exec \"$java_bin\""))
        .isLessThan(script.indexOf("--add-modules java.net.http"));
  }
}
