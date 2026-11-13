package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class CompletionTypeIndexTest extends CompletionTestSupport {

  @Test
  void typeIndex_fieldDeclaration_itemHasCorrectMetadataAndResultIsIncomplete() {
    // gap #4: kind/detail must be populated; result must be marked incomplete
    final var outcome = fixture.outcome("class Test { ArrayD§ field; }");
    assertThat(labels(outcome.items())).contains("ArrayDeque");
    final var item = itemLabeled(outcome.items(), "ArrayDeque").orElseThrow();
    assertThat(item.getKind()).isEqualTo(CompletionItemKind.Class);
    assertThat(item.getDetail()).isEqualTo("java.util.ArrayDeque");
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
  void constructorCall_emptyPrefixWithoutExpectedType_returnsIncomplete() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayDeque", "java.util.ArrayDeque", TypeKind.CLASS)));

    final var outcome = localFixture.outcome("class Test { void m() { final var x = new § } }");

    assertThat(outcome.incomplete()).isTrue();
  }

  @Test
  void constructorCall_emptyPrefixExpectedType_ranksExpectedTypeFirst() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayDeque", "java.util.ArrayDeque", TypeKind.CLASS)));

    final var items =
        localFixture.complete(
            """
            import java.util.ArrayDeque;

            class Test {
                void m() {
                    ArrayDeque<String> x = new §
                }
            }""");

    assertThat(items).isNotEmpty();
    assertThat(items.get(0).getLabel()).isEqualTo("ArrayDeque");
  }

  // A nested type completes by its simple name and is written as code there names it: from its
  // top-level type, which is imported, never the nested type itself; by its simple name where the
  // file already sees it (an import of it, an inherited member type); fully qualified where the
  // file gives the top-level name another type. The type after `new` and an unimported receiver
  // are written the same way.
  @Test
  void typeIndex_nestedType_writtenAsTheFileNamesItNeverImported() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Entry", "java.util.Map$Entry", TypeKind.INTERFACE),
                CompletionFixture.typeEntry(
                    "SimpleEntry", "java.util.AbstractMap$SimpleEntry", TypeKind.CLASS)));

    assertWritten("class Test { Entr§ field; }", "Entry", "Map.Entry", "import java.util.Map;\n");
    assertWritten("import java.util.Map.Entry; class Test { Entr§ field; }", "Entry", "Entry");
    assertWritten(
        "abstract class Test implements java.util.Map<String, String> { Entr§ field; }",
        "Entry",
        "Entry");
    assertWritten(
        "class Test { static class Map {} Entr§ field; }", "Entry", "java.util.Map.Entry");
    assertWritten(
        "class Test { java.util.AbstractMap.SimpleEntry<String, String> e = new SimpleE§ }",
        "SimpleEntry",
        "AbstractMap.SimpleEntry",
        "import java.util.AbstractMap;\n");

    final String receiver = "class Test { void m() { Entry.comp§ } }";
    final var member =
        localFixture.complete(receiver).stream()
            .filter(item -> item.getLabel().startsWith("comparingByKey"))
            .findFirst()
            .orElseThrow();
    assertThat(member.getAdditionalTextEdits())
        .extracting(TextEdit::getNewText)
        .containsExactlyInAnyOrder("import java.util.Map;\n", "Map.Entry");
    assertThat(member.getAdditionalTextEdits())
        .filteredOn(edit -> edit.getNewText().equals("Map.Entry"))
        .singleElement()
        .satisfies(
            edit ->
                assertThat(edit.getRange())
                    .isEqualTo(
                        new Range(
                            new Position(0, receiver.indexOf("Entry.")),
                            new Position(0, receiver.indexOf(".comp")))));
  }

  // A call chain's receiver is rewritten at its leading name. Before the file has an analysis there
  // is nothing to look at, so a nested type is written from its top-level type; no import is added
  // then, for any type, as the import's place comes from the analysis.
  @Test
  void typeIndex_nestedType_callChainReceiverAndNoAnalysisYet_writtenFromTopLevelType()
      throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Entry", "java.util.Map$Entry", TypeKind.INTERFACE)));

    final String chain = "class Test { void m() { Entry.comparingByKey().§ } }";
    assertThat(localFixture.complete(chain))
        .isNotEmpty()
        .allSatisfy(
            item ->
                assertThat(item.getAdditionalTextEdits())
                    .contains(
                        new TextEdit(
                            new Range(
                                new Position(0, chain.indexOf("Entry.")),
                                new Position(0, chain.indexOf(".comparingByKey"))),
                            "Map.Entry")));

    final var cold =
        itemLabeled(localFixture.completeWithoutCache("class Test { Entr§ field; }"), "Entry");
    assertThat(cold)
        .hasValueSatisfying(
            item -> assertThat(item.getTextEdit().getLeft().getNewText()).isEqualTo("Map.Entry"));
  }

  private void assertWritten(
      final String source, final String label, final String written, final String... imports) {
    final var item = itemLabeled(localFixture.complete(source), label).orElseThrow();
    assertThat(item.getTextEdit().getLeft().getNewText()).as(source).isEqualTo(written);
    assertThat(
            item.getAdditionalTextEdits() == null
                ? List.<String>of()
                : item.getAdditionalTextEdits().stream().map(TextEdit::getNewText).toList())
        .as(source)
        .containsExactly(imports);
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

  // A short prefix matches more types than the completer validates; after `@` only annotations
  // count toward that limit, so an annotation sorting behind a thousand classes is still offered.
  @Test
  void typeIndex_annotationPosition_findsAnnotationBehindTheCandidateLimit() throws IOException {
    final List<TypeIndexEntry> entries =
        Stream.concat(
                IntStream.range(0, 1_000)
                    .mapToObj(
                        i ->
                            CompletionFixture.typeEntry(
                                "Ge%04d".formatted(i), "a.Ge%04d".formatted(i), TypeKind.CLASS)),
                Stream.of(
                    CompletionFixture.typeEntry(
                        "GregorianCalendar", "java.util.GregorianCalendar", TypeKind.CLASS),
                    CompletionFixture.typeEntry(
                        "Generated", "javax.annotation.processing.Generated", TypeKind.ANNOTATION)))
            .toList();
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"), entries.toArray(TypeIndexEntry[]::new)));

    assertThat(labels(localFixture.complete("@Ge§ class Test {}")))
        .contains("Generated")
        .doesNotContain("GregorianCalendar");
    assertThat(labels(localFixture.complete("class Test { Greg§ field; }")))
        .contains("GregorianCalendar");
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

  // When an unimported simple name matches several classpath types (here the JDK's unrelated
  // `List` types), member completion must aggregate static members across all candidates — each
  // with its own auto-import — instead of surfacing only the first candidate's members. `of` is
  // unique to java.util.List and `TOP_ALIGNMENT` to java.awt.List (from Component); neither type
  // extends the other, and both ship with the JDK, so the test does not depend on which libraries
  // happen to be on the test classpath.
  @Test
  void memberAccess_unimportedAmbiguousSimpleName_suggestsMembersFromAllCandidates()
      throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("List", "java.util.List", TypeKind.INTERFACE),
                CompletionFixture.typeEntry("List", "java.awt.List", TypeKind.CLASS)));
    assertThat(
            labels(
                localFixture.complete(
                    """
                    class Test {
                        void m() {
                            List.§
                        }
                    }""")))
        .contains("of", "TOP_ALIGNMENT");
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

  @Test
  void typeIndex_jpmsObservableButUnreadableModule_doesNotSuggestIndexedType() throws IOException {
    // Reproduces the observable-but-unreadable module leak: a module the current module does not
    // read is nonetheless in the
    // module graph. In a real workspace java.desktop is pulled in by a dependency's non-transitive
    // `requires`; here --add-modules puts it in the graph directly. Either way it is *observable*
    // (Elements.getTypeElement resolves javax.swing.JButton) but not *readable* by com.example.app
    // (a real `import javax.swing.JButton;` fails: "module com.example.app does not read it").
    // getTypeElement alone would let JButton in; IndexTypes also checks Trees.isAccessible.
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
                    }""",
                    List.of("--add-modules", "java.desktop"))))
        .doesNotContain("JButton");
  }

  @Test
  void typeIndex_constructorSubtype_jpmsUnreadableModule_doesNotSuggestSubtype()
      throws IOException {
    // The `new X` / expected-type path resolves subtypes through
    // TypeReferenceCompleter.instantiableSubtypeCandidates, not IndexTypes. It must apply
    // the same JPMS readability gate: JButton is an observable-but-unreadable subtype of Object and
    // must not be offered as a constructor candidate.
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry(
                    "JButton", "javax.swing.JButton", TypeKind.CLASS, List.of("java.lang.Object"))),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;

                    class Test {
                        void m() {
                            Object o = new JBut§
                        }
                    }""",
                    """
                    module com.example.app {
                    }""",
                    List.of("--add-modules", "java.desktop"))))
        .doesNotContain("JButton");
  }

  @Test
  void typeIndex_constructorSubtype_jpmsReadablePackage_suggestsSubtype() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry(
                    "JButton", "javax.swing.JButton", TypeKind.CLASS, List.of("java.lang.Object"))),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;

                    class Test {
                        void m() {
                            Object o = new JBut§
                        }
                    }""",
                    """
                    module com.example.app {
                      requires java.desktop;
                    }""")))
        .contains("JButton");
  }

  // A type just created or renamed is in the reactor index before it has a class file: offered,
  // as the import quick fix offers it. A dependency type with no class file is not.
  @Test
  void typeIndex_typeWithoutClassFile_offeredOnlyFromTheReactor() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                List.of(
                    CompletionFixture.typeEntry(
                        "MyCustomError", "com.dep.MyCustomError", TypeKind.CLASS)),
                List.of(
                    CompletionFixture.typeEntry(
                        "MyCustomException", "com.example.MyCustomException", TypeKind.CLASS))));

    final List<CompletionItem> items =
        localFixture.complete(
            """
            package com.other;
            class Test {
                MyCus§ field;
            }""");

    assertThat(labels(items)).contains("MyCustomException").doesNotContain("MyCustomError");
    assertThat(
            items.stream()
                .filter(item -> item.getLabel().equals("MyCustomException"))
                .findFirst()
                .orElseThrow()
                .getAdditionalTextEdits())
        .extracting(TextEdit::getNewText)
        .anyMatch(text -> text.contains("import com.example.MyCustomException;"));
  }

  // ── argument position: importable types ───────────────────────────────────────
}
