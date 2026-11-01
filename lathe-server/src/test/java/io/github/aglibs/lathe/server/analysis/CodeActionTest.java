package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeActionTest {
  @TempDir private Path tmp;

  private WorkspaceTypeIndex typeIndex;
  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() throws IOException {
    typeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("index.json"),
            new TypeIndexEntry(
                "ArrayList", "java.util.ArrayList", "java.util", TypeKind.CLASS, true, List.of()),
            new TypeIndexEntry(
                "Generated",
                "javax.annotation.processing.Generated",
                "javax.annotation.processing",
                TypeKind.ANNOTATION,
                true,
                List.of()));
    session = new SourceAnalysisSession(new TempSourceCompiler());
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  // --- Classification ---

  @Test
  void compile_unresolvedTypeInField_setsTypeRefPayload() {
    final var source =
        """
        package com.example;
        class Test {
          ArrayList list;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.cant.resolve");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.TYPE_REF);
    assertThat(payload.name()).isEqualTo("ArrayList");
  }

  // An annotation's name is a type wherever it is used, so it gets the same import fix.
  @Test
  void codeAction_unresolvedAnnotation_classifiedAsTypeWithImportQuickFix() {
    final var source =
        """
        package com.example;
        class Test {
          @Generated String field;
          @Generated void method() {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    assertThat(diags)
        .filteredOn(d -> d.getData() instanceof DiagnosticPayload)
        .extracting(d -> (DiagnosticPayload) d.getData())
        .hasSize(2)
        .allMatch(payload -> payload.kind() == DiagnosticPayload.Kind.TYPE_REF)
        .allMatch(payload -> payload.name().equals("Generated"));
    assertThat(codeActions(diagnosticActions(source, diags)))
        .extracting(CodeAction::getTitle)
        .contains("Import 'javax.annotation.processing.Generated'");
  }

  // --- Static imports learned from the file's scope ---

  private static final LearnedStaticImports LEARNED =
      new LearnedStaticImports(
          Map.of("java.util.Objects.requireNonNull", 2, "java.util.Collections.*", 1));

  private static final String UNRESOLVED_MEMBERS =
      """
      package com.example;
      class Test {
        void m(Object o) {
          requireNonNull(o);
          emptyList();
          notImportedAnywhere();
        }
      }
      """;

  // An unresolved call the scope statically imports elsewhere gets one "Import 'static …'" action
  // per owner; with nothing learned it gets none.
  @Test
  void codeAction_unresolvedLearnedMember_offersStaticImport() {
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, UNRESOLVED_MEMBERS, 1, CompileMode.OPEN);

    final List<CodeAction> learned =
        codeActions(
            session.codeAction(
                TempSourceCompiler.TEST_URI,
                UNRESOLVED_MEMBERS,
                1,
                rangeAt(0, 0),
                toRequests(diags),
                typeIndex,
                LEARNED));
    assertThat(learned)
        .extracting(CodeAction::getTitle)
        .contains(
            "Import 'static java.util.Objects.requireNonNull'",
            "Import 'static java.util.Collections.emptyList'");
    assertThat(learned)
        .filteredOn(action -> action.getTitle().contains("requireNonNull"))
        .singleElement()
        .satisfies(
            action ->
                assertThat(action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI))
                    .extracting(TextEdit::getNewText)
                    .containsExactly("\n\nimport static java.util.Objects.requireNonNull;"));
    assertThat(codeActions(diagnosticActions(UNRESOLVED_MEMBERS, diags)))
        .extracting(CodeAction::getTitle)
        .noneMatch(title -> title.startsWith("Import 'static"));
  }

  // Missing imports lists an unresolved member with its static import, and leaves out a name no
  // learned import declares: that is a typo, not an import to add.
  @Test
  void missingImports_unresolvedLearnedMember_listedWithStaticCandidate() {
    final var result =
        session.missingImports(
            TempSourceCompiler.TEST_URI, UNRESOLVED_MEMBERS, 1, typeIndex, LEARNED);

    assertThat(result.items())
        .containsExactlyInAnyOrder(
            new MissingImportsResult.MissingImport(
                "requireNonNull", List.of("static java.util.Objects.requireNonNull")),
            new MissingImportsResult.MissingImport(
                "emptyList", List.of("static java.util.Collections.emptyList")));
  }

  // --- Qualifiers: an unresolved X in X.member may be a type or a statically imported field ---

  private static final String UNRESOLVED_QUALIFIERS =
      """
      package com.example;

      class Test {
        void m(Object o) {
          Objects.requireNonNull(o);
          Duration.ofSeconds(1);
          separator.length();
          typo.run();
          java.util.Lisst.of();
          TimeUnit.SECONDS.toMillis(1);
          Map.Entry.comparingByKey();
        }
      }
      """;

  private static final LearnedStaticImports LEARNED_FIELD =
      new LearnedStaticImports(Map.of("java.io.File.separator", 1));

  private WorkspaceTypeIndex qualifierIndex() throws IOException {
    return TempSourceCompiler.typeIndex(
        tmp.resolve("qualifier_index.json"),
        new TypeIndexEntry(
            "Objects", "java.util.Objects", "java.util", TypeKind.CLASS, true, List.of()),
        new TypeIndexEntry(
            "Duration", "java.time.Duration", "java.time", TypeKind.CLASS, true, List.of()),
        new TypeIndexEntry(
            "Duration",
            "javax.xml.datatype.Duration",
            "javax.xml.datatype",
            TypeKind.CLASS,
            true,
            List.of()),
        new TypeIndexEntry(
            "TimeUnit",
            "java.util.concurrent.TimeUnit",
            "java.util.concurrent",
            TypeKind.ENUM,
            true,
            List.of()),
        new TypeIndexEntry(
            "Map", "java.util.Map", "java.util", TypeKind.INTERFACE, true, List.of()));
  }

  // The qualifier itself is classified as one, and so is the type a chain starts with, which javac
  // reports as a missing package; a qualified name that misses its last part (java.util.Lisst)
  // starts at a qualifier too, but names something else, and an import's package is just missing.
  @Test
  void compile_unresolvedQualifier_setsQualifierRefPayloadOnlyForTheQualifierItself() {
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, UNRESOLVED_QUALIFIERS, 1, CompileMode.OPEN);

    final Map<String, DiagnosticPayload.Kind> kinds =
        diags.stream()
            .filter(d -> d.getData() instanceof DiagnosticPayload)
            .map(d -> (DiagnosticPayload) d.getData())
            .collect(
                Collectors.toMap(DiagnosticPayload::name, DiagnosticPayload::kind, (a, b) -> a));
    assertThat(kinds)
        .containsEntry("Objects", DiagnosticPayload.Kind.QUALIFIER_REF)
        .containsEntry("Duration", DiagnosticPayload.Kind.QUALIFIER_REF)
        .containsEntry("separator", DiagnosticPayload.Kind.QUALIFIER_REF)
        .containsEntry("typo", DiagnosticPayload.Kind.QUALIFIER_REF)
        .containsEntry("TimeUnit", DiagnosticPayload.Kind.QUALIFIER_REF)
        .containsEntry("Map", DiagnosticPayload.Kind.QUALIFIER_REF)
        .doesNotContainEntry("Lisst", DiagnosticPayload.Kind.QUALIFIER_REF);

    final String missingPackage = "import com.missing.Thing;\nclass Test {}\n";
    assertThat(session.compile(TempSourceCompiler.TEST_URI, missingPackage, 2, CompileMode.OPEN))
        .extracting(Diagnostic::getData)
        .noneMatch(DiagnosticPayload.class::isInstance);
  }

  // A qualifier gets the type import and the learned static import, a blank line below the package.
  @Test
  void codeAction_unresolvedQualifier_offersTypeAndStaticImports() throws IOException {
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, UNRESOLVED_QUALIFIERS, 1, CompileMode.OPEN);

    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            UNRESOLVED_QUALIFIERS,
            1,
            rangeAt(0, 0),
            toRequests(diags),
            qualifierIndex(),
            LEARNED_FIELD);

    assertThat(codeActions(actions))
        .extracting(CodeAction::getTitle)
        .contains(
            "Import 'java.util.Objects'",
            "Import 'java.time.Duration'",
            "Import 'javax.xml.datatype.Duration'",
            "Import 'static java.io.File.separator'",
            "Import 'java.util.concurrent.TimeUnit'",
            "Import 'java.util.Map'");
    assertThat(codeActions(actions))
        .filteredOn(action -> action.getTitle().equals("Import 'java.util.Objects'"))
        .singleElement()
        .satisfies(
            action ->
                assertThat(action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI))
                    .extracting(TextEdit::getNewText)
                    .containsExactly("\n\nimport java.util.Objects;"));
    assertThat(actions)
        .filteredOn(Either::isLeft)
        .extracting(action -> action.getLeft().getCommand())
        .containsExactly("lathe.missingImports");
  }

  // Missing imports lists each qualifier with every candidate of either kind: a single one is
  // added, several are a choice, and a qualifier matching nothing is a typo, left out.
  @Test
  void missingImports_unresolvedQualifiers_listedWithTypeAndStaticCandidates() throws IOException {
    // The project references javax.xml.datatype.Duration, so it leads its alphabetically-earlier
    // namesake.
    final var result =
        session.missingImports(
            TempSourceCompiler.TEST_URI,
            UNRESOLVED_QUALIFIERS,
            1,
            qualifierIndex().withUsageCounts(Map.of("javax.xml.datatype.Duration", 3)),
            LEARNED_FIELD);

    assertThat(candidatesOf(result, "Objects")).containsExactly("java.util.Objects");
    assertThat(candidatesOf(result, "Duration"))
        .containsExactly("javax.xml.datatype.Duration", "java.time.Duration");
    assertThat(candidatesOf(result, "separator")).containsExactly("static java.io.File.separator");
    assertThat(candidatesOf(result, "TimeUnit")).containsExactly("java.util.concurrent.TimeUnit");
    assertThat(candidatesOf(result, "Map")).containsExactly("java.util.Map");
    assertThat(result.items())
        .extracting(MissingImportsResult.MissingImport::name)
        .doesNotContain("typo");
  }

  // The chosen imports make the file compile: the type a chain starts with is the whole fix for
  // it, and an import lands next to the import or package clause it sorts by, never above the
  // package or inside the class, whatever shares their line.
  @Test
  void missingImports_chosenImports_insertInPlaceAndCompile() throws IOException {
    final WorkspaceTypeIndex index = qualifierIndex();
    Map.of(
            """
            package com.example;

            class Test {
              long m() {
                Map.Entry.comparingByKey();
                return TimeUnit.SECONDS.toMillis(1);
              }
            }
            """,
            List.of("java.util.Map", "java.util.concurrent.TimeUnit"),
            "package com.example; import java.util.Set;\n"
                + "class Test { Set<String> s; Map<String, String> m; }\n",
            List.of("java.util.Map"),
            "package com.example;\n"
                + "import java.util.Set; class Test { Set<String> s; TimeUnit u; }\n",
            List.of("java.util.concurrent.TimeUnit"),
            "package com.example; class Test { Map<String, String> m; }\n",
            List.of("java.util.Map"))
        .forEach(
            (source, chosen) ->
                assertCompilesClean(
                    applyEdits(
                        source,
                        session
                            .missingImports(
                                TempSourceCompiler.TEST_URI,
                                source,
                                1,
                                index,
                                LearnedStaticImports.empty())
                            .edits(chosen))));
  }

  @Test
  void compile_unresolvedIdentifierInInitializer_setsVariableRefPayload() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            int x = unknownVar;
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.cant.resolve");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.VARIABLE_REF);
    assertThat(payload.name()).isEqualTo("unknownVar");
  }

  @Test
  void compile_unreportedException_setsUnreportedExceptionPayload() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.io.IOException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.unreported.exception");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.UNREPORTED_EXCEPTION);
    assertThat(payload.name()).isEqualTo("java.io.IOException");
  }

  // --- Import provider ---

  @Test
  void codeAction_typeRef_returnsImportQuickFix() {
    final var source =
        """
        package com.example;
        class Test {
          ArrayList list;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);
    final List<CodeAction> imports = codeActions(actions);

    assertThat(imports).hasSize(1);
    final var action = imports.get(0);
    assertThat(action.getTitle()).isEqualTo("Import 'java.util.ArrayList'");
    assertThat(action.getKind()).isEqualTo("quickfix");
    assertThat(action.getDiagnostics()).hasSize(1);

    final var edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).isEqualTo("\n\nimport java.util.ArrayList;");
    assertThat(edits.get(0).getRange().getStart().getLine()).isEqualTo(0);
  }

  @Test
  void codeAction_typeRef_manyPrefixSiblingsInIndex_stillOffersExactImport() throws IOException {
    // The `List*` siblings all sort before java.util.List by binaryName, so a limit-truncated
    // prefix scan would starve the exact match; resolution must look the exact name up directly.
    final var siblings =
        IntStream.range(0, 120)
            .mapToObj(
                i ->
                    new TypeIndexEntry(
                        "List%03d".formatted(i),
                        "com.example.dep.List%03d".formatted(i),
                        "com.example.dep",
                        TypeKind.CLASS,
                        true,
                        List.of()));
    final var jdkList =
        Stream.of(
            new TypeIndexEntry(
                "List", "java.util.List", "java.util", TypeKind.CLASS, true, List.of()));
    final WorkspaceTypeIndex listIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("list_index.json"),
            Stream.concat(siblings, jdkList).toArray(TypeIndexEntry[]::new));

    final var source =
        """
        package com.example;
        record Order(List<String> lines) {}
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            source,
            1,
            rangeAt(0, 0),
            toRequests(diags),
            listIndex,
            LearnedStaticImports.empty());

    assertThat(codeActions(actions))
        .extracting(CodeAction::getTitle)
        .containsExactly("Import 'java.util.List'");
  }

  @Test
  void codeAction_typeRef_offersAddMissingImportsCommand() {
    final var source =
        """
        package com.example;
        class Test {
          ArrayList list;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final List<Command> commands =
        diagnosticActions(source, diags).stream()
            .filter(Either::isLeft)
            .map(Either::getLeft)
            .toList();

    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).getCommand()).isEqualTo("lathe.missingImports");
    assertThat(commands.get(0).getTitle()).isEqualTo("Add missing imports…");
  }

  @Test
  void codeAction_alreadyImportedType_isSkipped() {
    final var sourceWithoutImport =
        """
        package com.example;
        class Test { ArrayList list; }
        """;
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, sourceWithoutImport, 1, CompileMode.OPEN);

    final var sourceWithImport =
        """
        package com.example;
        import java.util.ArrayList;
        class Test { ArrayList list; }
        """;
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            sourceWithImport,
            2,
            rangeAt(0, 0),
            toRequests(diags),
            typeIndex,
            LearnedStaticImports.empty());
    assertThat(codeActions(actions)).isEmpty();
  }

  @Test
  void codeAction_noDiagnostics_returnsEmpty() {
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            "",
            1,
            rangeAt(0, 0),
            List.of(),
            typeIndex,
            LearnedStaticImports.empty());
    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_inaccessibleType_isSkipped() throws IOException {
    final var classDir = tmp.resolve("classes");
    final var ppFile = tmp.resolve("PackagePrivateClass.java");
    Files.writeString(
        ppFile,
        """
        package com.other;
        class PackagePrivateClass {}
        """);
    final var pubFile = tmp.resolve("PublicClass.java");
    Files.writeString(
        pubFile,
        """
        package com.other;
        public class PublicClass {}
        """);
    TestCompiler.compileToDir(classDir, ppFile, pubFile);
    session.close();
    session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));

    final WorkspaceTypeIndex customTypeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("custom_index.json"),
            new TypeIndexEntry(
                "PackagePrivateClass",
                "com.other.PackagePrivateClass",
                "com.other",
                TypeKind.CLASS,
                true,
                List.of()),
            new TypeIndexEntry(
                "PublicClass",
                "com.other.PublicClass",
                "com.other",
                TypeKind.CLASS,
                true,
                List.of()));

    final var source =
        """
        package com.example;
        class Test {
          PackagePrivateClass pp;
          PublicClass pub;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            source,
            1,
            rangeAt(0, 0),
            toRequests(diags),
            customTypeIndex,
            LearnedStaticImports.empty());

    assertThat(codeActions(actions)).hasSize(1);
    assertThat(codeActions(actions).get(0).getTitle()).isEqualTo("Import 'com.other.PublicClass'");
  }

  @Test
  void codeAction_typeRef_reactorEntryNotOnClasspath_offersImport() {
    // Reactor type is in the type index but has no class file on the compilation classpath.
    // This happens when a project type is created/renamed but not yet synced via Maven.
    final var reactorEntry =
        new TypeIndexEntry(
            "MyCustomException",
            "com.example.MyCustomException",
            "com.example",
            TypeKind.CLASS,
            true,
            List.of());
    final var reactorIndex = WorkspaceTypeIndex.build(List.of(), List.of(List.of(reactorEntry)));

    final var source =
        """
        package com.other;
        class Test {
          void m() throws MyCustomException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            source,
            1,
            rangeAt(0, 0),
            toRequests(diags),
            reactorIndex,
            LearnedStaticImports.empty());

    assertThat(codeActions(actions)).hasSize(1);
    assertThat(codeActions(actions).get(0).getTitle())
        .isEqualTo("Import 'com.example.MyCustomException'");
  }

  // --- Missing imports command ---

  @Test
  void missingImports_mixedNames_partitionsCandidatesByAmbiguity() throws IOException {
    final WorkspaceTypeIndex index =
        TempSourceCompiler.typeIndex(
            tmp.resolve("mixed_index.json"),
            new TypeIndexEntry(
                "ArrayList", "java.util.ArrayList", "java.util", TypeKind.CLASS, true, List.of()),
            new TypeIndexEntry(
                "List", "java.util.List", "java.util", TypeKind.CLASS, true, List.of()),
            new TypeIndexEntry(
                "List", "java.awt.List", "java.awt", TypeKind.CLASS, true, List.of()));

    final var source =
        """
        package com.example;
        class Test {
          ArrayList a;
          List b;
          Widget c;
        }
        """;

    final var result =
        session.missingImports(
            TempSourceCompiler.TEST_URI, source, 1, index, LearnedStaticImports.empty());

    // Insertion goes right after the package clause (no imports yet).
    assertThat(result.layout().edit("java.util.ArrayList").getRange().getStart().getLine())
        .isEqualTo(0);
    assertThat(result.items())
        .extracting(MissingImportsResult.MissingImport::name)
        .containsExactlyInAnyOrder("ArrayList", "List", "Widget");
    assertThat(candidatesOf(result, "ArrayList")).containsExactly("java.util.ArrayList");
    assertThat(candidatesOf(result, "List"))
        .containsExactlyInAnyOrder("java.util.List", "java.awt.List");
    assertThat(candidatesOf(result, "Widget")).isEmpty();
  }

  @Test
  void missingImports_allTypesResolved_returnsNoItems() {
    final var source =
        """
        package com.example;
        import java.util.ArrayList;
        class Test {
          ArrayList a;
        }
        """;

    final var result =
        session.missingImports(
            TempSourceCompiler.TEST_URI, source, 1, typeIndex, LearnedStaticImports.empty());

    assertThat(result.items()).isEmpty();
  }

  private static List<String> candidatesOf(final MissingImportsResult result, final String name) {
    return result.items().stream()
        .filter(i -> i.name().equals(name))
        .findFirst()
        .orElseThrow()
        .candidates();
  }

  // --- AddThrows provider ---

  @Test
  void codeAction_unreportedException_addsThrowsAndImport() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.io.IOException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(2);
    final var throwsAction = findThrowsAction(actions);
    assertThat(throwsAction.getTitle()).isEqualTo("Add 'throws IOException' to method");
    assertThat(throwsAction.getKind()).isEqualTo("quickfix");
    assertThat(throwsAction.getDiagnostics()).hasSize(1);

    final List<TextEdit> edits =
        throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(2);

    final var throwsEdit =
        edits.stream().filter(e -> e.getNewText().contains("throws")).findFirst().orElseThrow();
    assertThat(throwsEdit.getNewText()).isEqualTo(" throws IOException");

    final var importEdit =
        edits.stream().filter(e -> e.getNewText().contains("import")).findFirst().orElseThrow();
    assertThat(importEdit.getNewText()).isEqualTo("\n\nimport java.io.IOException;");
  }

  @Test
  void codeAction_unreportedException_appendsToExistingThrows() {
    final var source =
        """
        package com.example;
        import java.io.IOException;
        class Test {
          void method() throws IOException { helper(); }
          void helper() throws java.io.IOException, java.sql.SQLException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final List<CodeActionRequest> sqlOnly =
        toRequests(diags).stream()
            .filter(r -> r.payload().name().equals("java.sql.SQLException"))
            .toList();
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            source,
            1,
            rangeAt(0, 0),
            sqlOnly,
            typeIndex,
            LearnedStaticImports.empty());

    assertThat(actions).hasSize(2);
    final var throwsAction = findThrowsAction(actions);
    assertThat(throwsAction.getTitle()).isEqualTo("Add 'throws SQLException' to method");

    final List<TextEdit> edits =
        throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits.get(0).getNewText()).isEqualTo(", SQLException");
  }

  @Test
  void codeAction_unreportedJavaLangException_addsThrowsWithoutImport() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.lang.Exception {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(2);
    final var throwsAction = findThrowsAction(actions);
    final List<TextEdit> edits =
        throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).isEqualTo(" throws Exception");
  }

  // --- DeclareVariable provider ---

  @Test
  void codeAction_variableRef_intAssignment_declaresLocalVariable() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { count = 0; }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Declare local variable 'count'");
    assertThat(action.getKind()).isEqualTo("quickfix");

    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).isEqualTo("int count");
  }

  @Test
  void codeAction_variableRef_stringAssignment_declaresLocalVariableWithoutImport() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { msg = "hello"; }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final List<TextEdit> edits =
        actions.get(0).getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).isEqualTo("String msg");
  }

  @Test
  void codeAction_variableRef_rhsOfLocalDecl_returnsEmpty() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            int x = unknownVar;
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_variableRef_fieldLevel_returnsEmpty() {
    // DeclareVariable is suppressed for field-level: field initializers are VariableTree,
    // not ExpressionStatementTree, so the provider correctly offers nothing.
    final var source =
        """
        package com.example;
        class Test {
          int x = unknownVar;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).isEmpty();
  }

  // --- Gap regression tests ---

  @Test
  void codeAction_unreportedException_fieldInitializerLambda_offersTryCatch() {
    // Gap 1: UNREPORTED_EXCEPTION inside a field-initializer lambda should offer
    // "Wrap in try/catch" instead of depending on an enclosing MethodTree.
    final var source =
        """
        package com.example;
        class Test {
          Runnable r = () -> { throw new java.io.IOException("x"); };
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Wrap in try/catch");
    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText())
        .contains("try {")
        .contains("throw new java.io.IOException(\"x\");")
        .contains("catch (java.io.IOException e)");
  }

  @Test
  void codeAction_unreportedException_methodBodyLambda_doesNotAddThrowsToOuterMethod() {
    // Gap 1 variant: UNREPORTED_EXCEPTION inside a lambda nested in a method body.
    // AddThrowsProvider must not offer "Add throws" to the outer method, because the exception
    // cannot escape the lambda boundary. Desired action is "Wrap in try/catch".
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = () -> { throw new java.io.IOException("x"); };
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Wrap in try/catch");
    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).doesNotContain("throws");
  }

  @Test
  void codeAction_unreportedException_anonymousClassMethod_offersTryCatch() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = new Runnable() {
              @Override
              public void run() {
                throw new java.io.IOException("x");
              }
            };
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Wrap in try/catch");
    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText())
        .contains("try {")
        .contains("throw new java.io.IOException(\"x\");")
        .contains("catch (java.io.IOException e)")
        .doesNotContain("throws");
  }

  @Test
  void compile_doesNotOverrideAbstract_setsMissingMethodImplPayload() {
    final var source =
        """
        package com.example;
        class Test implements Runnable { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.does.not.override.abstract");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.MISSING_METHOD_IMPL);
    assertThat(payload.name()).isEqualTo("Test");
  }

  // --- EG-002 regression ---

  @Test
  void codeAction_unreportedException_methodBody_offersBothWrapAndThrows() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { throw new java.io.IOException("x"); }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(2);
    assertThat(actions.stream().map(a -> a.getRight().getTitle()))
        .contains("Add 'throws IOException' to method", "Wrap in try/catch");
  }

  @Test
  void codeAction_unreportedException_lambdaBody_offersOnlyWrap() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = () -> { throw new java.io.IOException("x"); };
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).getRight().getTitle()).isEqualTo("Wrap in try/catch");
  }

  // --- MissingMethodImpl provider ---

  @Test
  void codeAction_missingMethodImpl_singleAbstractMethod_generatesStub() {
    final var source =
        """
        package com.example;
        class Test implements Runnable { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Implement abstract methods");
    assertThat(action.getKind()).isEqualTo("quickfix");

    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText())
        .contains("@Override")
        .contains("public void run()")
        .contains("throw new UnsupportedOperationException()");
  }

  @Test
  void codeAction_missingMethodImpl_multipleAbstractMethods_generatesAllStubs() {
    final var source =
        """
        package com.example;
        import java.util.Iterator;
        class Test implements Iterator<String> { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final List<TextEdit> edits =
        actions.get(0).getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    final String stubText = edits.get(0).getNewText();
    assertThat(stubText).contains("public boolean hasNext()").contains("public String next()");
  }

  // --- Replace-var provider (request-driven, CA-5) ---

  @Test
  void codeAction_varLocal_offersReplaceWithInferredType() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var s = "hello";
          }
        }
        """;
    // cursor on the `var` local declaration; no diagnostic drives this
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'String'");
  }

  @Test
  void codeAction_varGenericType_replacesWithGenericsAndAddsImport() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var list = new java.util.ArrayList<String>();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'ArrayList<String>'");
    final List<TextEdit> edits =
        actions.get(0).getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).anyMatch(e -> e.getNewText().contains("import java.util.ArrayList"));
  }

  @Test
  void codeAction_varPrimitive_replacesWithPrimitiveType() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var n = 42;
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'int'");
  }

  @Test
  void codeAction_varAnonymousClass_offersNoReplace() {
    // The inferred type is an anonymous class, which cannot be written explicitly, so keep `var`.
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var r = new Runnable() { public void run() {} };
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Replace 'var'"));
  }

  @Test
  void codeAction_varUpwardProjectedType_replacesWithProjectedType() {
    // `list.get(0)` on a `? extends Number` list yields a captured type that `var`
    // upward-projects
    // (JLS 14.4.1) to the denotable `Number`, so the refactor is offered with the projected type.
    final var source =
        """
        package com.example;
        class Test {
          void m(java.util.List<? extends Number> list) {
            var x = list.get(0);
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'Number'");
  }

  @Test
  void codeAction_explicitTypedLocal_offersNoReplace() {
    // A normal explicitly-typed local is not a `var`, so no refactor is offered.
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            String s = "hello";
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 11);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Replace 'var'"));
  }

  // --- Convert-to-var provider (request-driven) ---

  @Test
  void codeAction_diamondInitializer_convertsToVarAndFillsTypeArguments() {
    // The declared type is dropped, so the diamond must be filled from the inferred arguments —
    // otherwise `var` would infer `Object`. Covers one and two type arguments.
    final var source =
        """
        package com.example;
        import java.util.ArrayList;
        import java.util.HashMap;
        import java.util.List;
        import java.util.Map;
        class Test {
          void m() {
            List<String> ls = new ArrayList<>();
            Map<String, Integer> mm = new HashMap<>();
          }
        }
        """;

    assertThat(convertVarEdits(replaceVarActionsAt(source, 7, 4)))
        .extracting(TextEdit::getNewText)
        .containsExactlyInAnyOrder("var", "<String>");
    assertThat(convertVarEdits(replaceVarActionsAt(source, 8, 4)))
        .extracting(TextEdit::getNewText)
        .containsExactlyInAnyOrder("var", "<String, Integer>");
  }

  @Test
  void codeAction_losslessAndWideningLocals_convertToVar() {
    // A same-type primitive local and a reference-widening local both convert to a single `var`
    // edit; neither has a diamond to fill.
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            int a = 1;
            Object o = "x";
          }
        }
        """;

    assertThat(convertVarEdits(replaceVarActionsAt(source, 3, 8)))
        .extracting(TextEdit::getNewText)
        .containsExactly("var");
    assertThat(convertVarEdits(replaceVarActionsAt(source, 4, 11)))
        .extracting(TextEdit::getNewText)
        .containsExactly("var");
  }

  @Test
  void codeAction_ineligibleDeclarations_offerNoConvertToVar() {
    // Field (`var` illegal), primitive mismatch (`long` <- `int`), multi-declarator, lambda (no
    // standalone type), and an already-`var` local must all decline the conversion.
    final var source =
        """
        package com.example;
        class Test {
          String f = "x";
          void m() {
            long big = 1;
            int a = 1, b = 2;
            Runnable r = () -> {};
            var already = 3;
          }
        }
        """;

    for (final int[] at : new int[][] {{2, 9}, {4, 9}, {5, 8}, {6, 13}, {7, 8}}) {
      assertThat(rightTitles(replaceVarActionsAt(source, at[0], at[1])))
          .as("line %d col %d", at[0], at[1])
          .noneMatch(title -> title.equals("Convert to 'var'"));
    }
  }

  // --- Extract-variable provider (request-driven) ---

  @Test
  void codeAction_subExpression_extractsLocalAndReplacesOccurrence() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            System.out.println(compute(2) + 1);
          }
          int compute(int n) { return n; }
        }
        """;
    // select `compute(2)` inside the println argument
    final var actions = extractActionsSpanning(source, 3, 23, 3, 33);

    assertThat(rightTitles(actions)).contains("Extract variable 'compute'");
    final List<TextEdit> edits = extractEdits(actions);
    assertThat(edits).hasSize(2);
    assertThat(newTextAtLineStart(edits)).isEqualTo("int compute = compute(2);\n    ");
    assertThat(replacementText(edits)).isEqualTo("compute");
  }

  @Test
  void codeAction_genericTypedExpression_addsImport() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            use(make());
          }
          java.util.List<String> make() { return null; }
          void use(java.util.List<String> l) {}
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 8, 3, 14);

    assertThat(rightTitles(actions)).contains("Extract variable 'make'");
    final List<TextEdit> edits = extractEdits(actions);
    assertThat(edits).hasSize(3);
    assertThat(newTextAtLineStart(edits)).isEqualTo("List<String> make = make();\n    ");
    assertThat(edits).anyMatch(e -> e.getNewText().contains("import java.util.List"));
  }

  @Test
  void codeAction_emptyRangeInsideExpression_extractsEnclosingExpression() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            use(compute(2) + 1);
          }
          int compute(int n) { return n; }
          void use(int n) {}
        }
        """;
    // caret sitting inside `compute(2)` with no selection resolves to the enclosing call
    final var actions = replaceVarActionsAt(source, 3, 12);

    assertThat(rightTitles(actions)).contains("Extract variable 'compute'");
  }

  @Test
  void codeAction_extractVariable_derivesNameFromSelectedExpression() {
    final var newClass =
        """
        package com.example;
        class Test {
          void m() {
            use(new StringBuilder());
          }
          void use(StringBuilder b) {}
        }
        """;
    final var getter =
        """
        package com.example;
        class Test {
          void m() {
            use(getName().length());
          }
          String getName() { return ""; }
          void use(int n) {}
        }
        """;
    final var collision =
        """
        package com.example;
        class Test {
          void m() {
            int compute = 0;
            use(compute(2));
          }
          int compute(int n) { return n; }
          void use(int n) {}
        }
        """;
    final var fieldAccess =
        """
        package com.example;
        class Test {
          String label = "x";
          void m() {
            use(this.label);
          }
          void use(String s) {}
        }
        """;
    final List<ExtractCase> cases =
        List.of(
            new ExtractCase(
                "new-class names after its type",
                newClass,
                3,
                8,
                3,
                27,
                "Extract variable 'stringBuilder'"),
            new ExtractCase(
                "getter strips get/is prefix", getter, 3, 8, 3, 17, "Extract variable 'name'"),
            new ExtractCase(
                "collision with a local gets a numeric suffix",
                collision,
                4,
                8,
                4,
                18,
                "Extract variable 'compute1'"),
            new ExtractCase(
                "field read names after the field",
                fieldAccess,
                4,
                8,
                4,
                18,
                "Extract variable 'label'"));

    for (final ExtractCase c : cases) {
      final var actions =
          extractActionsSpanning(
              c.source(), c.startLine(), c.startChar(), c.endLine(), c.endChar());
      assertThat(rightTitles(actions)).as(c.label()).contains(c.expectedTitle());
    }
  }

  @Test
  void codeAction_extractVariable_unsafeSelection_offersNothing() {
    final var voidCall =
        """
        package com.example;
        class Test {
          void m() {
            run();
          }
          void run() {}
        }
        """;
    final List<ExtractCase> cases =
        List.of(new ExtractCase("void has nothing to declare", voidCall, 3, 4, 3, 9, null));

    for (final ExtractCase c : cases) {
      final var actions =
          extractActionsSpanning(
              c.source(), c.startLine(), c.startChar(), c.endLine(), c.endChar());
      assertThat(rightTitles(actions))
          .as(c.label())
          .noneMatch(t -> t.startsWith("Extract variable"));
    }
  }

  @Test
  void codeAction_extractVariable_atStatementStart_rewritesToCompilingCode() {
    // The declaration is inserted where the extracted expression begins, so the insert and the
    // replacement share an offset; they must apply in order.
    final var source =
        """
        package com.example;
        class Test {
          void m(StringBuilder sb) {
            sb.append("x").append("y");
          }
        }
        """;
    final String rewritten =
        applyEdits(source, extractEdits(extractActionsSpanning(source, 3, 4, 3, 18)));

    assertThat(rewritten)
        .contains("StringBuilder append = sb.append(\"x\");\n    append.append(\"y\");");
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extraction_lineWiseSelection_findsTheExpression() {
    // A line-wise selection (Neovim's `V`) takes in the indentation and the `;` around the code.
    final String max = "Extract variable 'max'";
    assertExtracts(
        "void m(String a, String b) {\n    int n =\n"
            + "«        Math.max(\n            a.length(),\n            b.length());»\n  }",
        max);
    assertExtracts(
        "void m(String a, String b) {\n    use(\n"
            + "        «Math.max(\n            a.length(),\n            b.length())»);\n  }",
        max);
    assertExtracts(
        "void m(String a, String b) {\n«    int n = Math.max(a.length(), b.length());»\n  }", max);
    assertExtracts(
        "int m(String a) {\n«    return a.trim().length();»\n  }", "Extract variable 'length'");
    assertExtracts(
        "void m() {\n    use(\n«        \"hello world\");»\n  }", "Extract constant 'HELLO_WORLD'");
    assertThat(
            rightTitles(markedActions(inTestClass("void m() {\n«    use(1);\n    use(2);»\n  }"))))
        .as("several statements")
        .noneMatch(t -> t.startsWith("Extract variable"));
  }

  @Test
  void codeAction_extractVariable_evaluatedOnlyInPlace_offersNothing() {
    final Map<String, String> cases =
        Map.of(
            "right of &&", "void m(String a) { if (a != null && «a.isEmpty()») { use(a); } }",
            "?: branch", "void m(String a) { use(ok(a) ? «a.trim()» : \"\"); }",
            "while condition", "void m(List<String> l) { while («l.isEmpty()») { l.add(\"x\"); } }",
            "for condition", "void m(List<String> l) { for (int i = 0; i < «l.size()»; i++) { } }",
            "assert", "void m(String a) { assert «a.isEmpty()»; }",
            "super argument",
                "static class B { B(int x) {} }\n  static class C extends B { C(String a) { super(«a.length()»); } }",
            "intersection type", "void m() { use(«List.of(1, \"a\")»); }",
            "captured wildcard", "void m(Object o) { use(«o.getClass()»); }",
            "earlier resource",
                "void m() throws java.io.IOException {\n    try (var in = new java.io.StringReader(\"x\");"
                    + " var r = new java.io.BufferedReader(«in»)) { }\n  }",
            "earlier loop variable", "void m() { for (int i = 0, j = «i + 1»; i < j; i++) { } }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .noneMatch(t -> t.startsWith("Extract variable")));
  }

  @Test
  void codeAction_extractVariable_evaluatedOnceBeforeStatement_rewritesToCompilingCode() {
    final String variable = "Extract variable";
    assertExtracts("void m(String a) { if («a.isEmpty()») { use(a); } }", variable);
    assertExtracts(
        "void m(Map<String, Integer> m) { for (String k : «m.keySet()») { use(k); } }", variable);
    assertExtracts("void m(String a) { switch («a.trim()») { default -> use(a); } }", variable);
    assertExtracts("void m() { use(«List.of(\"a\", \"b\")»); }", variable);
    assertExtracts("void m(List<List<? extends Number>> l) { use(«l»); }", variable);

    // replace-all: the occurrence the anchor statement always evaluates covers the one in a branch
    final var branch =
        inTestClass("void m(String a, boolean b) { use(«a.trim()»); if (b) { use(a.trim()); } }");
    assertThat(rightTitles(markedActions(branch))).anyMatch(t -> t.contains("replace all 2"));
    final var onlyInBranches =
        inTestClass(
            "void m(String a, boolean b) {\n    if (b) { use(«a.trim()»); } else { use(a.trim()); }\n  }");
    assertThat(rightTitles(markedActions(onlyInBranches)))
        .noneMatch(t -> t.startsWith("Extract variable") && t.contains("replace all"));
  }

  @Test
  void codeAction_extractVariable_outsideABlock_declaresWhereItFits() {
    final String variable = "Extract variable";
    assertExtracts(
        "void m(String a) { switch (a) { case \"x\": use(«a.trim()»); break; default: break; } }",
        variable);
    assertExtracts(
        "void m(java.io.File f) throws java.io.IOException {\n"
            + "    try (var in = new java.io.FileInputStream(«f.getPath()»)) { use(in); }\n  }",
        variable);
    assertExtracts(
        "void m() { for (int i = «Integer.parseInt(\"1\")»; i < 3; i++) { use(i); } }", variable);
    assertExtracts("void m(String a) { «a.trim()»; }", "Extract variable 'trim'");
  }

  @Test
  void codeAction_extractVariable_bodyWithoutRoom_becomesABlock() {
    // A lambda returns the value only when its interface method does, a switch expression's rule
    // yields it: either mistake would not compile.
    final String variable = "Extract variable";
    assertExtracts("void m(List<String> l) { l.forEach(x -> use(«x.trim()»)); }", variable);
    assertExtracts(
        "List<Integer> m(List<String> l) {\n    return l.stream().map(x -> «x.length()» * 2)"
            + ".collect(java.util.stream.Collectors.toList());\n  }",
        variable);
    assertExtracts(
        "int m(String a) { return switch (a) { case \"x\" -> «a.length()» + 1; default -> 0; }; }",
        variable);
    assertExtracts(
        "void m(String a) { switch (a) { case \"x\" -> use(«a.trim()»); default -> use(a); } }",
        variable);
    assertExtracts("void m(String a, boolean b) { if (b) use(«a.trim()»); }", variable);
    assertExtracts("void m(List<String> l) { for (String x : l) use(«x.trim()»); }", variable);

    final String lambda =
        inTestClass("void m(List<String> l) { l.forEach(x -> use(«x.trim()»)); }");
    assertThat(applyEdits(unmarked(lambda), extractEdits(markedActions(lambda))))
        .contains("x -> {\n    String trim = x.trim();\n    use(trim);\n  });");

    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "boolean m(String a) { return java.util.Optional.of(a).filter(x -> x != null && «x.isEmpty()»).isPresent(); }"))))
        .as("pinned inside the body")
        .noneMatch(t -> t.startsWith(variable));
    assertThat(
            rightTitles(
                markedActions(inTestClass("void m(String a, boolean b) { if (b) «a.trim()»; }"))))
        .as("a whole statement alone in a body")
        .noneMatch(t -> t.startsWith(variable));
  }

  @Test
  void codeAction_annotatedDeclarations_insertInsideTheBody() {
    // A `{` in an annotation argument is not the body's: the insert lands inside the body.
    final var annotatedClass =
        """
        package com.example;
        @SuppressWarnings({"unused"})
        class Test {
          void m() { use(«"hello world"»); }
          void use(Object x) {}
        }
        """;
    final var annotatedComponent =
        """
        package com.example;
        record Test(@SuppressWarnings({"unused"}) int a) {
          void m() { use(«"hello world"»); }
          void use(Object x) {}
        }
        """;
    for (final String source : List.of(annotatedClass, annotatedComponent)) {
      final String rewritten = applyEdits(unmarked(source), constantEdits(markedActions(source)));
      assertThat(rewritten)
          .contains("{\n  private static final String HELLO_WORLD = \"hello world\";\n  void m()");
      assertCompilesClean(rewritten);
    }

    final var annotatedConstructor =
        """
        package com.example;
        @SuppressWarnings({"unused"})
        class Widget {
          private final String id;
          @SuppressWarnings({"unused"})
          Widget() {}
        }
        """;
    final String rewritten =
        applyEdits(
            annotatedConstructor, addCtorEdits(replaceVarActionsAt(annotatedConstructor, 3, 23)));
    assertThat(rewritten).contains("Widget(final String id) {\n    this.id = id;\n  }");
    assertCompilesClean(rewritten);
  }

  // --- Extract-method provider ---

  @Test
  void codeAction_extractMethod_expression_becomesPrivateMethodAfterItsMember() {
    final var source =
        inTestClass("int m(final String a) {\n    return «a.trim().length() * 2»;\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              int m(final String a) {
                return extracted(a);
              }

              private int extracted(final String a) {
                return a.trim().length() * 2;
              }
            """);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extractMethod_contexts_rewriteToCompilingCode() {
    final String method = "Extract method";
    // static contexts: a static method, a static field, a this(...) argument
    assertExtracts("static int s(String a) { return «a.length() + 1»; }", method);
    assertExtracts("static int g = «Integer.parseInt(\"4\") + 1»;", method);
    assertExtracts("Test(int x) {}\n  Test(String s) { this(«s.length() + 1»); }", method);
    // a lambda's parameter and a captured local become parameters; a field initializer works too
    assertExtracts("void m(List<String> l, String p) { l.forEach(x -> use(«x + p»)); }", method);
    assertExtracts("int base = 2;\n  int f = «base * 3»;", method);
    // inside an anonymous class the method joins that class, where its field is visible
    assertExtracts(
        "Runnable m() { return new Runnable() { int n = 1; public void run() { use(«n + 1»); } }; }",
        method);
    // checked exceptions as instantiated at the call, and every signature type imported
    assertExtracts(
        "String m(Optional<String> o) throws java.io.IOException {\n"
            + "    return «o.orElseThrow(java.io.IOException::new)»;\n  }",
        method);
    assertExtracts(
        "int m(Map<String, java.util.concurrent.atomic.AtomicInteger> c) { return «c.size() + 1»; }",
        method);
    // a nested type the new method's class cannot see by its simple name is written from its
    // top-level type (bare `Module` would be java.lang.Module)
    assertThat(
            assertExtracts(
                "static class Route { static class Module {} }\n"
                    + "  int m(Route.Module x) { return «x.hashCode() + 1»; }",
                method))
        .contains("private int extracted(Route.Module x)");
    // two nested types of one simple name stay apart, each from its enclosing type
    assertThat(
            assertExtracts(
                "static class A { static class Builder {} }\n  static class B { static class Builder {} }\n"
                    + "  int m(A.Builder a, B.Builder b) { return «a.hashCode() + b.hashCode()»; }",
                method))
        .contains("private int extracted(A.Builder a, B.Builder b)");
    // a private type of the file itself is used by its name, never imported
    assertExtracts("private record P(int v) {}\n  int m(P p) { return «p.v() + 1»; }", method);
    // the method's type variables the signature needs, with those their bounds name
    assertThat(
            assertExtracts(
                "<A, K, V extends Comparable<K>> V m(List<V> l, A a) { use(a); return «l.get(0)»; }",
                method))
        .contains("private <K, V extends Comparable<K>> V extracted(List<V> l)");
    // a class's type variable in an instance method; a name an inherited method already has
    assertExtracts("static class Box<E> { E e; E m(E x) { return «x != null ? x : e»; } }", method);
    assertExtracts(
        "static class Base { void extracted() {} }\n"
            + "  static class Sub extends Base { int m(String a) { return «a.length() + 1»; } }",
        "Extract method 'extracted1'");
  }

  @Test
  void codeAction_extractMethod_notMovableOrPointless_offersNothing() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("void", "void m(String a) { «use(a)»; }");
    cases.put("writes a local used later", "int m(int i) { int j = «i++»; return i + j; }");
    cases.put("non-denotable", "void m() { use(«List.of(1, \"a\")»); }");
    cases.put("constant", "int m() { return «1 + 2»; }");
    cases.put(
        "enum case label",
        "enum E { A, B }\n  void m(E e) { switch (e) { case «A»: break; default: break; } }");
    cases.put(
        "annotation value",
        "@interface Ann { Class<?> value(); }\n  @Ann(«String.class») void m() {}");
    cases.put("interface", "interface I { default int m(String a) { return «a.length() + 1»; } }");
    cases.put(
        "static with class type variable",
        "static class Box<E> {\n    Box(E e) { this(«List.of(e)»); }\n    Box(List<E> l) {}\n  }");
    cases.put("local class", "int m() { class L { int v() { return 1; } } return «new L().v()»; }");
    cases.put("enum constant creation", "enum E { A«(\"a\")»; E(String s) {} }");
    cases.put(
        "type variable only the return type names",
        "<T> void m() { final var l = «new ArrayList<T>()»; List<T> t = l; use(t); }");
    cases.put("assignment target", "void m(int[] a) { «a[0]» = 1; }");
    cases.put("this", "Object m() { return «this»; }");
    cases.put("a bare variable", "Object m(Object o) { return «o»; }");
    cases.put("enum constant argument", "enum E { A(«\"a\".trim()»); E(String s) {} }");
    cases.put(
        "inner class's super(...) argument",
        "int f = 1;\n  static class Base { Base(int x) {} }\n"
            + "  class Inner extends Base { Inner() { super(«f + 1»); } }");
    cases.put(
        "a local type in the signature",
        "void m() { record P(int v) {} final P p = new P(1); use(«p.v() + 1»); }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .noneMatch(t -> t.startsWith("Extract method")));
  }

  @Test
  void codeAction_extractMethod_statements_becomeVoidMethod() {
    final var source =
        inTestClass(
            "void m(String a) {\n    String t = a.trim();\n«    use(t);\n    use(t.length());»\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              void m(String a) {
                String t = a.trim();
                extracted(t);
              }

              private void extracted(String t) {
                use(t);
                use(t.length());
              }
            """);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extractMethod_declarationUsedLater_movesToTheCall() {
    final var source =
        inTestClass(
            "void m(String a) {\n«    final String t = a.trim();\n    use(t);»\n    use(t.length());\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              void m(String a) {
                final String t = extracted(a);
                use(t.length());
              }

              private String extracted(String a) {
                final String t = a.trim();
                use(t);
                return t;
              }
            """);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extractMethod_statementsReturningOnEveryPath_returnTheCall() {
    final var source =
        inTestClass(
            "int m(String a) {\n    use(a);\n«    if (a.isEmpty()) {\n      return 0;\n    }\n"
                + "    return a.length();»\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              int m(String a) {
                use(a);
                return extracted(a);
              }

              private int extracted(String a) {
                if (a.isEmpty()) {
                  return 0;
                }
                return a.length();
              }
            """);
    assertCompilesClean(rewritten);

    // a lambda's return is its own: the statements stay, though the lambda around them may move
    assertThat(
            assertExtracts(
                "java.util.function.Function<String, Integer> f = s -> { «use(s);\n    return 1;» };",
                "Extract method"))
        .doesNotContain("return extracted(");
  }

  @Test
  void codeAction_extractMethod_statementContexts_rewriteToCompilingCode() {
    final String method = "Extract method";
    assertExtracts(
        "void m(List<String> l) { for (String s : l) { «use(s);\n    use(s.trim());» } }", method);
    // no value before the region: the new method declares the local itself
    assertExtracts(
        "void m(boolean b) {\n    String line;\n«    line = String.valueOf(b);\n    use(line);»\n  }",
        method);
    // a bare array initializer is returned with its array creation
    assertThat(assertExtracts("String[] names = «{\"a\", \"b\"}»;", method))
        .contains("return new String[] {\"a\", \"b\"};");
    // the warnings the enclosing method suppresses stay suppressed
    assertThat(
            assertExtracts(
                "@SuppressWarnings(\"deprecation\") int m(java.util.Date d) { return «d.getYear() + 1»; }",
                method))
        .contains("  @SuppressWarnings(\"deprecation\")\n  private int extracted(");
    // a local declaration's suppressions join the method's, in one annotation
    assertThat(
            assertExtracts(
                "@SuppressWarnings(\"deprecation\") void m(Object o) {\n"
                    + "    @SuppressWarnings(\"unchecked\") final Map<String, Object> x = "
                    + "«(Map<String, Object>) o»;\n    use(x);\n  }",
                method))
        .contains(
            "  @SuppressWarnings({\"unchecked\", \"deprecation\"})\n  private Map<String, Object> extracted(");
    // the file's final parameters are mirrored, except on one the code assigns
    assertThat(
            assertExtracts(
                "void m(final String a) {\n    int n = a.length();\n«    n = n * 2;\n    use(a);»\n"
                    + "    use(n);\n  }",
                method))
        .contains("private int extracted(int n, final String a)");
    // a local the new method declares has its type imported, however the source spelled it
    assertThat(
            assertExtracts(
                "void m() {\n    java.util.concurrent.atomic.AtomicLong n;\n"
                    + "«    n = new java.util.concurrent.atomic.AtomicLong();\n    use(n);»\n  }",
                method))
        .contains("import java.util.concurrent.atomic.AtomicLong;", "    AtomicLong n;");
    // a loop with its own break moves whole; case statements without their break
    assertExtracts(
        "void m(List<String> l) { «for (String s : l) { if (s.isEmpty()) break; use(s); }» }",
        method);
    assertExtracts(
        "void m(int k) { switch (k) { case 1: «use(k);\n    use(k + 1);» break; default: break; } }",
        method);
    // checked exceptions: declared when thrown, not when caught inside
    assertExtracts(
        "void m(java.io.File f) throws java.io.IOException { «new java.io.FileInputStream(f).close();» }",
        method);
    assertExtracts(
        "void m(java.io.File f) {\n    «try { new java.io.FileInputStream(f).close(); }"
            + " catch (java.io.IOException e) { use(e); }»\n  }",
        method);
    assertExtracts(
        "void m(List<String> l, String p) { l.forEach(x -> { «use(x + p);» }); }", method);
    assertExtracts("static void s(String a) { «System.out.println(a);» }", method);
    // an earlier local assigned at the top level comes back out, its old value going in only when
    // read before; read earlier in the loop, the next iteration needs it
    assertThat(
            assertExtracts(
                "void m(String a) {\n    int n = 0;\n«    n = a.length();\n    use(n);»\n    use(n);\n  }",
                method))
        .contains(
            "n = extracted(a);", "    int n;\n    n = a.length();\n    use(n);\n    return n;");
    // a lone assignment extracts the value it stores
    assertThat(assertExtracts("void m(String a) { int n = 0; «n = a.length();» use(n); }", method))
        .contains("n = extracted(a);", "return a.length();");
    assertThat(assertExtracts("void m(int n) { «n = n * 2;\n    use(n);» use(n + 1); }", method))
        .contains("n = extracted(n);");
    assertThat(
            assertExtracts(
                "void m(List<String> l) { int n = 0; for (String s : l) { use(n); «n = s.length();» } }",
                method))
        .contains("n = extracted(s);");

    // a declaration used later is not a statement region yet; its value still is an expression
    final var declaration =
        inTestClass("void m(String a) {\n«    String t = a.trim();»\n    use(t);\n  }");
    assertThat(
            applyEdits(
                unmarked(declaration),
                editsOfActionStartingWith(markedActions(declaration), "Extract method")))
        .contains("String t = extracted(a);");
  }

  @Test
  void codeAction_extractMethod_statementsChangingControlOrState_offerNothing() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("return on some paths", "int m(String a) { «if (a == null) return 0;» return 1; }");
    cases.put("return from a void method", "void m(String a) { «use(a);\n    return;» }");
    cases.put(
        "continue out",
        "void m(List<String> l) { for (String s : l) { «if (s.isEmpty()) continue;» use(s); } }");
    cases.put(
        "case with its break",
        "void m(int k) { switch (k) { case 1: «use(k); break;» default: break; } }");
    cases.put("never completes", "void m() { «throw new IllegalStateException();» }");
    cases.put(
        "never completes, in a switch expression",
        "int m(int k) { return switch (k) { case 1 -> { «throw new IllegalStateException();» }"
            + " default -> 0; }; }");
    cases.put(
        "assigned conditionally",
        "void m(String a) { int n = 0; «if (a.isEmpty()) n = 1;» use(n); }");
    cases.put("two values", "void m() { «int x = 1;\n    int y = 2;» use(x + y); }");
    cases.put(
        "final field",
        "final String name;\n  Test(String n) { «this.name = n.trim();\n    use(n);» }");
    cases.put("this(...)", "Test(int x) {}\n  Test() { «this(1);» }");
    cases.put("local class", "void m() { class L {} «use(new L());» }");
    cases.put("local class used afterwards", "void m() { «record P(int v) {}» use(new P(1)); }");
    cases.put(
        "rethrow",
        "void m(java.io.File f) throws java.io.IOException {\n"
            + "    try { f.getCanonicalPath(); } catch (java.io.IOException e) { «throw e;» }\n  }");
    cases.put("two blocks", "void m(boolean b) { «use(1); if (b) { use(2);» } }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .noneMatch(t -> t.startsWith("Extract method")));
  }

  @Test
  void codeAction_extractMethod_copies_replacedInTheClassOwnMembers() {
    // copies in another member count, in a nested class do not; one in a static member makes the
    // replace-all method static while the single one stays an instance method
    final String source =
        inTestClass(
            "static String NAME = \"x\";\n"
                + "  void m() { use(«NAME.trim()»); }\n"
                + "  static void s() { System.out.println(NAME.trim()); }\n"
                + "  static class N { void n() { System.out.println(NAME.trim()); } }");
    final var actions = markedActions(source);
    assertThat(rightTitles(actions))
        .contains(
            "Extract method 'extracted'", "Extract method 'extracted' (replace all 2 occurrences)");
    final String all =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(actions, "Extract method 'extracted' (replace"));
    assertThat(all)
        .contains("private static String extracted()")
        .contains("System.out.println(extracted());");
    assertCompilesClean(all);
    assertCompilesClean(
        applyEdits(
            unmarked(source), editsOfActionStartingWith(actions, "Extract method 'extracted'")));

    // an if's parentheses are the statement's: replaced, `if extracted()` would not parse
    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "boolean m(Object o) { boolean b = «(o == null)»; if (o == null) { use(o); } return b; }"))))
        .contains("Extract method 'extracted'")
        .noneMatch(t -> t.startsWith("Extract method 'extracted' (replace all"));
    // a copy in an annotation value is not a copy: it needs a constant, not a call
    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "@interface Ann { Class<?> value(); }\n"
                            + "  Object m() { return «String.class»; }\n  @Ann(String.class) void n() {}"))))
        .noneMatch(t -> t.startsWith("Extract method 'extracted' (replace all"));
    // a poly expression typed differently elsewhere is not a copy: one method returns one type
    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "void m() { List<String> a = «List.of()»; List<Integer> b = List.of(); use(a); use(b); }"))))
        .contains("Extract method 'extracted'")
        .noneMatch(t -> t.startsWith("Extract method 'extracted' (replace all"));

    // a copy that is an assignment's target is not a copy
    assertThat(
            rightTitles(markedActions(inTestClass("void m(int[] a) { use(«a[0]»); a[0] = 1; }"))))
        .contains("Extract method 'extracted'")
        .noneMatch(t -> t.contains("replace all"));
  }

  @Test
  void codeAction_extractMethod_typeNamedLikeAnotherInTheFile_writtenQualified()
      throws IOException {
    // the simple name is taken by an import, by a type the file declares, or by one of its package
    final String imported =
        """
        package com.example;
        import java.sql.Date;
        class Test {
          Date sql;
          long m(java.util.Date d) { return «d.getTime() + 1»; }
        }
        """;
    final String declared =
        inTestClass(
            "static class Date {}\n  long m(java.util.Date d) { return «d.getTime() + 1»; }");
    final Path sibling = tmp.resolve("Date.java");
    Files.writeString(sibling, "package com.example;\npublic class Date {}\n");
    final Path classDir = tmp.resolve("sibling-classes");
    TestCompiler.compileToDir(classDir, sibling);
    session.close();
    session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));
    final String samePackage =
        inTestClass("long m(java.util.Date d) { return «d.getTime() + 1»; }");
    for (final String source : List.of(imported, declared, samePackage)) {
      final String rewritten =
          applyEdits(
              unmarked(source), editsOfActionStartingWith(markedActions(source), "Extract method"));
      assertThat(rewritten)
          .contains("private long extracted(java.util.Date d)")
          .doesNotContain("import java.util.Date;");
      assertCompilesClean(rewritten);
    }
  }

  @Test
  void codeAction_extractMethod_typeNameOfTheFile_keptOrRefusedWhenUnwritable() {
    // a nested type the file imports keeps the name the file uses
    final String imported =
        """
        package com.example;
        import java.util.Map.Entry;
        class Test {
          int m(Entry<String, String> e) { return «e.hashCode() + 1»; }
        }
        """;
    final String rewritten =
        applyEdits(
            unmarked(imported),
            editsOfActionStartingWith(markedActions(imported), "Extract method"));
    assertThat(rewritten).contains("private int extracted(Entry<String, String> e)");
    assertCompilesClean(rewritten);

    // another class's private nested type, returned by its method, cannot be written here
    final String hidden =
        """
        package com.example;
        class Other {
          private static class Hidden {}
          static Hidden make() { return new Hidden(); }
        }
        class Test {
          Object m() { return «Other.make()»; }
        }
        """;
    assertThat(rightTitles(markedActions(hidden))).noneMatch(t -> t.startsWith("Extract method"));
  }

  @Test
  void codeAction_extractMethod_statementCopies_calledWithTheirOwnLocals() {
    // a copy in another method, its locals and its declaration named differently
    final String source =
        inTestClass(
            "void m(String a) {\n«    final String t = a.trim();\n    use(t);»\n    use(t.length());\n  }\n"
                + "  void n(String b) {\n    final String u = b.trim();\n    use(u);\n"
                + "    use(u.isEmpty());\n  }");
    final var actions = markedActions(source);
    assertThat(rightTitles(actions))
        .contains("Extract method 'extracted' (replace all 2 occurrences)");
    final String all =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(actions, "Extract method 'extracted' (replace"));
    assertThat(all)
        .contains(
            """
              void m(String a) {
                final String t = extracted(a);
                use(t.length());
              }
            """,
            """
              void n(String b) {
                final String u = extracted(b);
                use(u.isEmpty());
              }
            """);
    assertCompilesClean(all);
  }

  @Test
  void codeAction_extractMethod_statementLookalikes_notReplaced() {
    final Map<String, String> cases =
        Map.of(
            "a local of another type",
            "void m(String a) { «use(a);\n    use(a + 1);» }\n  void n(int b) { use(b); use(b + 1); }",
            "two locals standing for one",
            "void m(String a, String b) { «use(a);\n    use(b);» }\n  void n(String c) { use(c); use(c); }",
            "overlapping the selection",
            "void m(String a) { «use(a);\n    use(a);» use(a); }",
            "a value used after the copy",
            "void m(String a) { «int x = a.length();\n    use(x);» }\n"
                + "  void n(String b) { int y = b.length(); use(y); use(y); }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .contains("Extract method 'extracted'")
                .noneMatch(t -> t.contains("replace all")));
  }

  // Asserts the `«…»` selection in the member offers an action titled with the prefix, and that
  // applying it leaves code that compiles; returns that code.
  private String assertExtracts(final String member, final String titlePrefix) {
    final String source = inTestClass(member);
    final var actions = markedActions(source);
    assertThat(rightTitles(actions)).as(member).anyMatch(t -> t.startsWith(titlePrefix));
    final String rewritten =
        applyEdits(unmarked(source), editsOfActionStartingWith(actions, titlePrefix));
    assertCompilesClean(rewritten);
    return rewritten;
  }

  private static String inTestClass(final String member) {
    return """
        package com.example;
        import java.util.*;
        class Test {
          %s
          void use(Object x) {}
          boolean ok(Object x) { return x != null; }
        }
        """
        .formatted(member);
  }

  // Code actions for the selection `«…»` marks in the source (the marks removed).
  private List<Either<Command, CodeAction>> markedActions(final String marked) {
    final int start = marked.indexOf('«');
    final int end = marked.indexOf('»') - 1;
    final String source = unmarked(marked);
    final Position from = positionOf(source, start);
    final Position to = positionOf(source, end);
    return extractActionsSpanning(
        source, from.getLine(), from.getCharacter(), to.getLine(), to.getCharacter());
  }

  private static String unmarked(final String marked) {
    return marked.replace("«", "").replace("»", "");
  }

  private static Position positionOf(final String source, final int offset) {
    final int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
    final int line = (int) source.substring(0, lineStart).chars().filter(c -> c == '\n').count();
    return new Position(line, offset - lineStart);
  }

  private record ExtractCase(
      String label,
      String source,
      int startLine,
      int startChar,
      int endLine,
      int endChar,
      String expectedTitle) {}

  @Test
  void codeAction_staticCallReceiver_extractsCallNotTypeReference() {
    // A caret on the type name of a static call must not extract the type reference, which produced
    // uncompilable `Factory factory = Factory;`. It climbs to the enclosing value expression, the
    // call: a whole-statement call becomes its declaration, a sub-expression call is extracted.
    final var wholeStatement =
        """
        package com.example;
        class Test {
          void m() {
            Factory.create();
          }
          static class Factory { static String create() { return ""; } }
        }
        """;
    // caret on the `F` of `Factory`: the whole statement becomes the declaration
    assertThat(applyEdits(wholeStatement, extractEdits(replaceVarActionsAt(wholeStatement, 3, 4))))
        .contains("String create = Factory.create();");

    final var subExpression =
        """
        package com.example;
        class Test {
          void m() {
            use(Factory.create());
          }
          void use(String s) {}
          static class Factory { static String create() { return ""; } }
        }
        """;
    final var actions = replaceVarActionsAt(subExpression, 3, 8);
    assertThat(rightTitles(actions)).contains("Extract variable 'create'");
    assertThat(newTextAtLineStart(extractEdits(actions)))
        .isEqualTo("String create = Factory.create();\n    ");
  }

  // --- Extract-constant provider ---

  @Test
  void codeAction_stringLiteral_extractsConstantNamedFromContent() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            use("hello world");
          }
          void use(String s) {}
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 8, 3, 21);

    assertThat(rightTitles(actions)).contains("Extract constant 'HELLO_WORLD'");
    final List<TextEdit> edits = constantEdits(actions);
    assertThat(newTextAtLineStart(edits))
        .startsWith("private static final String HELLO_WORLD = \"hello world\";");
    assertThat(replacementText(edits)).isEqualTo("HELLO_WORLD");
  }

  @Test
  void codeAction_numericLiteral_extractsConstantWithDefaultName() {
    final var source =
        """
        package com.example;
        class Test {
          int m() {
            return 42;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 13);

    assertThat(rightTitles(actions)).contains("Extract constant 'CONSTANT'");
    assertThat(newTextAtLineStart(constantEdits(actions)))
        .startsWith("private static final int CONSTANT = 42;");
  }

  @Test
  void codeAction_nonConstantExpression_offersNoConstant() {
    // a method call is not a compile-time constant — extract variable is still offered, constant
    // not
    final var source =
        """
        package com.example;
        class Test {
          int m() {
            return compute();
          }
          int compute() { return 1; }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 20);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract variable"));
    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract constant"));
  }

  @Test
  void codeAction_repeatedLiteral_offersReplaceAllConstant() {
    final var source =
        """
        package com.example;
        class Test {
          int a() {
            return 7;
          }
          int b() {
            return 7 + 7;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 12);

    assertThat(rightTitles(actions))
        .contains(
            "Extract constant 'CONSTANT'",
            "Extract constant 'CONSTANT' (replace all 3 occurrences)");
  }

  // --- Extract-field provider ---

  @Test
  void codeAction_expressionReadingOnlyFields_extractsInstanceField() {
    final var source =
        """
        package com.example;
        class Test {
          private final int base = compute();
          int m() {
            return base * 2;
          }
          int compute() { return 5; }
        }
        """;
    final var actions = extractActionsSpanning(source, 4, 11, 4, 19);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract field"));
    final List<TextEdit> edits = fieldEdits(actions);
    final String name = replacementText(edits);
    assertThat(insertedDecl(edits))
        .startsWith("private final int ")
        .contains(name)
        .endsWith("= base * 2;");
    assertThat(insertLine(edits)).isEqualTo(2);
  }

  @Test
  void codeAction_wholeStatementExpression_offersNoField() {
    // Extracting the whole expression of an expression-statement into a field would replace the
    // statement's only expression with a bare reference, leaving the illegal statement `name;`.
    // Mirror the extract-variable guard and decline. Regression: it emitted the orphaned `name;`.
    final var source =
        """
        package com.example;
        class Test {
          private final String existing = "";
          void m() {
            Factory.create();
          }
          static class Factory { static String create() { return ""; } }
        }
        """;
    final var actions = extractActionsSpanning(source, 4, 4, 4, 20);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  @Test
  void codeAction_expressionReadingParameter_offersNoField() {
    final var source =
        """
        package com.example;
        class Test {
          int m(int x) {
            return x * 2;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 16);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract variable"));
    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  @Test
  void codeAction_staticContext_offersNoField() {
    final var source =
        """
        package com.example;
        class Test {
          static int m() {
            return compute() + 1;
          }
          static int compute() { return 1; }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 24);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract variable"));
    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  @Test
  void codeAction_repeatedFieldExpression_offersReplaceAllField() {
    final var source =
        """
        package com.example;
        class Test {
          private final int base = compute();
          int a() {
            return base + 1;
          }
          int b() {
            return base + 1;
          }
          int compute() { return 5; }
        }
        """;
    final var actions = extractActionsSpanning(source, 4, 11, 4, 19);

    assertThat(rightTitles(actions))
        .anyMatch(t -> t.startsWith("Extract field") && t.contains("replace all 2 occurrences"));
  }

  @Test
  void codeAction_recordBody_offersNoField() {
    final var source =
        """
        package com.example;
        record Test(int base) {
          int m() {
            return base() * 2;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 21);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  // --- Extract-variable: replace all occurrences ---

  @Test
  void codeAction_repeatedExpression_offersReplaceAllOccurrences() {
    final var source =
        """
        package com.example;
        class Test {
          void m(Config config) {
            use(config.name());
            use(config.name());
            use(config.name());
          }
          void use(String s) {}
        }
        class Config { String name() { return ""; } }
        """;
    // select the first `config.name()`
    final var actions = extractActionsSpanning(source, 3, 8, 3, 21);

    assertThat(rightTitles(actions))
        .contains("Extract variable 'name'", "Extract variable 'name' (replace all 3 occurrences)");
    final List<TextEdit> edits = replaceAllEdits(actions);
    assertThat(edits).hasSize(4); // one declaration + three replacements
    assertThat(edits).filteredOn(e -> e.getNewText().equals("name")).hasSize(3);
    assertThat(newTextAtLineStart(edits)).isEqualTo("String name = config.name();\n    ");
  }

  @Test
  void codeAction_differentReceivers_doesNotMergeOccurrences() {
    // a.name() and b.name() spell the same but resolve to different receiver elements, so semantic
    // equality refuses to merge them — only the base single-occurrence action is offered.
    final var source =
        """
        package com.example;
        class Test {
          void m(Config a, Config b) {
            use(a.name());
            use(b.name());
          }
          void use(String s) {}
        }
        class Config { String name() { return ""; } }
        """;
    final var actions = extractActionsSpanning(source, 3, 8, 3, 16);

    assertThat(rightTitles(actions)).contains("Extract variable 'name'");
    assertThat(rightTitles(actions))
        .noneMatch(t -> t.startsWith("Extract variable") && t.contains("replace all"));
  }

  @Test
  void codeAction_readReassignedBetweenOccurrences_refusesReplaceAll() {
    // `x + 1` occurs twice, but `x` is reassigned between them, so collapsing would capture a stale
    // value — replace-all is refused; the base extraction of the first occurrence still stands.
    final var source =
        """
        package com.example;
        class Test {
          int m(int x) {
            int a = x + 1;
            x = 5;
            int b = x + 1;
            return a + b;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 12, 3, 17);

    assertThat(rightTitles(actions)).contains("Extract variable 'value'");
    assertThat(rightTitles(actions))
        .noneMatch(t -> t.startsWith("Extract variable") && t.contains("replace all"));
  }

  @Test
  void codeAction_replaceAll_insertsBeforeEarliestOccurrenceStatement() {
    // The anchor is the earliest statement that contains an occurrence, not the first statement of
    // the method — the unrelated leading statement must not move the declaration up.
    final var source =
        """
        package com.example;
        class Test {
          void m(Config config) {
            int unrelated = 0;
            use(config.name());
            use(config.name());
          }
          void use(String s) {}
        }
        class Config { String name() { return ""; } }
        """;
    final var actions = extractActionsSpanning(source, 4, 8, 4, 21);

    final List<TextEdit> edits = replaceAllEdits(actions);
    final TextEdit insert =
        edits.stream()
            .filter(e -> e.getNewText().startsWith("String name"))
            .findFirst()
            .orElseThrow();
    assertThat(insert.getRange().getStart().getLine()).isEqualTo(4);
  }

  // --- Try-with-resources provider (request-driven) ---

  @Test
  void codeAction_autoCloseableDeclaration_wrapsFollowingStatements() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r.read();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(rightTitles(actions)).contains("Surround with try-with-resources");
    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n      r.read();\n    }");
  }

  @Test
  void codeAction_declarationAsLastStatement_wrapsWithEmptyBody() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n    }");
  }

  @Test
  void codeAction_fourSpaceIndent_bodyUsesInferredIndentStep() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
            void m() throws Exception {
                FileReader r = new FileReader("x");
                r.read();
            }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 8);

    // step inferred as 4 spaces, so the body lands at 12 columns.
    assertThat(tryWithResourcesEdit(actions)).contains("\n            r.read();\n");
  }

  @Test
  void codeAction_nonAutoCloseableDeclaration_notOffered() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            String s = "x";
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 3, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_methodReferenceAssignedToVar_notOffered() {
    // A method reference cannot target `var`, so `x` has an error type; javac treats error types as
    // assignable to everything, which must not surface try-with-resources on non-compiling code.
    final var source =
        """
        package com.example;
        import java.time.Clock;
        class Test {
          void m() {
            final var x = Clock::systemUTC;
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 4, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_declarationWithoutInitializer_notOffered() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() {
            FileReader r;
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 4, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_reassignedResource_notOffered() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r = new FileReader("y");
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 4, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_trailingCloseCall_isDroppedFromBody() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r.read();
            r.close();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n      r.read();\n    }");
  }

  @Test
  void codeAction_closeIsOnlyStatement_wrapsWithEmptyBody() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r.close();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n    }");
  }

  // --- Add-constructor-parameter provider (request-driven, CA-10) ---

  @Test
  void codeAction_finalBlankField_multipleConstructors_bindsEach() {
    // One empty and one one-arg constructor cover both parameter-insertion forms (into an empty and
    // a non-empty list) plus the binding into each body, so no separate single-constructor case is
    // needed.
    final var source =
        """
        package com.example;
        class Box {
          private final int size;
          Box() {
          }
          Box(String label) {
          }
        }
        """;
    // caret on the `size` field
    final var actions = replaceVarActionsAt(source, 2, 20);

    assertThat(rightTitles(actions)).contains("Add constructor parameter 'size'");
    final String rewritten = applyEdits(source, addCtorEdits(actions));
    assertThat(rewritten)
        .contains("Box(final int size)")
        .contains("Box(String label, final int size)");
    assertThat(count(rewritten, "this.size = size;")).isEqualTo(2);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_finalBlankField_delegatingConstructor_forwardsArgument() {
    final var source =
        """
        package com.example;
        class Point {
          private final int x;
          Point() {
            this(0);
          }
          Point(int start) {
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 2, 20);

    final String rewritten = applyEdits(source, addCtorEdits(actions));
    // The delegating constructor forwards the argument and does not bind; only the target binds.
    assertThat(rewritten).contains("this(0, x)");
    assertThat(count(rewritten, "this.x = x;")).isEqualTo(1);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_finalBlankField_noConstructor_generatesOne() {
    final var source =
        """
        package com.example;
        class Widget {
          private final String id;
        }
        """;
    final var actions = replaceVarActionsAt(source, 2, 23);

    assertThat(rightTitles(actions)).contains("Add constructor parameter 'id'");
    final String rewritten = applyEdits(source, addCtorEdits(actions));
    assertThat(rewritten).contains("Widget(final String id)").contains("this.id = id;");
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_finalBlankField_ineligible_offersNothing() {
    final var alreadyAssigns =
        """
        package com.example;
        class Cfg {
          private final int port;
          Cfg() {
            this.port = 8080;
          }
        }
        """;
    final var paramCollision =
        """
        package com.example;
        class Svc {
          private final String name;
          Svc(String name) {
          }
        }
        """;
    final var nonFinal =
        """
        package com.example;
        class A {
          private String owner;
          A() {}
        }
        """;
    final var staticField =
        """
        package com.example;
        class B {
          private static final String owner = "";
          B() {}
        }
        """;
    final var initialized =
        """
        package com.example;
        class D {
          private final String owner = "x";
          D() {}
        }
        """;
    final var recordComponent =
        """
        package com.example;
        record R(int base) {
        }
        """;
    final var methodLocal =
        """
        package com.example;
        class C {
          private final int v;
          void m() {
            int local = 0;
          }
        }
        """;
    record Case(String label, String source, int line, int character) {}

    final List<Case> cases =
        List.of(
            new Case("constructor already assigns the field", alreadyAssigns, 2, 20),
            new Case("parameter name collides with the field", paramCollision, 2, 23),
            new Case("field is not final", nonFinal, 2, 17),
            new Case("field is static", staticField, 2, 30),
            new Case("field already has an initializer", initialized, 2, 23),
            new Case("record component, not a class field", recordComponent, 1, 13),
            new Case("caret sits on a method-body local", methodLocal, 4, 8));

    for (final Case c : cases) {
      assertThat(rightTitles(replaceVarActionsAt(c.source(), c.line(), c.character())))
          .as(c.label())
          .noneMatch(t -> t.startsWith("Add constructor parameter"));
    }
  }

  // --- Helpers ---

  private static List<TextEdit> addCtorEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Add constructor parameter");
  }

  private void assertCompilesClean(final String source) {
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 99, CompileMode.OPEN);
    assertThat(diags)
        .filteredOn(d -> d.getSeverity() == DiagnosticSeverity.Error)
        .extracting(Diagnostic::getMessage)
        .isEmpty();
  }

  private static int count(final String haystack, final String needle) {
    int occurrences = 0;
    int from = 0;
    while (true) {
      final int at = haystack.indexOf(needle, from);
      if (at < 0) {
        return occurrences;
      }

      occurrences++;
      from = at + needle.length();
    }
  }

  // Applies non-overlapping TextEdits to the original source, from the latest offset backwards so
  // earlier offsets stay valid, so a test can assert on (and recompile) the rewritten file. Edits
  // at one offset land in list order, as LSP specifies: the stable ascending sort keeps that order,
  // and walking it backwards applies the later edit first.
  private static String applyEdits(final String source, final List<TextEdit> edits) {
    final List<TextEdit> ordered = new ArrayList<>(edits);
    ordered.sort(Comparator.comparingInt(e -> offsetOf(source, e.getRange().getStart())));
    Collections.reverse(ordered);
    String result = source;
    for (final TextEdit edit : ordered) {
      final int start = offsetOf(source, edit.getRange().getStart());
      final int end = offsetOf(source, edit.getRange().getEnd());
      result = result.substring(0, start) + edit.getNewText() + result.substring(end);
    }

    return result;
  }

  private static int offsetOf(final String source, final Position position) {
    int line = 0;
    int index = 0;
    while (line < position.getLine() && index < source.length()) {
      if (source.charAt(index) == '\n') {
        line++;
      }

      index++;
    }

    return index + position.getCharacter();
  }

  private static String tryWithResourcesEdit(final List<Either<Command, CodeAction>> actions) {
    final CodeAction action =
        actions.stream()
            .map(Either::getRight)
            .filter(a -> "Surround with try-with-resources".equals(a.getTitle()))
            .findFirst()
            .orElseThrow();
    return action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI).get(0).getNewText();
  }

  private List<Either<Command, CodeAction>> replaceVarActionsAt(
      final String source, final int line, final int character) {
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    return session.codeAction(
        TempSourceCompiler.TEST_URI,
        source,
        1,
        rangeAt(line, character),
        List.of(),
        typeIndex,
        LearnedStaticImports.empty());
  }

  private List<Either<Command, CodeAction>> extractActionsSpanning(
      final String source,
      final int startLine,
      final int startChar,
      final int endLine,
      final int endChar) {
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var range = new Range(new Position(startLine, startChar), new Position(endLine, endChar));
    return session.codeAction(
        TempSourceCompiler.TEST_URI,
        source,
        1,
        range,
        List.of(),
        typeIndex,
        LearnedStaticImports.empty());
  }

  private static List<TextEdit> extractEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Extract variable");
  }

  private static List<TextEdit> constantEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Extract constant");
  }

  private static List<TextEdit> fieldEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Extract field");
  }

  private static List<TextEdit> convertVarEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Convert to 'var'");
  }

  // The inserted declaration text, stripped of the leading/trailing whitespace that positions it on
  // its own line, so a placement-agnostic assertion can match the declaration itself.
  private static String insertedDecl(final List<TextEdit> edits) {
    return insertEdit(edits).getNewText().strip();
  }

  private static int insertLine(final List<TextEdit> edits) {
    return insertEdit(edits).getRange().getStart().getLine();
  }

  private static TextEdit insertEdit(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> e.getRange().getStart().equals(e.getRange().getEnd()))
        .filter(e -> !e.getNewText().startsWith("import "))
        .findFirst()
        .orElseThrow();
  }

  private static List<TextEdit> editsOfActionStartingWith(
      final List<Either<Command, CodeAction>> actions, final String titlePrefix) {
    return actions.stream()
        .filter(Either::isRight)
        .map(Either::getRight)
        .filter(a -> a.getTitle().startsWith(titlePrefix))
        .findFirst()
        .orElseThrow()
        .getEdit()
        .getChanges()
        .get(TempSourceCompiler.TEST_URI);
  }

  private static List<TextEdit> replaceAllEdits(final List<Either<Command, CodeAction>> actions) {
    return actions.stream()
        .filter(Either::isRight)
        .map(Either::getRight)
        .filter(a -> a.getTitle().contains("replace all"))
        .findFirst()
        .orElseThrow()
        .getEdit()
        .getChanges()
        .get(TempSourceCompiler.TEST_URI);
  }

  private static String newTextAtLineStart(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> e.getRange().getStart().equals(e.getRange().getEnd()))
        .filter(e -> !e.getNewText().startsWith("import "))
        .map(TextEdit::getNewText)
        .findFirst()
        .orElseThrow();
  }

  private static String replacementText(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> !e.getRange().getStart().equals(e.getRange().getEnd()))
        .map(TextEdit::getNewText)
        .findFirst()
        .orElseThrow();
  }

  private List<Either<Command, CodeAction>> diagnosticActions(
      final String source, final List<Diagnostic> diags) {
    return session.codeAction(
        TempSourceCompiler.TEST_URI,
        source,
        1,
        rangeAt(0, 0),
        toRequests(diags),
        typeIndex,
        LearnedStaticImports.empty());
  }

  private static List<CodeAction> codeActions(final List<Either<Command, CodeAction>> actions) {
    return actions.stream().filter(Either::isRight).map(Either::getRight).toList();
  }

  private static Range rangeAt(final int line, final int character) {
    final var pos = new Position(line, character);
    return new Range(pos, pos);
  }

  private static List<String> rightTitles(final List<Either<Command, CodeAction>> actions) {
    return actions.stream().filter(Either::isRight).map(a -> a.getRight().getTitle()).toList();
  }

  private static CodeAction findThrowsAction(final List<Either<Command, CodeAction>> actions) {
    return actions.stream()
        .map(a -> a.getRight())
        .filter(a -> a.getTitle().startsWith("Add 'throws"))
        .findFirst()
        .orElseThrow();
  }

  private static List<CodeActionRequest> toRequests(final List<Diagnostic> diags) {
    return diags.stream()
        .filter(d -> d.getData() instanceof DiagnosticPayload)
        .map(
            d ->
                new CodeActionRequest(
                    TempSourceCompiler.TEST_URI, d, (DiagnosticPayload) d.getData()))
        .toList();
  }

  private static Diagnostic diagWithCode(final List<Diagnostic> diags, final String codePrefix) {
    return diags.stream()
        .filter(
            d ->
                d.getCode() != null
                    && d.getCode().isLeft()
                    && d.getCode().getLeft().startsWith(codePrefix))
        .findFirst()
        .orElseThrow();
  }
}
