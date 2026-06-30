package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompletionThisTest extends CompletionTestSupport {

  @Test
  void completion_thisReceiver_suppressesObjectSyncMethods() {
    final var items =
        fixture.complete(
            """
            class Test {
                String name;
                void m() { this.§ }
            }""");
    assertThat(labels(items)).doesNotContain("notify", "notifyAll", "wait");
  }
}
