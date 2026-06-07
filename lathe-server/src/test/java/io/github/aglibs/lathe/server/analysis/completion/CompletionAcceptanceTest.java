package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class CompletionAcceptanceTest extends CompletionTestSupport {

  @Test
  void accept_staticImportMethod_insertsBareMemberAndSemicolon() {
    final CompletionItem item =
        itemLabeled(
                fixture.complete(
                    """
                    import static java.util.Collections.emptyL§
                    class Test {}
                    """),
                "emptyList")
            .orElseThrow();

    assertThat(
            accept(
                """
            import static java.util.Collections.emptyL§
            class Test {}
            """,
                item))
        .isEqualTo(
            """
            import static java.util.Collections.emptyList;§
            class Test {}
            """);
  }

  @Test
  void accept_staticImportMethod_beforeSemicolon_preservesExistingSemicolon() {
    final CompletionItem item =
        itemLabeled(
                fixture.complete(
                    """
                    import static java.util.Collections.emptyL§;
                    class Test {}
                    """),
                "emptyList")
            .orElseThrow();

    assertThat(
            accept(
                """
            import static java.util.Collections.emptyL§;
            class Test {}
            """,
                item))
        .isEqualTo(
            """
            import static java.util.Collections.emptyList§;
            class Test {}
            """);
  }

  @Test
  void accept_staticImportParameterizedMethod_insertsBareMemberAndSemicolon() {
    final CompletionItem item =
        itemWithLabelDetail(
                fixture.complete(
                    """
                import static java.util.Objects.requireNon§
                class Test {}
                """),
                "requireNonNull",
                "(T, String)")
            .orElseThrow();

    assertThat(
            accept(
                """
            import static java.util.Objects.requireNon§
            class Test {}
            """,
                item))
        .isEqualTo(
            """
            import static java.util.Objects.requireNonNull;§
            class Test {}
            """);
  }

  @Test
  void accept_overloadedZeroArgMethod_placesCursorAfterCall() {
    final CompletionItem item =
        itemWithLabelDetail(fixture.complete(overloadedPingSource("r.pi§")), "ping", "()")
            .orElseThrow();

    assertThat(accept(overloadedPingSource("r.pi§"), item))
        .isEqualTo(overloadedPingSource("r.ping()§"));
  }

  @Test
  void accept_overloadedParameterizedMethod_placesCursorInsideCall() {
    final CompletionItem item =
        itemWithLabelDetail(
                fixture.complete(overloadedPingSource("r.pi§")), "ping", "(String value)")
            .orElseThrow();

    assertThat(item.getInsertTextFormat()).isEqualTo(InsertTextFormat.Snippet);
    assertThat(accept(overloadedPingSource("r.pi§"), item))
        .isEqualTo(overloadedPingSource("r.ping(§)"));
  }

  @Test
  void accept_noArgMethod_inVariableInitializer_withoutSemicolon_appendsSemicolon() {
    final CompletionItem item =
        itemWithLabelDetail(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            boolean x = Boolean.TRUE.booleanV§
                        }
                    }
                    """),
                "booleanValue",
                "()")
            .orElseThrow();

    assertThat(
            accept(
                """
                class Test {
                    void m() {
                        boolean x = Boolean.TRUE.booleanV§
                    }
                }
                """,
                item))
        .isEqualTo(
            """
            class Test {
                void m() {
                    boolean x = Boolean.TRUE.booleanValue();§
                }
            }
            """);
  }

  @Test
  void accept_noArgMethod_inVariableInitializer_withSemicolon_preservesSemicolon() {
    final CompletionItem item =
        itemWithLabelDetail(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            boolean x = Boolean.TRUE.booleanV§;
                        }
                    }
                    """),
                "booleanValue",
                "()")
            .orElseThrow();

    assertThat(
            accept(
                """
                class Test {
                    void m() {
                        boolean x = Boolean.TRUE.booleanV§;
                    }
                }
                """,
                item))
        .isEqualTo(
            """
            class Test {
                void m() {
                    boolean x = Boolean.TRUE.booleanValue()§;
                }
            }
            """);
  }

  private static String overloadedPingSource(final String callLine) {
    return """
        class Test {
            static class Receiver {
                void ping() {}
                void ping(String value) {}
            }

            void m(Receiver r) {
                %s
            }
        }
        """
        .formatted(callLine);
  }

  private static String accept(final String markedSource, final CompletionItem item) {
    final var cursor = CursorFixture.cursor(markedSource);
    final var accepted = applyCompletion(cursor.content(), item);
    return accepted.content().substring(0, accepted.cursorOffset())
        + "§"
        + accepted.content().substring(accepted.cursorOffset());
  }

  private static Accepted applyCompletion(final String content, final CompletionItem item) {
    final var textEdit = item.getTextEdit().getLeft();
    final String rawText = textEdit.getNewText();
    final var replacement =
        item.getInsertTextFormat() == InsertTextFormat.Snippet
            ? expandSnippet(rawText)
            : new Replacement(rawText, rawText.length());

    final var edits =
        item.getAdditionalTextEdits() == null ? List.<TextEdit>of() : item.getAdditionalTextEdits();
    final var editsWithPrimary =
        java.util.stream.Stream.concat(edits.stream(), java.util.stream.Stream.of(textEdit))
            .sorted(
                Comparator.comparingInt(
                    edit -> -CursorFixture.offset(content, edit.getRange().getStart())))
            .toList();

    String updated = content;
    int cursorOffset = -1;
    for (final TextEdit edit : editsWithPrimary) {
      final int start = CursorFixture.offset(content, edit.getRange().getStart());
      final int end = CursorFixture.offset(content, edit.getRange().getEnd());
      final boolean primary = edit == textEdit;
      final String newText = primary ? replacement.text() : edit.getNewText();
      updated = updated.substring(0, start) + newText + updated.substring(end);
      if (primary) {
        cursorOffset = start + replacement.cursorOffset();
      }
    }

    return new Accepted(updated, cursorOffset);
  }

  private static Replacement expandSnippet(final String text) {
    final int placeholder = text.indexOf("$1");
    if (placeholder >= 0) {
      return new Replacement(text.replace("$1", ""), placeholder);
    }

    final int finalTabstop = text.indexOf("$0");
    if (finalTabstop >= 0) {
      return new Replacement(text.replace("$0", ""), finalTabstop);
    }

    return new Replacement(text, text.length());
  }

  private record Accepted(String content, int cursorOffset) {}

  private record Replacement(String text, int cursorOffset) {}
}
