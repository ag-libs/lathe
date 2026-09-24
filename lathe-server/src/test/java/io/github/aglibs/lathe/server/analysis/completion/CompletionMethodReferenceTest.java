package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionMethodReferenceTest extends CompletionTestSupport {

  @Test
  void methodReference_typeReceiver_offersStaticAndInstanceMethodsButNoFields() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        String::§
                    }
                }"""));

    assertThat(labels).anyMatch(l -> l.startsWith("valueOf")); // static form
    assertThat(labels).anyMatch(l -> l.startsWith("charAt")); // unbound-instance form
    assertThat(labels).noneMatch(l -> l.startsWith("CASE_INSENSITIVE_ORDER")); // field excluded
  }

  @Test
  void methodReference_instanceReceiver_offersInstanceMethodsOnly() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(String s) {
                        s::§
                    }
                }"""));

    assertThat(labels).anyMatch(l -> l.startsWith("charAt"));
    assertThat(labels).noneMatch(l -> l.startsWith("valueOf")); // no static via an instance
  }

  @Test
  void methodReference_thisReceiver_offersEnclosingInstanceMethodsOnly() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                class Test {
                    String greet() { return "hi"; }
                    static String shout() { return "HI"; }
                    void m() {
                        this::§
                    }
                }"""));

    assertThat(labels).contains("greet");
    assertThat(labels).doesNotContain("shout"); // static not reachable via this
  }

  @Test
  void methodReference_typeReceiver_offersNewConstructorReference() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                class Widget {
                    Widget() {}
                }
                class Test {
                    void m() {
                        Widget::§
                    }
                }"""));

    assertThat(labels).contains("new");
  }

  @Test
  void methodReference_abstractType_omitsNewConstructorReference() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                abstract class Shape {
                    abstract double area();
                }
                class Test {
                    void m() {
                        Shape::§
                    }
                }"""));

    assertThat(labels).doesNotContain("new");
    assertThat(labels).contains("area"); // methods still offered
  }

  @Test
  void methodReference_argumentSlot_ranksArityCompatibleAboveIncompatible() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                import java.util.function.Function;
                class Test {
                    void take(Function<String, Integer> f) {}
                    void m() {
                        take(String::§)
                    }
                }"""));

    // SAM apply(T) has arity 1: unbound `length` (0 params + receiver) fits; `concat`
    // (1 param + receiver = 2) does not, so it is demoted below the compatible candidate.
    assertThat(labels).contains("length", "concat");
    assertThat(labels.indexOf("length")).isLessThan(labels.indexOf("concat"));
  }

  @Test
  void methodReference_noExpectedType_returnsUnfilteredMembers() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        String::§
                    }
                }"""));

    // No SAM target: nothing is demoted, so both arities are present.
    assertThat(labels).contains("length", "concat");
  }

  @Test
  void methodReference_accepted_insertsBareNameWithoutParens() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            class Test {
                void m() {
                    String::§
                }
            }""");

    // A `::` target is a method name, never a call — no `()` or snippet placeholder.
    assertThat(insertTextOf(items, "charAt")).isEqualTo("charAt");
    assertThat(insertTextOf(items, "valueOf")).isEqualTo("valueOf");
  }

  @Test
  void methodReference_prefix_filtersByName() {
    final List<String> labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        String::to§
                    }
                }"""));

    assertThat(labels).contains("toString");
    assertThat(labels).noneMatch(l -> l.startsWith("charAt"));
  }
}
