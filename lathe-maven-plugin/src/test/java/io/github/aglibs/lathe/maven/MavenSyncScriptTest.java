package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.Platform;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.SyncScript;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

// What the Maven front-end puts into the sync script; SyncScriptTest runs the rendered script.
class MavenSyncScriptTest {

  private static final String SUFFIX = Platform.WINDOWS ? ".cmd" : "";

  @Test
  void script_plainWrapperAndMvndBuilds_runTheirOwnMavenOnTheBuildJdk() {
    final var plain = script("/opt/maven", null);
    assertThat(plain.tool()).isEqualTo(absolute("/opt/maven/bin/mvn" + SUFFIX));
    assertThat(plain.javaHome()).isEqualTo(absolute("/opt/jdk"));
    assertThat(plain.fixedArgs())
        .containsExactly("--no-transfer-progress", "-Dmaven.build.cache.enabled=false");
    assertThat(plain.goals()).containsExactly(MavenSyncScript.SYNC_PHASE);
    assertThat(plain.testsGoals())
        .containsExactly("test", "-Dlathe.capture.only=true", "-DfailIfNoTests=false");
    assertThat(plain.moduleArgs()).containsExactly("-pl", "{module}");
    assertThat(plain.afterModules()).containsExactly("-am");

    // The wrapper's maven.home is its distribution; mvnd's maven.home is the Maven it embeds.
    assertThat(script("/home/me/.m2/wrapper/dists/apache-maven-3.9.16/abc", null).tool())
        .isEqualTo(absolute("/home/me/.m2/wrapper/dists/apache-maven-3.9.16/abc/bin/mvn" + SUFFIX));
    assertThat(script("/opt/mvnd/mvn", "/opt/mvnd").tool())
        .isEqualTo(absolute("/opt/mvnd/bin/mvnd" + SUFFIX));
  }

  // A -D on the build (maven.config included) wins over the root POM's property, which wins over
  // the default; a phase that cannot refresh .lathe/, or that would publish, stops the build.
  @Test
  void phase_setOnTheBuildOrInThePom_followsItAndRejectsPhasesOutsideTheRange() {
    final var pom = new Properties();
    pom.setProperty(LatheFlags.SYNC_PHASE, "package");
    final var build = new Properties();
    build.setProperty(LatheFlags.SYNC_PHASE, "install");

    assertThat(MavenSyncScript.phase(new Properties(), new Properties()))
        .isEqualTo(MavenSyncScript.SYNC_PHASE);
    assertThat(MavenSyncScript.phase(new Properties(), pom)).isEqualTo("package");
    assertThat(MavenSyncScript.phase(build, pom)).isEqualTo("install");
    final var padded = new Properties();
    padded.setProperty(LatheFlags.SYNC_PHASE, " package ");
    assertThat(MavenSyncScript.phase(new Properties(), padded)).isEqualTo("package");
    for (final String phase : List.of("compile", "deploy", "pakage")) {
      final var invalid = new Properties();
      invalid.setProperty(LatheFlags.SYNC_PHASE, phase);
      assertThatThrownBy(() -> MavenSyncScript.phase(invalid, new Properties()))
          .isInstanceOf(SyncException.class)
          .hasMessageContaining("lathe.sync.phase=%s".formatted(phase));
    }
  }

  // From the test phase on, a plain sync skips the tests; past it, a --tests sync captures them on
  // the way to the project's phase, skipping Failsafe's integration tests, which it cannot capture.
  @Test
  void script_laterPhase_skipsTestsAndCapturesOnTheWayToIt() {
    final var properties = new Properties();
    properties.setProperty("maven.home", absolute("/opt/maven").toString());
    properties.setProperty("java.home", absolute("/opt/jdk").toString());

    final SyncScript packaged = MavenSyncScript.of(properties, "package");
    final SyncScript tested = MavenSyncScript.of(properties, "test");

    assertThat(packaged.goals()).containsExactly("package", "-DskipTests");
    assertThat(packaged.testsGoals())
        .containsExactly(
            "package", "-Dlathe.capture.only=true", "-DfailIfNoTests=false", "-DskipITs");
    assertThat(tested.goals()).containsExactly("test", "-DskipTests");
    assertThat(tested.testsGoals())
        .containsExactly("test", "-Dlathe.capture.only=true", "-DfailIfNoTests=false");
  }

  private static SyncScript script(final String mavenHome, final String mvndHome) {
    final var properties = new Properties();
    properties.setProperty("maven.home", absolute(mavenHome).toString());
    properties.setProperty("java.home", absolute("/opt/jdk").toString());
    if (mvndHome != null) {
      properties.setProperty("mvnd.home", absolute(mvndHome).toString());
    }

    return MavenSyncScript.of(properties, MavenSyncScript.SYNC_PHASE);
  }

  // On Windows a path from '/' has no drive, so it is not absolute until it gains the current one.
  private static Path absolute(final String posixPath) {
    return Path.of(posixPath).toAbsolutePath();
  }
}
