package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CompileMode;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import org.junit.jupiter.api.Test;

// The text fallback resolveReceiver takes when the snapshot's tree has no receiver at the
// completion's position (a snapshot attributed before the last keystrokes): each kind of receiver
// text it reads, and what it gives up on.
class ReceiverResolverTest {

  private static final String SOURCE =
      """
      class Base {}
      class Test extends Base {
          String field = "";
          void m(int param) {
              java.util.List<String> local = null;
          }
      }
      """;

  // Line 5 (0-based 4) is inside m, after `local`'s declaration.
  private static final int CURSOR_LINE = 4;

  @Test
  void resolveReceiver_fallbackOnText_resolvesEachReceiverKind() {
    final AttributedFileAnalysis snapshot = compile();

    assertThat(typeOf("\"text\"", snapshot)).isEqualTo("java.lang.String");
    assertThat(typeOf("this", snapshot)).isEqualTo("Test");
    assertThat(typeOf("super", snapshot)).isEqualTo("Base");
    assertThat(typeOf("java.util.Map", snapshot)).isEqualTo("java.util.Map<K,V>");
    assertThat(typeOf("Math", snapshot)).isEqualTo("java.lang.Math");
    assertThat(typeOf("local", snapshot)).isEqualTo("java.util.List<java.lang.String>");
    assertThat(typeOf("field", snapshot)).isEqualTo("java.lang.String");
    assertThat(resolve("Math", snapshot).staticAccess()).as("a type is static access").isTrue();
    assertThat(resolve("field", snapshot).staticAccess()).isFalse();
  }

  // A call, an index or a cast needs the attributed tree; an unknown name resolves to nothing.
  @Test
  void resolveReceiver_fallbackOnComplexOrUnknownText_none() {
    final AttributedFileAnalysis snapshot = compile();

    assertThat(resolve("field.trim()", snapshot)).isNull();
    assertThat(resolve("values[0]", snapshot)).isNull();
    assertThat(resolve("missing", snapshot)).isNull();
  }

  private static String typeOf(final String receiverText, final AttributedFileAnalysis snapshot) {
    return resolve(receiverText, snapshot).type().toString();
  }

  private static ResolvedReceiver resolve(
      final String receiverText, final AttributedFileAnalysis snapshot) {
    final var injected =
        new SentinelInjectionResult(
            "", 0, 0, receiverText, SentinelInjector.Context.STATEMENT, true, false, "");
    final ParsedSentinel sentinel =
        ParsedSentinel.valid(
            injected,
            SentinelContext.MEMBER_ACCESS,
            -1,
            -1,
            "Test",
            "m",
            -1,
            null,
            null,
            -1,
            null,
            null,
            null,
            TypeReferenceRole.ORDINARY,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            null,
            1);
    return ReceiverResolver.resolveReceiver(sentinel, CURSOR_LINE, snapshot);
  }

  private static AttributedFileAnalysis compile() {
    return new TempSourceCompiler()
        .compile(TempSourceCompiler.TEST_URI, SOURCE, CompileMode.OPEN)
        .fileAnalysis();
  }
}
