package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatheBuildInfoTest {

  @Test
  void summary_anyClass_namesTheRunningJvm() {
    assertThat(LatheBuildInfo.summary(LatheBuildInfoTest.class))
        .startsWith("version=")
        .contains(
            "java=%s".formatted(Runtime.version()),
            "java.home=%s".formatted(System.getProperty("java.home")));
  }

  // Read from the jar's own manifest, so a class in a named module has it too.
  @Test
  void version_classInJarOrDirectory_manifestVersionOrEmpty() {
    assertThat(LatheBuildInfo.version(Test.class)).get().asString().matches("\\d+\\.\\d+.*");
    assertThat(LatheBuildInfo.version(LatheBuildInfoTest.class)).isEmpty();
  }
}
