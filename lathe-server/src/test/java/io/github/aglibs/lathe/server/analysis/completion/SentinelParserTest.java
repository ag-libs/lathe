package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.SourceParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class SentinelParserTest {

  private static SourceParser sourceParser;
  private static SentinelParser sentinelParser;

  @BeforeAll
  static void setup() {
    sourceParser = new SourceParser();
    sentinelParser = new SentinelParser(sourceParser);
  }

  @AfterAll
  static void teardown() throws Exception {
    sourceParser.close();
  }

  private static ParsedSentinel parse(final String markedSource) {
    final var c = cursor(markedSource);
    final var injected = new SentinelInjector(c.content()).inject(c.offset());
    final int lspLine =
        (int) c.content().substring(0, c.offset()).chars().filter(ch -> ch == '\n').count();
    return sentinelParser.parse(injected, lspLine, 0);
  }

  @Test
  void moduleDirective_requires_isModuleDirective() {
    final var result =
        parse(
            """
        module foo {
            requires io.helidon.dbclient.metrics.§
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MODULE_DIRECTIVE);
    assertThat(result.receiverText()).isEqualTo("io.helidon.dbclient.metrics");
    assertThat(result.enclosingClass()).isNull();
    assertThat(result.enclosingMethod()).isNull();
  }

  @Test
  void moduleDirective_exports_isModuleDirective() {
    final var result =
        parse(
            """
        module foo {
            exports io.helidon.dbclient.§
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MODULE_DIRECTIVE);
    assertThat(result.receiverText()).isEqualTo("io.helidon.dbclient");
  }

  @Disabled("Not implemented yet")
  @Test
  void moduleDirective_dotAfterTransitiveKeyword_isInvalid() {
    final var result =
        parse(
            """
        module foo {
            requires transitive.§
        }""");
    assertThat(result.valid()).isFalse();
  }

  @Test
  void regularClass_memberAccess_isValid() {
    final var result =
        parse(
            """
        class Foo {
            void m(Object obj) {
                obj.§
            }
        }""");
    assertThat(result.valid()).isTrue();
    assertThat(result.sentinelContext()).isEqualTo(SentinelContext.MEMBER_ACCESS);
    assertThat(result.receiverText()).isEqualTo("obj");
    assertThat(result.enclosingClass()).isEqualTo("Foo");
    assertThat(result.enclosingMethod()).isEqualTo("m");
  }
}
