package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

class CompletionSiteTest {

  @Test
  void from_lowercaseSimpleName_selectsValueMode() {
    final var site = site("str", SentinelContext.SIMPLE_NAME, SentinelInjector.Context.STATEMENT);

    assertThat(site.mode()).isEqualTo(CompletionMode.VALUE);
  }

  @Test
  void from_uppercaseSimpleName_selectsMixedMode() {
    final var site = site("Str", SentinelContext.SIMPLE_NAME, SentinelInjector.Context.STATEMENT);

    assertThat(site.mode()).isEqualTo(CompletionMode.MIXED);
  }

  @Test
  void from_memberAccess_selectsMemberModeAndPrefixRange() {
    final var site =
        site("sub", SentinelContext.MEMBER_ACCESS, SentinelInjector.Context.EXPRESSION);

    assertThat(site.mode()).isEqualTo(CompletionMode.MEMBER);
    assertThat(site.replacementRange().getStart()).isEqualTo(new Position(0, 5));
    assertThat(site.replacementRange().getEnd()).isEqualTo(new Position(0, 8));
  }

  private static CompletionSite site(
      final String prefix,
      final SentinelContext sentinelContext,
      final SentinelInjector.Context injectorContext) {
    final var request =
        new CompletionRequest(
            "file:///Test.java", "list.%s".formatted(prefix), new Position(0, 8), null, null);
    final var injected = new SentinelResult(prefix, 5, "list", injectorContext, true, "");
    final var parsed = ParsedSentinel.valid(injected, sentinelContext, 4, -1);
    return CompletionSite.from(request, injected, parsed);
  }
}
