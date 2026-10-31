package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.install.jdk.JdkSourceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheWorkspaceTaskTest {

  @TempDir Path tmp;

  // The server runs on the newest compiling JDK it can run on; a build that compiles only on older
  // JDKs (a toolchain on 11) gets the JDK the build itself runs on.
  @Test
  void compilingJdk_toolchainsOlderThanTheServer_leftOut() throws IOException {
    final ProjectFacts on11 = facts("lib", jdk("jdk-11", "11"), 11);
    final Path jdk21 = jdk("jdk-21", "21");

    assertThat(LatheWorkspaceTask.compilingJdk(List.of(on11, facts("app", jdk21, 21))).home())
        .isEqualTo(jdk21.toRealPath());
    assertThat(LatheWorkspaceTask.compilingJdk(List.of(on11)).home())
        .isEqualTo(JdkSourceResolver.resolve().home());
  }

  private Path jdk(final String name, final String version) throws IOException {
    final Path home = Files.createDirectories(tmp.resolve(name));
    Files.writeString(home.resolve("release"), "JAVA_VERSION=\"%s\"%n".formatted(version));
    return home;
  }

  private static ProjectFacts facts(final String module, final Path javaHome, final int version) {
    return new ProjectFacts(
        module,
        List.of(),
        List.of(),
        javaHome.toString(),
        version,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
