package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

class CompletionTypeIndexTest extends CompletionTestSupport {

  @Test
  void typeIndex_fieldDeclaration_itemHasCorrectMetadataAndResultIsIncomplete() {
    // gap #4: kind/detail must be populated; result must be marked incomplete
    final var outcome = fixture.outcome("class Test { ArrayD§ field; }");
    final var item = itemLabeled(outcome.items(), "ArrayDeque");
    assertThat(item).isPresent();
    assertThat(item.get().getKind()).isEqualTo(CompletionItemKind.Class);
    assertThat(item.get().getDetail()).isEqualTo("java.util.ArrayDeque");
    assertThat(outcome.incomplete()).isTrue();
  }

  @Test
  void typeIndex_suggestsIndexedTypeAtDeclarationSites() {
    // TYPE_REFERENCE positions: method param, generic arg, field, return type, extends, implements
    assertThat(labels(fixture.complete("class Test { void m(ArrayD§ p) {} }")))
        .contains("ArrayDeque");
    assertThat(
            labels(fixture.complete("class Test { void m() { java.util.List<ArrayD§> list; } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { ArrayD§ field; }"))).contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { ArrayD§ getQ() { return null; } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test extends AbstractL§ {}")))
        .contains("AbstractList");
    assertThat(labels(fixture.complete("class Test implements Runn§ {}"))).contains("Runnable");
  }

  @Test
  void typeIndex_suggestsIndexedTypeInCodeStatements() {
    // SIMPLE_NAME positions with uppercase prefix: method body, ctor body, local var, new-prefix
    assertThat(labels(fixture.complete("class Test { void m() { ArrayD§ } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { void m() { ArrayD§ local; } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { Test() { ArrayD§ } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { void m() { new StringBuilder(); ArrayD§ } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { void m() { Object x = new ArrayD§ } }")))
        .contains("ArrayDeque");
  }

  @Test
  void typeIndex_classLiteral_suggestsIndexedType() {
    assertThat(
            labels(
                fixture.complete(
                    "class Test { void register(Class<?> c) {} void m() { register(ArrayD§.class); } }")))
        .contains("ArrayDeque");
  }

  @Test
  void typeIndex_candidates_javaLangRanksBeforeOtherPackages() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry(
                    "StringJoiner", "java.util.StringJoiner", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "StringBuilder", "java.lang.StringBuilder", TypeKind.CLASS)));
    final var items = labels(localFixture.complete("class Test { Str§ field; }"));
    assertThat(items.indexOf("StringBuilder")).isLessThan(items.indexOf("StringJoiner"));
  }

  // ── java.lang fallback (no type index) ───────────────────────────────────────

  @Test
  void methodBody_typePrefix_suggestsJavaLangType_withoutTypeIndex() {
    // engine has an empty type index — String must still appear via the java.lang fallback
    localFixture = new CompletionFixture();
    assertThat(labels(localFixture.complete("class Test { void m() { Str§ value; } }")))
        .contains("String");
    assertThat(labels(localFixture.complete("class Test { void m() { Str§ } }")))
        .contains("String");
  }

  @Test
  void memberAccess_unimportedSimpleName_typeIndexFallback_suggestsMembers() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Objects", "java.util.Objects", TypeKind.CLASS)));
    assertThat(
            labels(
                localFixture.complete(
                    """
                    import static java.util.Objects.requireNonNull;

                    class Test {
                        void m(String s) {
                            Objects.§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("requireNonNull"));
  }

  // ── FQN navigation: JPMS visibility ──────────────────────────────────────────

  @Test
  void typeIndex_jpmsReadablePackage_suggestsIndexedType() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("JButton", "javax.swing.JButton", TypeKind.CLASS)),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;

                    class Test {
                        JBut§ field;
                    }""",
                    """
                    module com.example.app {
                      requires java.desktop;
                    }""")))
        .contains("JButton");
  }

  @Test
  void typeIndex_jpmsUnreadablePackage_doesNotSuggestIndexedType() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("JButton", "javax.swing.JButton", TypeKind.CLASS)),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;

                    class Test {
                        JBut§ field;
                    }""",
                    """
                    module com.example.app {
                    }""")))
        .doesNotContain("JButton");
  }

  @Test
  void typeIndex_platformType_survivesValidator_jpmsModule() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Objects", "java.util.Objects", TypeKind.CLASS)),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() {
                            Obj§
                        }
                    }""",
                    """
                    module com.example.app {
                    }""")))
        .anyMatch(l -> l.startsWith("Objects"));
  }

  // ── argument position: importable types ───────────────────────────────────────
}
