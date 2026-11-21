package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// WS-46: which modules a server on a given JDK can check as their build does.
class JdkSupportTest {

  private static final int SERVER = 21;
  private static final Path MODULE = Path.of("/workspace/.lathe/app");

  @TempDir Path tmp;

  @Test
  void check_releaseNewerThanTheServer_needsThatRelease() {
    final Optional<JdkSupport.Mismatch> mismatch =
        JdkSupport.check(config("25", List.of()), SERVER);

    assertThat(mismatch).isPresent();
    assertThat(mismatch.get().needed()).isEqualTo(25);
    assertThat(mismatch.get().why()).isEqualTo("is built for Java 25");
  }

  @Test
  void check_releaseInlineInTheArgs_needsThatRelease() {
    assertThat(JdkSupport.check(config("", List.of("--release=25")), SERVER))
        .hasValueSatisfying(mismatch -> assertThat(mismatch.needed()).isEqualTo(25));
  }

  // --release takes the API from the server's own JDK, whichever JDK the build ran on.
  @Test
  void check_releaseTheServerKnowsBuiltWithANewerJdk_supported() throws Exception {
    final List<String> args = List.of("--system", jdk("25.0.1").toString());

    assertThat(JdkSupport.check(config("17", args), SERVER)).isEmpty();
  }

  @Test
  void check_noReleaseSourceNewerThanTheServer_needsThatLevel() {
    assertThat(JdkSupport.check(config("", List.of("-source", "25", "-target", "25")), SERVER))
        .hasValueSatisfying(mismatch -> assertThat(mismatch.needed()).isEqualTo(25));
  }

  @Test
  void check_noReleaseLegacyLevel_supported() {
    assertThat(JdkSupport.check(config("", List.of("-source", "1.8", "-target", "1.8")), SERVER))
        .isEmpty();
  }

  // Without --release the API is the build JDK's, which a server on an older JDK cannot read.
  @Test
  void check_noReleaseBuiltWithANewerJdk_needsThatJdk() throws Exception {
    final List<String> args = List.of("-source", "17", "--system", jdk("25.0.1").toString());

    final Optional<JdkSupport.Mismatch> mismatch = JdkSupport.check(config("", args), SERVER);

    assertThat(mismatch).isPresent();
    assertThat(mismatch.get().needed()).isEqualTo(25);
    assertThat(mismatch.get().why()).isEqualTo("was built with JDK 25");
  }

  // A JDK whose version cannot be read is not compared, as before, rather than failed on a guess.
  @Test
  void check_noReleaseBuildJdkVersionUnreadable_supported() throws Exception {
    final Path jdk = Files.createDirectories(tmp.resolve("jdk-unknown"));

    assertThat(JdkSupport.check(config("", List.of("--system", jdk.toString())), SERVER)).isEmpty();
  }

  // The args are the build's text: a --system no path can hold is not compared, and the load goes
  // on.
  @Test
  void check_noReleaseSystemNoPath_supported() {
    assertThat(JdkSupport.check(config("", List.of("--system", "jdk\u0000home")), SERVER))
        .isEmpty();
  }

  @Test
  void check_noReleaseBuildJdkTheServerKnows_supported() throws Exception {
    final List<String> args = List.of("--system", jdk("21.0.8").toString());

    assertThat(JdkSupport.check(config("", args), SERVER)).isEmpty();
  }

  @Test
  void released_releaseOptionOrFlag_true() {
    assertThat(JdkSupport.released(config("17", List.of()))).isTrue();
    assertThat(JdkSupport.released(config("", List.of("--release", "17")))).isTrue();
  }

  @Test
  void released_sourceAndTargetOnly_false() {
    assertThat(JdkSupport.released(config("", List.of("-source", "17", "-target", "17"))))
        .isFalse();
  }

  @Test
  void workspaceMessage_severalModules_namesTheFirstCountsTheRestAndGivesTheFix() {
    final String message = JdkSupport.workspaceMessage(List.of("app", "docs", "tools"), 25);

    assertThat(message)
        .contains("modules app and 2 more need JDK 25")
        .contains("Run Lathe on JDK 25 or newer")
        .contains("LATHE_JAVA_HOME");
  }

  @Test
  void workspaceMessage_oneModule_namesIt() {
    assertThat(JdkSupport.workspaceMessage(List.of("app"), 25)).contains("module app needs JDK 25");
  }

  private Path jdk(final String version) throws Exception {
    final Path jdk = Files.createDirectories(tmp.resolve("jdk-%s".formatted(version)));
    Files.writeString(
        jdk.resolve(LatheLayout.JDK_RELEASE_FILE),
        "%s=\"%s\"\n".formatted(LatheLayout.JDK_RELEASE_VERSION_KEY, version));
    return jdk;
  }

  private static ModuleSourceConfig config(final String release, final List<String> args) {
    return new ModuleSourceConfig(
        MODULE,
        LatheLayout.CLASSES_DIR,
        MODULE.resolve(LatheLayout.CLASSES_DIR),
        null,
        List.of(Path.of("/workspace/app/src/main/java")),
        List.of(),
        List.of(),
        List.of(),
        release,
        "UTF-8",
        false,
        false,
        null,
        args,
        null,
        false,
        null,
        ReactorRewrite.none(),
        null);
  }
}
