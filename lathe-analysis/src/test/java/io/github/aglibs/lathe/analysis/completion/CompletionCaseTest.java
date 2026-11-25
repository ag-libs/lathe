package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CompletionCaseTest extends CompletionTestSupport {

  @Test
  void completion_caseInSealedSwitch_offersPermittedSubtypes() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            sealed interface Shape permits Circle, Square {}
            record Circle() implements Shape {}
            record Square() implements Shape {}
            class Test {
              String describe(Shape s) {
                return switch (s) {
                  case §
                };
              }
            }""");

    assertThat(labels(items))
        .contains("Circle", "Square")
        .doesNotContain("String", "StringBuilder");
    assertThat(itemLabeled(items, "Circle"))
        .hasValueSatisfying(i -> assertThat(i.getInsertText()).isEqualTo("Circle circle ->"));
  }

  @Test
  void completion_caseInEnumSwitch_unchanged() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            enum Color { RED, GREEN, BLUE }
            class Test {
              String describe(Color c) {
                return switch (c) {
                  case §
                };
              }
            }""");

    assertThat(labels(items)).contains("RED", "GREEN", "BLUE");
  }

  // CQ-0079: typed line by line, the switch block is not closed yet, and the next statement
  // follows.
  @ParameterizedTest(name = "{0}")
  @MethodSource("unclosedSwitchLabels")
  void completion_memberAccessInUnclosedSwitchLabel_offersTheReceiversMembers(
      final String label, final String source) {
    assertThat(labels(fixture.complete(source))).as(label).contains("isEmpty");
  }

  static Stream<Arguments> unclosedSwitchLabels() {
    return Stream.of(
        unclosedSwitch("arrowCase", "case String str -> str.§"),
        unclosedSwitch("guardedArrowCase", "case String str when str.length() > 2 -> str.§"),
        unclosedSwitch("defaultArrow", "default -> text.§"),
        unclosedSwitch("colonCase", "case String str:\n        str.§"),
        unclosedSwitch("stringLabel", "case \"a;b\" -> text.§"),
        unclosedSwitch("escapedQuoteInStringLabel", "case \"x\\\";y\" -> text.§"),
        unclosedSwitch("charLabel", "case ';' -> text.§"),
        unclosedSwitch("braceCharLabel", "case '}' -> text.§"),
        unclosedSwitch("escapedQuoteCharLabel", "case '\\'' -> text.§"),
        unclosedSwitch("recordPattern", "case Box(String inner) -> inner.§"));
  }

  private static Arguments unclosedSwitch(final String label, final String caseLine) {
    return Arguments.of(
        label,
        """
        record Box(String inner) {}
        class Test {
          int next;
          void m(Object o, String text) {
            switch (o) {
              %s
            next = 1;
          }
        }"""
            .formatted(caseLine));
  }

  // A lambda's arrow and a ternary's colon still end an expression, not a statement.
  @Test
  void completion_memberAccessInLambdaBodyBeforeAStatement_offersTheReceiversMembers() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            class Test {
              int next;
              void m(java.util.List<String> list) {
                list.forEach(s -> s.§);
                next = 1;
              }
            }""");

    assertThat(labels(items)).contains("isEmpty");
  }

  @Test
  void completion_memberAccessAfterTernaryColon_offersTheReceiversMembers() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            class Test {
              int next;
              void m(boolean b, String s) {
                int n = b ? 0 : s.§
                next = 1;
              }
            }""");

    assertThat(labels(items)).contains("length");
  }

  // As in the editor, the analysis is the file's before the switch was typed: the receiver resolves
  // on a reattribution of the edited text, whose switch block is still open.
  @Test
  void completion_memberAccessInUnclosedSwitchLabelAfterAnEdit_offersTheReceiversMembers() {
    final String before =
        """
        class Test {
          int next;
          void m(Object o) {
            next = 1;
          }
          void n() {}
        }""";
    final List<CompletionItem> items =
        fixture.completeWithCache(
            before,
            """
            class Test {
              int next;
              void m(Object o) {
                switch (o) {
                  case String str -> str.§
                next = 1;
              }
              void n() {}
            }""");

    assertThat(labels(items)).contains("isEmpty");
  }

  @Test
  void completion_memberAccessInClosedSwitchLabelAfterAnEdit_offersTheReceiversMembers() {
    final String before =
        """
        class Test {
          void m(Object o) {
          }
        }""";
    final List<CompletionItem> items =
        fixture.completeWithCache(
            before,
            """
            class Test {
              void m(Object o) {
                switch (o) {
                  case String str -> str.§
                  default -> { }
                }
              }
            }""");

    assertThat(labels(items)).contains("isEmpty");
  }

  // A block typed line by line, not closed yet, before the class's other members: the receiver
  // resolves, a type of the enclosing class among them.
  @ParameterizedTest(name = "{0}")
  @MethodSource("unclosedBlocks")
  void completion_memberAccessInUnclosedBlockAfterAnEdit_offersTheReceiversMembers(
      final String label, final String block, final String member) {
    final String before =
        """
        class Test {
          static class Nested { int width() { return 0; } }
          int next;
          void m(Object o, Nested nested) {
            next = 1;
          }
          void n() {}
        }""";
    final List<CompletionItem> items =
        fixture.completeWithCache(
            before,
            """
            class Test {
              static class Nested { int width() { return 0; } }
              int next;
              void m(Object o, Nested nested) {
                %s
                next = 1;
              }
              void n() {}
            }"""
                .formatted(block));

    assertThat(labels(items)).as(label).contains(member);
  }

  static Stream<Arguments> unclosedBlocks() {
    return Stream.of(
        Arguments.of("switchOnNestedType", "switch (o) {\n case Nested w -> w.§", "width"),
        Arguments.of("ifBlock", "if (next > 0) {\n nested.§", "width"),
        Arguments.of("lambdaBlock", "Runnable r = () -> {\n nested.§", "width"),
        Arguments.of("forBlock", "for (int i = 0; i < 2; i++) {\n nested.§", "width"));
  }
}
