package io.github.aglibs.lathe.analysis;

import static io.github.aglibs.lathe.analysis.Positions.applyEdits;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.refactor.ImportCandidates;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheCommands;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

// The quick fixes a diagnostic offers: imports, throws or try/catch, a local for an unknown name,
// stubs for abstract methods, constructor bindings for final fields.
class QuickFixCodeActionTest extends CodeActionTestBase {
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
            session
                .files()
                .codeAction(
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
        session
            .files()
            .missingImports(TempSourceCompiler.TEST_URI, UNRESOLVED_MEMBERS, 1, typeIndex, LEARNED);

    assertThat(result.items())
        .containsExactlyInAnyOrder(
            new MissingImportsResult.MissingImport(
                "requireNonNull", List.of("static java.util.Objects.requireNonNull"), List.of()),
            new MissingImportsResult.MissingImport(
                "emptyList", List.of("static java.util.Collections.emptyList"), List.of()));
  }

  // Qualifiers: an unresolved X in X.member may be a type or a statically imported field.

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
        session
            .files()
            .codeAction(
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
        .containsExactly(LatheCommands.IMPORTS_MISSING);
  }

  // Missing imports lists each qualifier with every candidate of either kind: a single one is
  // added, several are a choice, and a qualifier matching nothing is a typo, left out.
  @Test
  void missingImports_unresolvedQualifiers_listedWithTypeAndStaticCandidates() throws IOException {
    // The project references javax.xml.datatype.Duration, so it leads its alphabetically-earlier
    // namesake.
    final var result =
        session
            .files()
            .missingImports(
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
                            .files()
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
        session
            .files()
            .codeAction(
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
    assertThat(commands.get(0).getCommand()).isEqualTo(LatheCommands.IMPORTS_MISSING);
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
        session
            .files()
            .codeAction(
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
        session
            .files()
            .codeAction(
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
    TestJavac.compileToDir(classDir, ppFile, pubFile);
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
        session
            .files()
            .codeAction(
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

  // A nested type is never imported itself, not even statically when the workspace imports it so:
  // the quick fix writes it from its top-level type and imports that, fully qualified when the file
  // gives the top-level name another type. Missing imports name it as a hint, not a candidate, so a
  // top-level type of the same name stays the one unambiguous candidate, and a qualifier only a
  // nested type matches keeps its hint. An inaccessible one is not offered.
  @Test
  void codeAction_nestedType_qualifiedFromItsTopLevelTypeNeverImported() throws IOException {
    final var classDir = tmp.resolve("classes");
    final var outerFile = tmp.resolve("A.java");
    Files.writeString(
        outerFile,
        """
        package com.other;
        public class A {
          public static class Item {}
          public static class Tool {
            public static void run() {}
          }
          private static class Hidden {}
        }
        """);
    final var itemFile = tmp.resolve("Item.java");
    Files.writeString(
        itemFile,
        """
        package com.example.items;
        public class Item {}
        """);
    TestJavac.compileToDir(classDir, outerFile, itemFile);
    session.close();
    session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));
    final WorkspaceTypeIndex nestedIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("nested_index.json"),
            new TypeIndexEntry(
                "Entry", "java.util.Map$Entry", "java.util", TypeKind.INTERFACE, true, List.of()),
            new TypeIndexEntry(
                "Item", "com.other.A$Item", "com.other", TypeKind.CLASS, true, List.of()),
            new TypeIndexEntry(
                "Item",
                "com.example.items.Item",
                "com.example.items",
                TypeKind.CLASS,
                true,
                List.of()),
            new TypeIndexEntry(
                "Tool", "com.other.A$Tool", "com.other", TypeKind.CLASS, true, List.of()),
            new TypeIndexEntry(
                "Hidden", "com.other.A$Hidden", "com.other", TypeKind.CLASS, true, List.of()));
    final var learned =
        new LearnedStaticImports(Map.of("java.util.Map.Entry", 3, "com.other.A.*", 1));
    final var source =
        """
        package com.example;
        class Test {
          Entry<String, String> entry;
          Item item;
          Hidden hidden;
          void m() {
            Tool.run();
          }
        }
        """;

    final List<CodeAction> actions = codeActions(nestedActions(source, nestedIndex, learned));

    assertThat(actions)
        .extracting(CodeAction::getTitle)
        .containsExactly(
            "Qualify as 'Map.Entry' (java.util.Map.Entry)",
            "Import 'com.example.items.Item'",
            "Qualify as 'A.Item' (com.other.A.Item)",
            "Qualify as 'A.Tool' (com.other.A.Tool)");
    assertThat(applyEdits(source, editsOf(actions.get(0))))
        .contains("import java.util.Map;")
        .contains("  Map.Entry<String, String> entry;")
        .doesNotContain("import java.util.Map.Entry;");
    assertThat(
            session
                .files()
                .missingImports(TempSourceCompiler.TEST_URI, source, 1, nestedIndex, learned)
                .items())
        .containsExactlyInAnyOrder(
            new MissingImportsResult.MissingImport(
                "Entry", List.of(), List.of("java.util.Map.Entry")),
            new MissingImportsResult.MissingImport(
                "Item", List.of("com.example.items.Item"), List.of("com.other.A.Item")),
            new MissingImportsResult.MissingImport("Hidden", List.of(), List.of()),
            new MissingImportsResult.MissingImport("Tool", List.of(), List.of("com.other.A.Tool")));

    final AttributedFileAnalysis analysis =
        session.analysis(TempSourceCompiler.TEST_URI, source, 1, () -> {});
    assertThat(ImportCandidates.nestedTypes("Item", null, analysis, nestedIndex))
        .as("without a scope only a type public all the way out")
        .extracting(type -> type.getQualifiedName().toString())
        .containsExactly("com.other.A.Item");
    assertThat(ImportCandidates.nestedTypes("Hidden", null, analysis, nestedIndex)).isEmpty();

    final var clashing =
        """
        package com.example;
        class Test {
          static class Map {}
          Entry<String, String> entry;
        }
        """;
    final List<CodeAction> qualified =
        codeActions(nestedActions(clashing, nestedIndex, LearnedStaticImports.empty()));

    assertThat(qualified)
        .singleElement()
        .satisfies(
            action -> {
              assertThat(action.getTitle()).isEqualTo("Qualify as 'java.util.Map.Entry'");
              assertThat(applyEdits(clashing, editsOf(action)))
                  .contains("  java.util.Map.Entry<String, String> entry;")
                  .doesNotContain("import");
            });
  }

  // A name in a class header gets the fix too; a common name nested in many types offers only the
  // first few.
  @Test
  void codeAction_nestedType_inAHeaderAndNestedInManyTypes_qualifiedAtMostTenWays()
      throws IOException {
    final var classDir = tmp.resolve("classes");
    final List<Path> outers =
        IntStream.range(0, 12)
            .mapToObj(
                i ->
                    IOUtil.unchecked(
                        () ->
                            Files.writeString(
                                tmp.resolve("O%d.java".formatted(i)),
                                "package com.other; public class O%d { public static class Part {} }"
                                    .formatted(i))))
            .toList();
    TestJavac.compileToDir(classDir, outers.toArray(Path[]::new));
    session.close();
    session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));
    final WorkspaceTypeIndex partIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("part_index.json"),
            Stream.concat(
                    IntStream.range(0, 12)
                        .mapToObj(
                            i ->
                                new TypeIndexEntry(
                                    "Part",
                                    "com.other.O%d$Part".formatted(i),
                                    "com.other",
                                    TypeKind.CLASS,
                                    true,
                                    List.of())),
                    Stream.of(
                        new TypeIndexEntry(
                            "Entry",
                            "java.util.Map$Entry",
                            "java.util",
                            TypeKind.INTERFACE,
                            true,
                            List.of())))
                .toArray(TypeIndexEntry[]::new));

    assertThat(
            codeActions(
                nestedActions(
                    "package com.example;\nclass Test {\n  Part part;\n}\n",
                    partIndex,
                    LearnedStaticImports.empty())))
        .hasSize(10)
        .allMatch(action -> action.getTitle().startsWith("Qualify as 'O"));
    assertThat(
            codeActions(
                nestedActions(
                    "package com.example;\nabstract class Header implements Entry<String, String> {}\n",
                    partIndex,
                    LearnedStaticImports.empty())))
        .extracting(CodeAction::getTitle)
        .containsExactly("Qualify as 'Map.Entry' (java.util.Map.Entry)");
  }

