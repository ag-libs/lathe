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
}
