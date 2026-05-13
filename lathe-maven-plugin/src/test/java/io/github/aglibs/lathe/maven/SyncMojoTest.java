package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyncMojoTest {

  @Test
  void isDirectSyncInvocation_detectsDirectSyncGoal() {
    assertThat(SyncMojo.isDirectSyncInvocation(List.of("lathe:sync"))).isTrue();
  }

  @Test
  void isDirectSyncInvocation_ignoresLifecycleGoals() {
    assertThat(SyncMojo.isDirectSyncInvocation(List.of("process-test-classes"))).isFalse();
  }
}
