package io.github.aglibs.lathe.server.analysis;

import static io.github.aglibs.lathe.server.analysis.SampleFixture.posOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class RenameProviderTest {

  // --- name validation ---

  @Test
  void isValidName_identifier_isTrue() {
    assertThat(RenameProvider.isValidName("total")).isTrue();
  }

  @Test
  void isValidName_keywordOrIllegalOrNull_isFalse() {
    assertThat(RenameProvider.isValidName("class")).isFalse();
    assertThat(RenameProvider.isValidName("1total")).isFalse();
    assertThat(RenameProvider.isValidName("has space")).isFalse();
    assertThat(RenameProvider.isValidName("")).isFalse();
    assertThat(RenameProvider.isValidName(null)).isFalse();
  }

  // --- identifier range (prepareRename) ---

  @Test
  void identifierRange_cursorInsideIdentifier_returnsWholeToken() {
    final var range = RenameProvider.identifierRange("int foo = 1;", new Position(0, 5));

    assertThat(range).isEqualTo(new Range(new Position(0, 4), new Position(0, 7)));
  }

  @Test
  void identifierRange_cursorOnOperator_returnsNull() {
    // the `=` at column 8 is bounded by whitespace, so no identifier token surrounds the cursor
    assertThat(RenameProvider.identifierRange("int foo = 1;", new Position(0, 8))).isNull();
  }

  // --- edit assembly ---

  @Test
  void toWorkspaceEdit_groupsByUri_andReplacesEveryRangeWithNewName() {
    final var a = new Location("file:///A.java", range(0, 0, 0, 1));
    final var b = new Location("file:///A.java", range(1, 2, 1, 3));
    final var c = new Location("file:///B.java", range(0, 0, 0, 1));

    final var edit = RenameProvider.toWorkspaceEdit(List.of(a, b, c), "renamed");

    assertThat(edit.getChanges().get("file:///A.java")).hasSize(2);
    assertThat(edit.getChanges().get("file:///B.java")).hasSize(1);
    assertThat(edit.getChanges().values().stream().flatMap(List::stream))
        .allMatch(e -> e.getNewText().equals("renamed"));
  }

  @Test
  void toWorkspaceEditWithFileRename_emitsTextEditsThenRenameFile() {
    final var occurrence = new Location("file:///dir/Foo.java", range(0, 13, 0, 16));

    final var edit =
        RenameProvider.toWorkspaceEditWithFileRename(
            List.of(occurrence), "Bar", "file:///dir/Foo.java", "file:///dir/Bar.java");

    // resource operations require documentChanges, not the plain changes map
    assertThat(edit.getChanges()).isNullOrEmpty();
    final var documentChanges = edit.getDocumentChanges();
    assertThat(documentChanges).hasSize(2);
    assertThat(documentChanges.get(0).isLeft()).isTrue();
    assertThat(documentChanges.get(1).getRight()).isInstanceOf(RenameFile.class);
    final var rename = (RenameFile) documentChanges.get(1).getRight();
    assertThat(rename.getOldUri()).isEqualTo("file:///dir/Foo.java");
    assertThat(rename.getNewUri()).isEqualTo("file:///dir/Bar.java");
  }

  // --- integration: rename edit through the reference pipeline ---

  @Test
  void rename_localVariable_editsDeclarationAndUsesButNotOtherScope() {
    final var source =
        """
        class Test {
            int first() {
                int x = 1;
                return x + x;
            }
            int second() {
                int x = 2;
                return x;
            }
        }
        """;
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var target = session.resolveTarget(requestAt(source, posOf(source, "int x = 1", "x")));
      assertThat(target.isLocalScope()).isTrue();

      final List<Location> occurrences =
          session.searchReferences(TempSourceCompiler.TEST_URI, source, 1, target, true).stream()
              .map(match -> new Location(match.uri(), match.range()))
              .toList();
      final var edits =
          RenameProvider.toWorkspaceEdit(occurrences, "y")
              .getChanges()
              .get(TempSourceCompiler.TEST_URI);

      // decl + two uses in first(); nothing from second()
      assertThat(edits).hasSize(3).allMatch(e -> e.getNewText().equals("y"));
      assertThat(edits)
          .noneMatch(e -> e.getRange().getStart().equals(posOf(source, "int x = 2", "x")));
    }
  }

  // javac's column numbers count a tab as up to 8 columns, an LSP character as one: a position
  // taken from its columns would land 7 characters further per tab before it.
  @Test
  void rename_tabIndentedField_editsCoverTheNameAtEveryDepth() {
    final var source =
        """
        class Test {
        \tprivate final java.util.Map<String, Integer> cache = new java.util.HashMap<>();
        \tboolean has(String type) {
        \t\treturn cache.containsKey(type);
        \t}
        \tvoid put(String type) {
        \t\tif (type != null) {
        \t\t\tcache.put(type, type.length());
        \t\t}
        \t}
        }
        """;
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var target =
          session.resolveTarget(requestAt(source, posOf(source, "\tcache.put", "cache")));

      final List<Range> ranges =
          session.searchReferences(TempSourceCompiler.TEST_URI, source, 1, target, true).stream()
              .map(match -> match.range())
              .toList();

      final List<String> lines = source.lines().toList();
      assertThat(ranges)
          .extracting(Range::getStart)
          .extracting(Position::getLine)
          .containsExactlyInAnyOrder(1, 3, 7);
      assertThat(ranges)
          .extracting(
              range ->
                  lines
                      .get(range.getStart().getLine())
                      .substring(range.getStart().getCharacter(), range.getEnd().getCharacter()))
          .containsOnly("cache");
    }
  }

  @Test
  void resolveTarget_onMember_isNotLocalScope() {
    final var source = "class Test { int field; }";
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var request = requestAt(source, posOf(source, "int field", "field"));

      assertThat(session.resolveTarget(request).isLocalScope()).isFalse();
    }
  }

  // --- resolveRenameTarget gate (Slice 2: members + in-place types) ---

  @Test
  void resolveRenameTarget_fieldAndMethod_areRenameable() {
    final var source = "class T { int field; void run() {} }";
    withSession(
        source,
        session -> {
          assertThat(
                  session.resolveRenameTarget(
                      requestAt(source, posOf(source, "int field", "field"))))
              .isNotNull();
          assertThat(
                  session.resolveRenameTarget(requestAt(source, posOf(source, "void run", "run"))))
              .isNotNull();
        });
  }

  @Test
  void resolveRenameTarget_topLevelAndNestedTypes_areRenameable() {
    // The session resolves both as renameable; the file-move gating for a public top-level type
    // lives in WorkspaceSession (client-capability aware), not here.
    final var source = "public class Outer { static class Inner {} }";
    withSession(
        source,
        session -> {
          assertThat(
                  session.resolveRenameTarget(
                      requestAt(source, posOf(source, "class Outer", "Outer"))))
              .isNotNull();
          assertThat(
                  session.resolveRenameTarget(
                      requestAt(source, posOf(source, "class Inner", "Inner"))))
              .isNotNull();
        });
  }

  @Test
  void rename_type_editsDeclarationConstructorAndInstantiations() {
    final var source =
        """
        class Holder {
            static class Widget {
                Widget() {}
                Widget(int n) {}
            }
            Widget make() { return new Widget(); }
            Widget makeN() { return new Widget(42); }
        }
        """;
    withSession(
        source,
        session -> {
          final var edits = renameEdits(session, source, "class Widget", "Widget", "Gadget");

          // class decl (line 1), two constructor decl names (lines 2-3), two field-type uses, and
          // two `new Widget(...)` sites — every occurrence rewritten.
          assertThat(edits).allMatch(e -> e.getNewText().equals("Gadget"));
          assertThat(edits.stream().map(e -> e.getRange().getStart().getLine()).toList())
              .contains(1, 2, 3);
        });
  }

  @Test
  void resolveRenameTarget_enumConstant_isRenameable() {
    final var source = "enum Color { RED, GREEN }";
    withSession(
        source,
        session ->
            assertThat(session.resolveRenameTarget(requestAt(source, posOf(source, "RED", "RED"))))
                .isNotNull());
  }

  @Test
  void rename_enumConstant_editsDeclarationUseAndCaseLabel() {
    final var source =
        """
        enum Color {
            RED, GREEN;
            static String name(Color c) {
                return switch (c) {
                    case RED -> "r";
                    case GREEN -> "g";
                };
            }
            Color self() { return RED; }
        }
        """;
    withSession(
        source,
        session -> {
          final var edits = renameEdits(session, source, "RED, GREEN", "RED", "CRIMSON");

          // declaration, `case RED`, and `return RED`
          assertThat(edits).hasSize(3).allMatch(e -> e.getNewText().equals("CRIMSON"));
        });
  }

  @Test
  void resolveRenameTarget_cursorNotNamingTarget_isRefusedButJavadocLinkTargetsLinkedType() {
    final var source =
        """
        public class Outer {
            /** Prose words, then {@link Helper}. */
            int run() {
                // a comment word
                return 1;
            }
        }
        class Helper {}
        """;
    withSession(
        source,
        session -> {
          // Javadoc prose, a comment, and a keyword all sit in Outer's body; none names Outer.
          Map.of("Prose words", "words", "a comment word", "comment", "return 1", "return")
              .forEach(
                  (context, token) ->
                      assertThat(
                              session.resolveRenameTarget(
                                  requestAt(source, posOf(source, context, token))))
                          .as(token)
                          .isNull());

          final var link =
              session.resolveRenameTarget(
                  requestAt(source, posOf(source, "{@link Helper}", "Helper")));
          assertThat(link).isNotNull();
          assertThat(link.simpleName()).isEqualTo("Helper");
        });
  }

  @Test
  void resolveRenameTarget_constructor_redirectsToType() {
    final var source =
        """
        class Holder {
            static class Widget { Widget() {} }
            Widget make() { return new Widget(); }
        }
        """;
    withSession(
        source,
        session -> {
          final var target =
              session.resolveRenameTarget(
                  requestAt(source, posOf(source, "new Widget()", "Widget")));
          // the constructor site resolves to the (nested) type rename, not a <init> target
          assertThat(target).isNotNull();
          assertThat(target.simpleName()).isEqualTo("Widget");
          assertThat(target.isLocalScope()).isFalse();
        });
  }

  // --- integration: single-file member rename ---

  @Test
  void rename_privateField_editsDeclarationAndUses() {
    final var source =
        """
        class T {
            private int count;
            int read() { return count + count; }
        }
        """;
    withSession(
        source,
        session -> {
          final var edits = renameEdits(session, source, "int count", "count", "total");
          assertThat(edits).hasSize(3).allMatch(e -> e.getNewText().equals("total"));
        });
  }

  @Test
  void rename_interfaceMethodImplementedByRecordComponent_renamesComponent_stillCompiles() {
    // Impl implements Named.text() through its component's implicit accessor, which has no
    // declaration of its own: the component must be renamed with the method, or Impl stops
    // implementing Named. Unrelated's same-named component is not part of the family.
    final var source =
        """
        interface Named { String text(); }
        record Impl(String text) implements Named {
            Impl { text = text.trim(); }
            String loud() { return text + "!"; }
        }
        record Unrelated(String text) {
            String read() { return text; }
        }
        class Use { String read(Named named) { return named.text(); } }
        """;
    withSession(
        source,
        session -> {
          final String renamed =
              SourceLocator.applyEdits(
                  source, renameEdits(session, source, "String text();", "text", "label"));

          assertThat(renamed)
              .isEqualTo(
                  """
                  interface Named { String label(); }
                  record Impl(String label) implements Named {
                      Impl { label = label.trim(); }
                      String loud() { return label + "!"; }
                  }
                  record Unrelated(String text) {
                      String read() { return text; }
                  }
                  class Use { String read(Named named) { return named.label(); } }
                  """);
          assertThat(session.compile(TempSourceCompiler.TEST_URI, renamed, 2, CompileMode.OPEN))
              .noneMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
        });
  }

  // --- rename conflicts: a clash with an existing declaration is refused, a near miss is not ---

  @Test
  void renameConflict_eachKind_refusesAClashButNotANearMiss() {
    final var source =
        """
        class Base { int inherited; void take(String s) {} }
        class T extends Base {
            int field; int other;
            enum Color { RED, GREEN }
            class Inner {} class Other {}
            void run(int count, int limit) {
                int local = 0;
                for (int i = 0; i < count; i++) { int seen = i; }
                for (int j = 0; j < count; j++) {}
            }
            void put(String s) {}
            <A, B> void generic() {}
        }
        """;
    withSession(
        source,
        session -> {
          assertThat(conflict(session, source, "int field", "field", "other"))
              .hasValue("`field` cannot be renamed to `other`: T already declares field `other`");
          assertThat(conflict(session, source, "int field", "field", "inherited"))
              .hasValueSatisfying(
                  reason -> assertThat(reason).endsWith("Base already declares field `inherited`"));
          assertThat(conflict(session, source, "int field", "field", "field"))
              .hasValue("`field` cannot be renamed to `field`: it already has that name");
          assertThat(conflict(session, source, "int field", "field", "fresh")).isEmpty();
          assertThat(conflict(session, source, "RED", "RED", "GREEN"))
              .hasValueSatisfying(
                  reason ->
                      assertThat(reason).endsWith("Color already declares enum constant `GREEN`"));
          assertThat(conflict(session, source, "void put", "put", "take"))
              .hasValueSatisfying(
                  reason -> assertThat(reason).endsWith("Base already declares method `take`"));
          assertThat(conflict(session, source, "void run", "run", "take"))
              .as("an overload with other parameters")
              .isEmpty();
          assertThat(conflict(session, source, "class Inner", "Inner", "Other"))
              .hasValueSatisfying(
                  reason ->
                      assertThat(reason).endsWith("T already has a nested type named `Other`"));
          assertThat(conflict(session, source, "class T", "T", "Base"))
              .hasValueSatisfying(
                  reason -> assertThat(reason).endsWith("a type `Base` already exists"));
          assertThat(conflict(session, source, "<A", "A", "B")).isPresent();
          assertThat(conflict(session, source, "int count", "count", "local"))
              .as("declared within the parameter's scope")
              .isPresent();
          assertThat(conflict(session, source, "int local", "local", "limit"))
              .as("visible where the local is declared")
              .hasValueSatisfying(
                  reason ->
                      assertThat(reason).endsWith("a variable named `limit` is already in scope"));
          assertThat(conflict(session, source, "int i = 0", "i = 0", "seen")).isPresent();
          assertThat(conflict(session, source, "int seen", "seen", "j"))
              .as("a variable of a sibling loop")
              .isEmpty();

          assertThat(conflict(session, source, "class Inner", "Inner", "var"))
              .hasValue("`Inner` cannot be renamed to `var`: `var` cannot name a type");
          assertThat(conflict(session, source, "<A", "A", "record")).isPresent();
          assertThat(conflict(session, source, "void put", "put", "yield"))
              .hasValueSatisfying(
                  reason -> assertThat(reason).endsWith("cannot be called without a qualifier"));
          assertThat(conflict(session, source, "void put", "put", "record"))
              .as("a method can take any other restricted identifier")
              .isEmpty();
          assertThat(conflict(session, source, "int field", "field", "record"))
              .as("a variable can take a restricted identifier")
              .isEmpty();
          assertThat(conflict(session, source, "int local", "local", "var")).isEmpty();
        });
  }

  private static Optional<String> conflict(
      final SourceAnalysisSession session,
      final String source,
      final String context,
      final String token,
      final String newName) {
    return session.renameConflict(requestAt(source, posOf(source, context, token)), newName);
  }

  private static void withSession(
      final String source, final java.util.function.Consumer<SourceAnalysisSession> body) {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      body.accept(session);
    }
  }

  // Resolve the rename target at the cursor, search its references, and return the file's edits.
  private static List<TextEdit> renameEdits(
      final SourceAnalysisSession session,
      final String source,
      final String cursorText,
      final String cursorToken,
      final String newName) {
    final var target =
        session.resolveRenameTarget(requestAt(source, posOf(source, cursorText, cursorToken)));
    final List<Location> occurrences =
        session.searchReferences(TempSourceCompiler.TEST_URI, source, 1, target, true).stream()
            .map(match -> new Location(match.uri(), match.range()))
            .toList();
    return RenameProvider.toWorkspaceEdit(occurrences, newName)
        .getChanges()
        .get(TempSourceCompiler.TEST_URI);
  }

  private static Range range(final int sl, final int sc, final int el, final int ec) {
    return new Range(new Position(sl, sc), new Position(el, ec));
  }

  private static SourceFeatureRequest requestAt(final String source, final Position pos) {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI, source, 1, pos, List.of(), WorkspaceManifest.empty());
  }
}
