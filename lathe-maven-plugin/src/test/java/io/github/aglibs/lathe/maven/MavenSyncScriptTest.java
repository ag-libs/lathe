package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Platform;
import io.github.aglibs.lathe.install.SyncScript;
import java.nio.file.Path;
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
    assertThat(plain.goals()).containsExactly(LatheLayout.Maven.SYNC_PHASE);
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

  private static SyncScript script(final String mavenHome, final String mvndHome) {
    final var properties = new Properties();
    properties.setProperty("maven.home", absolute(mavenHome).toString());
    properties.setProperty("java.home", absolute("/opt/jdk").toString());
    if (mvndHome != null) {
      properties.setProperty("mvnd.home", absolute(mvndHome).toString());
    }

    return MavenSyncScript.of(properties);
  }

  // On Windows a path from '/' has no drive, so it is not absolute until it gains the current one.
  private static Path absolute(final String posixPath) {
    return Path.of(posixPath).toAbsolutePath();
  }
}