  private List<Either<Command, CodeAction>> nestedActions(
      final String source, final WorkspaceTypeIndex index, final LearnedStaticImports learned) {
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    return session
        .files()
        .codeAction(
            TempSourceCompiler.TEST_URI,
            source,
            1,
            rangeAt(0, 0),
            toRequests(diags),
            index,
            learned);
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
    final var reactorIndex =
        WorkspaceTypeIndex.build(
            List.of(), TempSourceCompiler.reactorShards(List.of(reactorEntry)));

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
        session
            .files()
            .codeAction(
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
        session
            .files()
            .missingImports(
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
        session
            .files()
            .missingImports(
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
  void codeAction_unreportedException_hiddenByAMemberClass_addsThrowsQualified() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.io.IOException {}
          static class IOException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var throwsAction = findThrowsAction(diagnosticActions(source, diags));

    final String rewritten =
        applyEdits(source, throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI));
    assertThat(rewritten).contains("void method() throws java.io.IOException {");
    assertCompilesClean(rewritten);
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
        session
            .files()
            .codeAction(
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
    assertCompilesClean(applyEdits(source, edits));
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
    assertCompilesClean(applyEdits(source, edits));
  }

  // The statement around the call is wrapped, never the method body block; the catch variable takes
  // a name no local in scope has.
  @Test
  void codeAction_unreportedException_insideIfWithLocalE_wrapsTheIfAndRenamesTheCatch() {
    final var source =
        """
        package com.example;
        class Test {
          void method(boolean flag) {
            String e = "taken";
            if (flag) {
              throw new java.io.IOException(e);
            }
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final List<TextEdit> edits =
        editsOf(actionTitled(diagnosticActions(source, diags), "Wrap in try/catch"::equals));
    final String rewritten = applyEdits(source, edits);

    assertThat(edits.get(0).getNewText())
        .startsWith("try {")
        .contains("if (flag)")
        .contains("catch (java.io.IOException e1)");
    assertThat(rewritten).contains("void method(boolean flag) {").contains("String e = \"taken\";");
    assertCompilesClean(rewritten);
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

  @Test
  void codeAction_missingMethodImpl_writesTheThrowsClauseAndImportsWhatTheStubWrites() {
    // close() throws IOException: the stub declares it, as the contract does, and imports it.
    final var source =
        """
        package com.example;
        class Test implements java.io.Closeable, java.util.function.Supplier<java.util.List<String>> { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final String rewritten =
        applyEdits(source, editsOf(diagnosticActions(source, diags).get(0).getRight()));

    assertThat(rewritten)
        .contains("import java.util.List;")
        .contains("public List<String> get()")
        .contains("import java.io.IOException;")
        .contains("public void close() throws IOException")
        .doesNotContain("public List<String> get() throws");
    assertCompilesClean(rewritten);
  }

  // A generic method declares its type variables; methods inherited with one signature take one
  // stub, throwing only what both allow (Quiet.close() allows nothing).
  @Test
  void codeAction_missingMethodImpl_genericAndSharedSignatures_stubsCompile() {
    final var source =
        """
        package com.example;
        interface Task { <X extends Exception> void run() throws X; }
        interface Quiet { void close(); }
        interface Sink<T> { void accept(T value); }
        interface TextSink { void accept(String value); }
        class Test implements Task, Quiet, java.io.Closeable, Sink<String>, TextSink { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final String rewritten =
        applyEdits(source, editsOf(diagnosticActions(source, diags).get(0).getRight()));

    assertThat(rewritten)
        .contains("public <X extends Exception> void run() throws X")
        .contains("public void close() {")
        .doesNotContain("IOException");
    assertThat(count(rewritten, "void close()")).isEqualTo(2);
    assertThat(count(rewritten, "public void accept(String")).isEqualTo(1);
    assertCompilesClean(rewritten);
  }

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
  void codeAction_finalBlankField_delegatingIntoAConstructorThatSetsIt_leftAlone() {
    final var source =
        """
        package com.example;
        class Point {
          private final int x;
          Point() {
            this(0);
          }
          Point(int start) {
            this.x = start;
          }
          Point(String label) {
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 2, 20);

    final String rewritten = applyEdits(source, addCtorEdits(actions));
    assertThat(rewritten).contains("this(0);").contains("Point(String label, final int x)");
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

  private static List<CodeAction> codeActions(final List<Either<Command, CodeAction>> actions) {
    return actions.stream().filter(Either::isRight).map(Either::getRight).toList();
  }

  private static CodeAction findThrowsAction(final List<Either<Command, CodeAction>> actions) {
    return actionTitled(actions, title -> title.startsWith("Add 'throws"));
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
