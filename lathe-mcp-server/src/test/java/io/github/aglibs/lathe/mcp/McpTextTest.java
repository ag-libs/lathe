package io.github.aglibs.lathe.mcp;

import static io.github.aglibs.lathe.mcp.McpFixtures.location;
import static io.github.aglibs.lathe.mcp.McpFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.ReconcileOutcome;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheFormat;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheImports;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestFailure;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTextTest {

  @TempDir private Path tmp;

  @Test
  void locatedDiagnosticLine_warning_grepStyleWithPathLineAndColumn() {
    final var diagnostic =
        new Diagnostic(
            new Range(new Position(757, 8), new Position(757, 20)),
            "setMinResponseDataRate(long) has been deprecated",
            DiagnosticSeverity.Warning,
            "javac");

    assertThat(
            McpText.locatedDiagnosticLine(
                LatheDiagnostic.of("jetty/src/main/java/Http.java", diagnostic)))
        .isEqualTo(
            "jetty/src/main/java/Http.java:758:9: Warning: setMinResponseDataRate(long) has been deprecated");
  }

  @Test
  void modulesResult_modules_oneLineEachWithUpstreamAndTestCapture_longListsCut() {
    final List<String> upstream =
        IntStream.rangeClosed(1, 12).mapToObj("lib%02d"::formatted).toList();

    final String text =
        text(
            McpText.modulesResult(
                List.of(
                    new LatheModuleSummary("app", upstream, true, true),
                    new LatheModuleSummary("lib01", List.of(), true, false),
                    new LatheModuleSummary("docs", List.of(), false, false)),
                McpText.NO_NOTES));

    assertThat(text.lines())
        .contains(
            "app  depends on: lib01, lib02, lib03, lib04, lib05, lib06, lib07, lib08, lib09, lib10, and 2 more  tests: captured",
            "lib01  tests: not captured",
            "docs")
        .first()
        .asString()
        .startsWith("3 module(s); build them with `%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(text).endsWith("`%s`.".formatted(LatheLayout.captureTestsCommand("<module>")));
  }

  @Test
  void outlineResult_nestedDeclarations_indentedLinesUnderOneHeader_orNoDeclarations() {
    final List<LatheOutlineItem> items =
        List.of(
            outlineItem("class", "Shop", 0, 3, "public class Shop {"),
            outlineItem("method", "sell", 1, 8, "int sell(int count) {"));

    assertThat(text(McpText.outlineResult(tmp.resolve("Shop.java"), items, McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "2 declaration(s) in app/Shop.java:",
                "class Shop  3:1: public class Shop {",
                "  method sell  8:1: int sell(int count) {"));
    assertThat(text(McpText.outlineResult(tmp.resolve("Empty.java"), List.of(), McpText.NO_NOTES)))
        .isEqualTo("No declarations in Empty.java.");
  }

  @Test
  void importsResult_addedAmbiguousAndUnknown_listsEachKind_onlyTheAddedLineWhenNothingIsLeft() {
    final var imports =
        new LatheImports(
            "app/src/main/java/a/Foo.java",
            List.of("java.time.Duration", "java.util.ArrayList"),
            List.of(
                new MissingImport("List", List.of("java.util.List", "java.awt.List"), List.of()),
                new MissingImport("Fooo", List.of(), List.of()),
                new MissingImport("Entry", List.of(), List.of("java.util.Map.Entry"))));

    assertThat(text(McpText.importsResult(imports, McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "Added 2 import(s) to app/src/main/java/a/Foo.java: java.time.Duration, java.util.ArrayList",
                "Ambiguous — add one yourself:",
                "  List: java.util.List, java.awt.List",
                "No import found for: Fooo, Entry (nested type java.util.Map.Entry: qualify it from its outer type)"));
    assertThat(
            text(
                McpText.importsResult(
                    new LatheImports("a/Foo.java", List.of(), List.of()), McpText.NO_NOTES)))
        .isEqualTo("No import added to a/Foo.java.");
  }

  @Test
  void verifyChangeResult_deferred_namesTheSyncOnlyWhenOneHelps() {
    assertThat(
            text(
                McpText.verifyChangeResult(
                    new LatheVerifyChange(
                        ReconcileOutcome.DeferReason.BUILD_IN_PROGRESS,
                        List.of(),
                        List.of(),
                        ".lathe/lathe-sync.sh"),
                    McpText.NO_NOTES)))
        .isEqualTo(
            "Verification deferred: a build is in progress. Run `.lathe/lathe-sync.sh`, then re-check.");
    assertThat(
            text(
                McpText.verifyChangeResult(
                    new LatheVerifyChange(
                        ReconcileOutcome.DeferReason.OTHER_SERVER_WRITING,
                        List.of(),
                        List.of(),
                        null),
                    McpText.NO_NOTES)))
        .isEqualTo(
            "Verification deferred: another Lathe server on this workspace is updating .lathe/. Re-check in a moment.");
  }

  @Test
  void noteLines_eachKindOfNote_rendersItsLine_noneWhenNothingToSay() {
    assertThat(McpText.noteLines(McpText.NO_NOTES)).isEmpty();
    assertThat(
            McpText.noteLines(
                new LatheNotes(
                    List.of("a/A.java:3: cannot find symbol", "b/B.java:1: ';' expected"),
                    List.of("build files changed — run `.lathe/lathe-sync.sh`"),
                    List.of("x/X.java is outside the modules the last build compiled"),
                    true)))
        .containsExactly(
            "Not compiled: a/A.java:3: cannot find symbol; b/B.java:1: ';' expected — fix the code; cross-module answers use their last good classes.",
            "Sync required: build files changed — run `.lathe/lathe-sync.sh`",
            "Not checked: x/X.java is outside the modules the last build compiled",
            "A build is running: this answer reflects Lathe's classes from before it; re-query when it ends.");
    assertThat(LatheMcpServer.INSTRUCTIONS)
        .as("the routing instructions explain every note")
        .contains("\"Not compiled:\"")
        .contains("\"Sync required:\"")
        .contains("\"Not checked:\"")
        .doesNotContain("Stale:");
  }

  @Test
  void crossModuleNote_dependents_namesTheFirstTenAndSaysWhetherTheBuildIsNeeded() {
    final List<String> twelve = IntStream.rangeClosed(1, 12).mapToObj("mod%d"::formatted).toList();
    final String sync = ".lathe/lathe-sync.sh core";

    assertThat(McpText.crossModuleNote(new LatheVerifyChange(null, List.of(), twelve, sync), 0))
        .contains("12 module(s) depend on your change (mod1, mod2,")
        .contains("mod10, and 2 more)")
        .doesNotContain("mod11")
        .endsWith(sync);
    assertThat(
            McpText.crossModuleNote(
                new LatheVerifyChange(null, List.of(), List.of("a", "b"), sync), 0))
        .contains(
            "2 module(s) depend on your change (a, b); their callers compile, so a full build is optional")
        .doesNotContain("more");
    assertThat(
            McpText.crossModuleNote(
                new LatheVerifyChange(null, List.of(), List.of("a", "b"), sync), 1))
        .contains("(a, b); fix the errors and re-check, or build them with")
        .endsWith(sync);
    assertThat(McpText.crossModuleNote(new LatheVerifyChange(null, List.of(), twelve, null), 0))
        .isEmpty();
  }

  @Test
  void verifyHeadline_errorsAndWarnings_leadsWithErrors() {
    assertThat(McpText.verifyHeadline(0, 0, 0))
        .isEqualTo("No errors — the changed files and their callers compile.");
    assertThat(McpText.verifyHeadline(0, 7, 0))
        .isEqualTo(
            "No errors — the changed files and their callers compile; 7 warning(s) in the changed files.");
    assertThat(McpText.verifyHeadline(2, 1, 0))
        .isEqualTo("2 error(s); 1 warning(s) in the changed files.");
    assertThat(McpText.verifyHeadline(3, 0, 1)).isEqualTo("3 error(s).");
  }

  @Test
  void verifyHeadline_namedFileNotChecked_neverSaysEverythingCompiles() {
    assertThat(McpText.verifyHeadline(0, 0, 1))
        .isEqualTo("No errors in what was checked; 1 named file(s) not checked.");
    assertThat(McpText.verifyHeadline(0, 2, 3))
        .isEqualTo(
            "No errors in what was checked; 2 warning(s) in the changed files; 3 named file(s) not checked.");
  }

  // A reactor file carries no tag (most answers are reactor code); anything else says where it is.
  @Test
  void locationLine_reactorAndExternal_oneGrepStyleLineTaggedOnlyOutsideTheReactor() {

    assertThat(
            McpText.locationLine(
                new LatheLocation(
                    "file:///ws/app/src/main/java/App.java",
                    "app/src/main/java/App.java",
                    411,
                    8,
                    LatheLocation.Origin.REACTOR,
                    "client.connect();")))
        .isEqualTo("app/src/main/java/App.java:412: client.connect();");
    assertThat(
            McpText.locationLine(
                new LatheLocation(
                    "jar:file:///repo/lib-sources.jar!/Lib.java",
                    "jar:file:///repo/lib-sources.jar!/Lib.java",
                    411,
                    8,
                    LatheLocation.Origin.EXTERNAL,
                    "void connect() {")))
        .isEqualTo("jar:file:///repo/lib-sources.jar!/Lib.java:412 [EXTERNAL]: void connect() {");
  }

  // A capped list says how many there are in all; an empty one says none, without the authority.
  @Test
  void referencesAndImplementationsResult_cappedListAndEmpty_headerLinesThenAuthority_orNone() {
    final var call = location("app/App.java", 12, "shop.sell(1);");
    final var dependency =
        location("lib/Lib.java", 3, LatheLocation.Origin.EXTERNAL, "void sell(int n) {");

    assertThat(
            text(
                McpText.referencesResult(
                    new LatheReferences(5, true, List.of(call, dependency)), McpText.NO_NOTES)))
        .startsWith(
            lines(
                "5 references (showing first 2):",
                "app/App.java:12: shop.sell(1);",
                "lib/Lib.java:3 [EXTERNAL]: void sell(int n) {",
                "",
                "All javac-resolved references"));
    assertThat(
            text(
                McpText.implementationsResult(
                    new LatheImplementations(1, false, List.of(call)), McpText.NO_NOTES)))
        .startsWith(
            lines(
                "1 implementation(s):",
                "app/App.java:12: shop.sell(1);",
                "",
                "All javac-resolved implementers"));
    assertThat(
            text(
                McpText.referencesResult(
                    new LatheReferences(0, false, List.of()), McpText.NO_NOTES)))
        .isEqualTo("No references found.");
  }

  // The places where the old name is still text are listed for the agent to decide, never edited.
  @Test
  void renameResult_editsAndMentions_filesThenTheMentionsToReview_orNothingToRename() {
    final var rename =
        new LatheRename(
            "total",
            3,
            List.of(
                new LatheFileEdit(
                    "file:///w/app/Shop.java", "app/Shop.java", LatheLocation.Origin.REACTOR, 2),
                new LatheFileEdit(
                    "file:///w/gen/ShopView.java",
                    "gen/ShopView.java",
                    LatheLocation.Origin.GENERATED,
                    1)),
            List.of(location("app/Shop.java", 7, "// the count of items")),
            4);

    final String text = text(McpText.renameResult(rename, McpText.NO_NOTES));

    assertThat(text)
        .startsWith(
            lines(
                "Renamed to total: 3 edit(s) across 2 file(s):",
                "  app/Shop.java — 2 edit(s)",
                "  gen/ShopView.java [GENERATED] — 1 edit(s)"))
        .endsWith(
            lines(
                "Not renamed — review: the old name is still text in 4 place(s) (showing first 1):",
                "app/Shop.java:7: // the count of items"));
    assertThat(
            text(
                McpText.renameResult(
                    new LatheRename("total", 0, List.of(), List.of(), 0), McpText.NO_NOTES)))
        .isEqualTo("No references to rename.");
  }

  // Outgoing entries are call sites; an empty reactor-only answer points at scope=all.
  @Test
  void callHierarchyResult_incomingOutgoingAndEmpty_arrowLinesOrWhatTheScopeLeftOut() {
    final var caller = new LatheCall("run", location("app/App.java", 12, "shop.sell(1);"));
    final var callee = new LatheCall("total", location("app/Shop.java", 9, "return total();"));

    assertThat(
            text(
                McpText.callHierarchyResult(
                    new LatheCallHierarchy(true, 3, true, List.of(caller)),
                    SymbolScope.REACTOR,
                    McpText.NO_NOTES)))
        .isEqualTo(lines("3 callers (showing first 1):", "← run  app/App.java:12: shop.sell(1);"));
    assertThat(
            text(
                McpText.callHierarchyResult(
                    new LatheCallHierarchy(false, 1, false, List.of(callee)),
                    SymbolScope.ALL,
                    McpText.NO_NOTES)))
        .isEqualTo(lines("1 call(s):", "→ total  app/Shop.java:9: return total();"));
    assertThat(
            text(
                McpText.callHierarchyResult(
                    new LatheCallHierarchy(false, 0, false, List.of()),
                    SymbolScope.REACTOR,
                    McpText.NO_NOTES)))
        .isEqualTo(
            "No callees in the reactor; scope=all includes calls into the JDK and dependencies.");
    assertThat(
            text(
                McpText.callHierarchyResult(
                    new LatheCallHierarchy(false, 0, false, List.of()),
                    SymbolScope.ALL,
                    McpText.NO_NOTES)))
        .isEqualTo("No calls found.");
    assertThat(
            text(
                McpText.callHierarchyResult(
                    new LatheCallHierarchy(true, 0, false, List.of()),
                    SymbolScope.REACTOR,
                    McpText.NO_NOTES)))
        .isEqualTo("No callers found.");
  }

  @Test
  void searchResult_symbolsAndNoMatch_oneLineEachWithItsContainer_orTheQuery() {
    final var symbols =
        List.of(
            new LatheSymbol(
                "Shop", "class", "com.example", location("app/Shop.java", 3, "class Shop {")),
            new LatheSymbol("sell", "method", "", location("app/Shop.java", 8, "int sell() {")));

    assertThat(text(McpText.searchResult("S", symbols, McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "2 symbol(s):",
                "Shop (class) — com.example  app/Shop.java:3",
                "sell (method)  app/Shop.java:8"));
    assertThat(text(McpText.searchResult("Cart", List.of(), McpText.NO_NOTES)))
        .isEqualTo("No symbols matching 'Cart'.");
  }

  // A run that reported no test says how the JVM ended instead of a PASS over nothing.
  @Test
  void testRunResult_blockedNothingRanFailedAndPassed_eachSaysWhatHappened() {
    assertThat(
            text(
                McpText.testRunResult(
                    testRun(false, List.of("no runner jar", "not captured"), 0, 0, 0, List.of()),
                    McpText.NO_NOTES)))
        .isEqualTo("Test run blocked: no runner jar; not captured");
    assertThat(
            text(
                McpText.testRunResult(
                    new LatheTestRun(
                        true,
                        List.of(),
                        List.of("com.example.ShopTest"),
                        0,
                        0,
                        0,
                        0,
                        List.of(),
                        1,
                        List.of("Error: could not find the main class"),
                        List.of()),
                    McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "No test ran (exit 1)",
                "Ran: com.example.ShopTest",
                "    | Error: could not find the main class"));
    assertThat(
            text(
                McpText.testRunResult(
                    testRun(
                        true,
                        List.of(),
                        1,
                        1,
                        1,
                        List.of(
                            new LatheTestFailure(
                                "ShopTest.sell",
                                "expected 2 but was 1",
                                14,
                                List.of("at ShopTest.sell")))),
                    McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "FAIL — 1 passed, 1 failed, 1 skipped",
                "Ran: com.example.ShopTest",
                "  ShopTest.sell:14 — expected 2 but was 1",
                "    | at ShopTest.sell",
                "Note: classes from the last build"));
    assertThat(
            text(
                McpText.testRunResult(
                    testRun(true, List.of(), 2, 0, 0, List.of()), McpText.NO_NOTES)))
        .startsWith(lines("PASS — 2 passed, 0 failed, 0 skipped", "Ran: com.example.ShopTest"));
  }

  @Test
  void analyzeResult_impactAndNoSymbol_everyFieldOnItsLine_noneWhereEmpty() {
    final var base = location("app/Base.java", 4, "abstract int sell(int count);");
    final var override = location("app/Shop.java", 8, "int sell(int count) {");

    assertThat(
            text(
                McpText.analyzeResult(
                    new LatheChangeImpact(
                        "int sell(int count)",
                        List.of(base, override),
                        4,
                        2,
                        true,
                        List.of("app", "core"),
                        List.of("ShopTest")),
                    McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "int sell(int count)",
                "references: 4 production, 2 test (truncated)",
                "affected modules: app, core",
                "relevant tests: ShopTest",
                "override family: 2",
                "app/Base.java:4: abstract int sell(int count);",
                "app/Shop.java:8: int sell(int count) {"));
    assertThat(
            text(
                McpText.analyzeResult(
                    new LatheChangeImpact("", List.of(), 0, 0, false, List.of(), List.of()),
                    McpText.NO_NOTES)))
        .isEqualTo(
            lines(
                "(no symbol at that position)",
                "references: 0 production, 0 test",
                "affected modules: none",
                "relevant tests: none",
                "override family: none"));
  }

  // Never "compiles cleanly": a file no module checked says so, and a clean one says no problems.
  @Test
  void diagnosticsText_notCheckedCleanAndDiagnostics_saysWhichWithOneLinePerDiagnostic() {
    final Path file = tmp.resolve("Shop.java");
    final var error =
        diagnostic(
            new Range(new Position(4, 9), new Position(4, 12)),
            "cannot find symbol",
            DiagnosticSeverity.Error,
            "javac");
    final var warning =
        diagnostic(
            new Range(new Position(0, 0), new Position(0, 1)),
            "unchecked call",
            DiagnosticSeverity.Warning,
            "javac");

    assertThat(
            McpText.diagnosticsText(
                file, List.of(error), LatheNotes.ofNotChecked(List.of("Shop.java: no module"))))
        .isEqualTo("Shop.java was not checked.");
    assertThat(McpText.diagnosticsText(file, List.of(), McpText.NO_NOTES))
        .isEqualTo("No errors or warnings in Shop.java.");
    assertThat(McpText.diagnosticsText(file, List.of(error, warning), McpText.NO_NOTES))
        .isEqualTo(
            lines(
                "2 diagnostic(s) in Shop.java:",
                "  Error [5:10] cannot find symbol",
                "  Warning [1:1] unchecked call"));
  }

  // The engine's lines are 0-based like every position; the agent reads them 1-based.
  @Test
  void formatResult_oneLineOrASpanOrNothing_linesAsTheAgentReadsThem() {
    final Path file = tmp.resolve("Shop.java");

    assertThat(
            text(
                McpText.formatResult(
                    file, new LatheFormat("app/Shop.java", 5, 7), McpText.NO_NOTES)))
        .isEqualTo("Formatted app/Shop.java: lines 6–8 changed.");
    assertThat(
            text(
                McpText.formatResult(
                    file, new LatheFormat("app/Shop.java", 0, 0), McpText.NO_NOTES)))
        .isEqualTo("Formatted app/Shop.java: line 1 changed.");
    assertThat(text(McpText.formatResult(file, null, McpText.NO_NOTES)))
        .isEqualTo("Shop.java was already formatted.");
  }

  private static LatheOutlineItem outlineItem(
      final String kind, final String name, final int depth, final int line, final String code) {
    return new LatheOutlineItem(kind, name, depth, location("app/Shop.java", line, code));
  }

  private static String lines(final String... lines) {
    return String.join(System.lineSeparator(), lines);
  }

  private static LatheTestRun testRun(
      final boolean launched,
      final List<String> blockedReasons,
      final int passed,
      final int failed,
      final int skipped,
      final List<LatheTestFailure> failures) {
    return new LatheTestRun(
        launched,
        blockedReasons,
        launched ? List.of("com.example.ShopTest") : List.of(),
        passed + failed + skipped,
        passed,
        failed,
        skipped,
        failures,
        failed == 0 ? 0 : 1,
        List.of(),
        launched ? List.of("classes from the last build") : List.of());
  }

  private static LatheDiagnostic diagnostic(
      final Range range,
      final String message,
      final DiagnosticSeverity severity,
      final String source) {
    return LatheDiagnostic.of("Shop.java", new Diagnostic(range, message, severity, source));
  }
}
