package io.github.aglibs.lathe.mcp;

import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.ReconcileOutcome;
import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheChangeImpact;
import io.github.aglibs.lathe.server.engine.LatheDefinition;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheFormat;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheImports;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleDiagnostics;
import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.engine.LatheNotes;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestFailure;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// The text of every MCP answer: what an agent reads back from a tool, built from the engine's
// results and the notes on the files they cover.
final class McpText {

  private static final int LISTED_MODULES = 10;

  private McpText() {}

  // Authority lines appended to semantic-result text so the agent trusts the result instead of
  // re-verifying with grep (the "distrust tax"). True by construction: the search is
  // javac-resolved.
  private static final String REFERENCES_AUTHORITY =
      "All javac-resolved references to this exact declaration; same-named but unrelated symbols are"
          + " already excluded — no need to re-verify with grep.";
  private static final String IMPLEMENTATIONS_AUTHORITY =
      "All javac-resolved implementers/overriders across modules; unrelated same-named types are"
          + " excluded — no need to re-verify with grep.";
  private static final String RENAME_AUTHORITY =
      "Only javac-resolved uses of this declaration were renamed (overload- and scope-correct);"
          + " same-named unrelated symbols were left untouched.";

  static CallToolResult referencesResult(final LatheReferences refs, final LatheNotes notes) {
    return locationListResult(
        "reference",
        refs.total(),
        refs.truncated(),
        refs.references(),
        notes,
        REFERENCES_AUTHORITY);
  }

  static CallToolResult implementationsResult(
      final LatheImplementations impls, final LatheNotes notes) {
    return locationListResult(
        "implementation",
        impls.total(),
        impls.truncated(),
        impls.implementations(),
        notes,
        IMPLEMENTATIONS_AUTHORITY);
  }

  // Shared rendering for a capped, ranked location list ("N noun(s)"); authority is appended when
  // the list is non-empty.
  static CallToolResult locationListResult(
      final String noun,
      final int total,
      final boolean truncated,
      final List<LatheLocation> locations,
      final LatheNotes notes,
      final String authority) {
    final String header =
        truncated
            ? "%d %ss (showing first %d):".formatted(total, noun, locations.size())
            : "%d %s(s):".formatted(total, noun);
    final String text =
        locations.isEmpty()
            ? "No %ss found.".formatted(noun)
            : "%s%n%s%n%n%s".formatted(header, locationLines(locations), authority);
    return result(text, notes);
  }

  static CallToolResult renameResult(final LatheRename rename, final LatheNotes notes) {
    if (rename.totalEdits() == 0) {
      return result("No references to rename.", notes);
    }

    final String text =
        "Renamed to %s: %d edit(s) across %d file(s):%n%s%n%n%s%s"
            .formatted(
                rename.newName(),
                rename.totalEdits(),
                rename.files().size(),
                rename.files().stream()
                    .map(McpText::fileEditLine)
                    .collect(Collectors.joining(System.lineSeparator())),
                RENAME_AUTHORITY,
                mentionLines(rename));
    return result(text, notes);
  }

  // The rename edits no text; the agent decides about each place the old name still stands.
  static String mentionLines(final LatheRename rename) {
    if (rename.mentionCount() == 0) {
      return "";
    }

    final String more =
        rename.mentionCount() > rename.mentions().size()
            ? " (showing first %d)".formatted(rename.mentions().size())
            : "";
    return "%n%nNot renamed — review: the old name is still text in %d place(s)%s:%n%s"
        .formatted(rename.mentionCount(), more, locationLines(rename.mentions()));
  }

  // A null format: the file was already formatted.
  static CallToolResult formatResult(
      final Path file, final LatheFormat format, final LatheNotes notes) {
    if (format == null) {
      return result("%s was already formatted.".formatted(file.getFileName()), notes);
    }

    return result("Formatted %s: %s changed.".formatted(format.path(), lineSpan(format)), notes);
  }

  // The path once in the header: each line carries only its line number and declaration.
  static CallToolResult outlineResult(
      final Path file, final List<LatheOutlineItem> items, final LatheNotes notes) {
    final String text =
        items.isEmpty()
            ? "No declarations in %s.".formatted(file.getFileName())
            : "%d declaration(s) in %s:%n%s"
                .formatted(
                    items.size(),
                    items.get(0).location().path(),
                    items.stream()
                        .map(McpText::outlineLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(text, notes);
  }

  // line:column of the name, the position the other tools take.
  static String outlineLine(final LatheOutlineItem item) {
    return "%s%s %s  %d:%d: %s"
        .formatted(
            "  ".repeat(item.depth()),
            item.kind(),
            item.name(),
            line(item.location()),
            toWire(item.location().column()),
            item.location().snippet());
  }

  // The notes say why the list may be empty or stale: a .lathe/ from another Lathe version loads no
  // modules, and a pending build-file change awaits a sync.
  static CallToolResult modulesResult(
      final List<LatheModuleSummary> modules, final LatheNotes notes) {
    final String text =
        "%d module(s); build them with `%s`, one with `%s`.%n%s%n%nCapture a module's tests for run_test with `%s`."
            .formatted(
                modules.size(),
                LatheLayout.SYNC_SCRIPT_COMMAND,
                LatheLayout.scopedSyncCommand(List.of("<module>")),
                modules.stream()
                    .map(McpText::moduleLine)
                    .collect(Collectors.joining(System.lineSeparator())),
                LatheLayout.captureTestsCommand("<module>"));
    return result(text, notes);
  }

  static String moduleLine(final LatheModuleSummary module) {
    final String upstream =
        module.dependsOn().isEmpty()
            ? ""
            : "  depends on: %s".formatted(listed(module.dependsOn()));
    final String tests =
        !module.hasTests()
            ? ""
            : "  tests: %s".formatted(module.testsCaptured() ? "captured" : "not captured");
    return "%s%s%s".formatted(module.module(), upstream, tests);
  }

  static CallToolResult importsResult(final LatheImports imports, final LatheNotes notes) {
    final List<MissingImport> ambiguous =
        imports.unresolved().stream().filter(item -> !item.candidates().isEmpty()).toList();
    final List<String> unknown =
        imports.unresolved().stream()
            .filter(item -> item.candidates().isEmpty())
            .map(item -> item.name() + nestedHint(item))
            .toList();
    final String added =
        imports.added().isEmpty()
            ? "No import added to %s.".formatted(imports.path())
            : "Added %d import(s) to %s: %s"
                .formatted(
                    imports.added().size(), imports.path(), String.join(", ", imports.added()));
    final String text =
        Stream.of(
                Optional.of(added),
                Optional.of(ambiguous)
                    .filter(items -> !items.isEmpty())
                    .map(
                        items ->
                            "Ambiguous — add one yourself:%n%s"
                                .formatted(
                                    items.stream()
                                        .map(McpText::choiceLine)
                                        .collect(Collectors.joining(System.lineSeparator())))),
                Optional.of(unknown)
                    .filter(names -> !names.isEmpty())
                    .map(names -> "No import found for: %s".formatted(String.join(", ", names))))
            .flatMap(Optional::stream)
            .collect(Collectors.joining(System.lineSeparator()));
    return result(text, notes);
  }

  static String choiceLine(final MissingImport item) {
    return "  %s: %s%s"
        .formatted(item.name(), String.join(", ", item.candidates()), nestedHint(item));
  }

  // A nested type is not imported itself, so it is named as another way to write the name.
  private static String nestedHint(final MissingImport item) {
    return item.nested().isEmpty()
        ? ""
        : " (nested type %s: qualify it from its outer type)"
            .formatted(String.join(", ", item.nested()));
  }

  static String lineSpan(final LatheFormat format) {
    return format.firstLine() == format.lastLine()
        ? "line %d".formatted(toWire(format.firstLine()))
        : "lines %d–%d".formatted(toWire(format.firstLine()), toWire(format.lastLine()));
  }

  // An empty reactor-only answer says what the scope left out: the method may call only the JDK.
  static CallToolResult callHierarchyResult(
      final LatheCallHierarchy ch, final SymbolScope scope, final LatheNotes notes) {
    // Outgoing entries are call sites: a callee called twice is two.
    final String noun = ch.incoming() ? "caller" : "call";
    final String arrow = ch.incoming() ? "←" : "→";
    final String header =
        ch.truncated()
            ? "%d %ss (showing first %d):".formatted(ch.total(), noun, ch.calls().size())
            : "%d %s(s):".formatted(ch.total(), noun);
    final String none =
        !ch.incoming() && scope == SymbolScope.REACTOR
            ? "No callees in the reactor; scope=all includes calls into the JDK and dependencies."
            : "No %ss found.".formatted(noun);
    final String text =
        ch.calls().isEmpty()
            ? none
            : "%s%n%s"
                .formatted(
                    header,
                    ch.calls().stream()
                        .map(call -> callLine(arrow, call))
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(text, notes);
  }

  static CallToolResult describeResult(final String markup, final LatheNotes notes) {
    final String text = markup.isBlank() ? "No symbol information at that position." : markup;
    return result(text, notes);
  }

  static CallToolResult searchResult(
      final String query, final List<LatheSymbol> symbols, final LatheNotes notes) {
    final String text =
        symbols.isEmpty()
            ? "No symbols matching '%s'.".formatted(query)
            : "%d symbol(s):%n%s"
                .formatted(
                    symbols.size(),
                    symbols.stream()
                        .map(McpText::symbolLine)
                        .collect(Collectors.joining(System.lineSeparator())));
    return result(text, notes);
  }

  static String symbolLine(final LatheSymbol symbol) {
    final String where = symbol.container().isBlank() ? "" : " — %s".formatted(symbol.container());
    return "%s (%s)%s  %s"
        .formatted(symbol.name(), symbol.kind(), where, position(symbol.location()));
  }

  static String callLine(final String arrow, final LatheCall call) {
    return "%s %s  %s".formatted(arrow, call.name(), locationLine(call.location()));
  }

  static CallToolResult testRunResult(final LatheTestRun run, final LatheNotes notes) {
    if (!run.launched()) {
      return result(
          "Test run blocked: %s".formatted(String.join("; ", run.blockedReasons())), notes);
    }

    // No test reported back: the JVM failed before (a test that does not compile, a broken
    // launch), so its exit code and last lines say why, not a "PASS" over nothing.
    if (run.total() == 0) {
      return result(
          "No test ran (exit %d)%nRan: %s%s"
              .formatted(run.exitCode(), String.join(", ", run.ran()), outputLines(run.output())),
          notes);
    }

    final String header =
        "%s — %d passed, %d failed, %d skipped%nRan: %s"
            .formatted(
                run.failed() == 0 ? "PASS" : "FAIL",
                run.passed(),
                run.failed(),
                run.skipped(),
                String.join(", ", run.ran()));
    return result(
        header
            + run.failures().stream().map(McpText::testFailureLines).collect(Collectors.joining())
            + run.notes().stream().map("%nNote: %s"::formatted).collect(Collectors.joining()),
        notes);
  }

  static String testFailureLines(final LatheTestFailure f) {
    return "%n  %s:%d — %s%s".formatted(f.test(), f.line(), f.summary(), outputLines(f.output()));
  }

  static String outputLines(final List<String> output) {
    return output.stream().map(line -> "%n    | %s".formatted(line)).collect(Collectors.joining());
  }

  static CallToolResult analyzeResult(final LatheChangeImpact impact, final LatheNotes notes) {
    final String overrides =
        impact.overrideFamily().isEmpty()
            ? "none"
            : "%d%n%s"
                .formatted(impact.overrideFamily().size(), locationLines(impact.overrideFamily()));
    final String text =
        String.join(
            System.lineSeparator(),
            impact.signature().isBlank() ? "(no symbol at that position)" : impact.signature(),
            "references: %d production, %d test%s"
                .formatted(
                    impact.productionRefs(),
                    impact.testRefs(),
                    impact.referencesTruncated() ? " (truncated)" : ""),
            "affected modules: %s".formatted(joinOrNone(impact.affectedModules())),
            "relevant tests: %s".formatted(joinOrNone(impact.relevantTests())),
            "override family: %s".formatted(overrides));
    return result(text, notes);
  }

  static String joinOrNone(final List<String> items) {
    return items.isEmpty() ? "none" : String.join(", ", items);
  }

  static final LatheNotes NO_NOTES = LatheNotes.ofNotChecked(List.of());

  static CallToolResult verifyChangeResult(final LatheVerifyChange verify, final LatheNotes notes) {
    if (verify.deferral() != null) {
      // Another server writing .lathe/ ends on its own, so no sync is named then.
      final String next =
          verify.suggestedSync() == null
              ? "Re-check in a moment."
              : "Run `%s`, then re-check.".formatted(verify.suggestedSync());
      return result(
          "Verification deferred: %s. %s".formatted(deferralText(verify.deferral()), next), notes);
    }

    final int total = verify.perModule().stream().mapToInt(m -> m.diagnostics().size()).sum();
    final long errors = errorCount(verify);
    return result(
        verifyHeadline(errors, total - errors, notes.notChecked().size())
            + verifyDiagnosticLines(verify.perModule())
            + crossModuleNote(verify, errors),
        notes);
  }

  // Callers report errors only, so every warning is in a changed file. A file it could not check
  // must not read as compiling.
  static String verifyHeadline(final long errors, final long warnings, final int unchecked) {
    final String warned =
        warnings == 0 ? "" : "; %d warning(s) in the changed files".formatted(warnings);
    if (errors == 0 && unchecked > 0) {
      return "No errors in what was checked%s; %d named file(s) not checked."
          .formatted(warned, unchecked);
    }

    return errors == 0
        ? "No errors — the changed files and their callers compile%s.".formatted(warned)
        : "%d error(s)%s.".formatted(errors, warned);
  }

  static long errorCount(final LatheVerifyChange verify) {
    return verify.perModule().stream()
        .flatMap(module -> module.diagnostics().stream())
        .filter(located -> located.severity() == LatheDiagnostic.Severity.ERROR)
        .count();
  }

  // A change to a core module names over a hundred dependents; the count and the sync command carry
  // what matters.
  static String crossModuleNote(final LatheVerifyChange verify, final long errors) {
    if (verify.suggestedSync() == null) {
      return "";
    }

    final List<String> modules = verify.affectedModules();
    final String advice =
        errors == 0
            ? "their callers compile, so a full build is optional"
            : "fix the errors and re-check, or build them with";
    return "%n%n%d module(s) depend on your change (%s); %s:%n  %s"
        .formatted(modules.size(), listed(modules), advice, verify.suggestedSync());
  }

  // A core module has a hundred dependents: the first few name the shape.
  static String listed(final List<String> modules) {
    final String first = modules.stream().limit(LISTED_MODULES).collect(Collectors.joining(", "));
    return modules.size() > LISTED_MODULES
        ? "%s, and %d more".formatted(first, modules.size() - LISTED_MODULES)
        : first;
  }

  // One flat list, errors first: each line's path already starts with its module.
  static String verifyDiagnosticLines(final List<LatheModuleDiagnostics> perModule) {
    return perModule.stream()
        .flatMap(module -> module.diagnostics().stream())
        .sorted(Comparator.comparing(LatheDiagnostic::severity))
        .map(located -> System.lineSeparator() + locatedDiagnosticLine(located))
        .collect(Collectors.joining());
  }

  // grep-style, like a location: path:line:column: severity: message.
  static String locatedDiagnosticLine(final LatheDiagnostic located) {
    return "%s:%d:%d: %s: %s"
        .formatted(
            located.path(),
            toWire(located.line()),
            toWire(located.column()),
            located.severity().label(),
            located.message());
  }

  static String deferralText(final ReconcileOutcome.DeferReason reason) {
    return switch (reason) {
      case BUILD_FILES_PENDING -> "a pending build-file or dependency change needs a sync";
      case BUILD_IN_PROGRESS -> "a build is in progress";
      case OTHER_SERVER_WRITING -> "another Lathe server on this workspace is updating .lathe/";
      case NONE -> "no reason";
    };
  }

  static CallToolResult diagnosticsResult(
      final Path file, final List<LatheDiagnostic> diagnostics, final LatheNotes notes) {
    return result(diagnosticsText(file, diagnostics, notes), notes);
  }

  // Never "compiles cleanly": no module may compile the file, and the analysis is not the write
  // (whose failure the "Not compiled:" note reports).
  static String diagnosticsText(
      final Path file, final List<LatheDiagnostic> diagnostics, final LatheNotes notes) {
    if (!notes.notChecked().isEmpty()) {
      return "%s was not checked.".formatted(file.getFileName());
    }

    if (diagnostics.isEmpty()) {
      return "No errors or warnings in %s.".formatted(file.getFileName());
    }

    return "%d diagnostic(s) in %s:%n%s"
        .formatted(
            diagnostics.size(),
            file.getFileName(),
            diagnostics.stream()
                .map(McpText::diagnosticLine)
                .collect(Collectors.joining(System.lineSeparator())));
  }

  // An empty answer into the JDK says what to install, not just that nothing was found.
  static CallToolResult definitionResult(final LatheDefinition definition, final LatheNotes notes) {
    final String text =
        definition.targets().isEmpty()
            ? definition.jdkSourcesNote().orElse("No definition found.")
            : locationLines(definition.targets());
    return result(text, notes);
  }

  static String locationLines(final List<LatheLocation> locations) {
    return locations.stream()
        .map(McpText::locationLine)
        .collect(Collectors.joining(System.lineSeparator()));
  }

  // grep-style, the form an agent already reads: one line per location.
  static String locationLine(final LatheLocation location) {
    return "%s: %s".formatted(position(location), location.snippet());
  }

  static String position(final LatheLocation location) {
    return "%s:%d%s".formatted(location.path(), line(location), originTag(location.origin()));
  }

  static int line(final LatheLocation location) {
    return toWire(location.line());
  }

  // Most answers are reactor code, so only the exceptions are tagged.
  static String originTag(final LatheLocation.Origin origin) {
    return origin == LatheLocation.Origin.REACTOR ? "" : " [%s]".formatted(origin);
  }

  // Text only: several clients give the model a result's structured content in place of its text,
  // and the notes are only in the text.
  static CallToolResult result(final String text, final LatheNotes notes) {
    final String body =
        Stream.concat(Stream.of(text), noteLines(notes))
            .collect(Collectors.joining(System.lineSeparator().repeat(2)));
    return CallToolResult.builder().addTextContent(body).build();
  }

  // What an answer cannot vouch for: code the agent must fix (no sync helps), what only a sync can
  // fix, and a build running meanwhile.
  static Stream<String> noteLines(final LatheNotes notes) {
    return Stream.of(
            Optional.of(notes.notCompiled())
                .filter(files -> !files.isEmpty())
                .map(
                    files ->
                        "Not compiled: %s — fix the code; cross-module answers use their last good classes."
                            .formatted(String.join("; ", files))),
            Optional.of(notes.syncRequired())
                .filter(reasons -> !reasons.isEmpty())
                .map(reasons -> "Sync required: %s".formatted(String.join("; ", reasons))),
            Optional.of(notes.notChecked())
                .filter(files -> !files.isEmpty())
                .map(files -> "Not checked: %s".formatted(String.join("; ", files))),
            Optional.of(
                    "A build is running: this answer reflects Lathe's classes from before it; re-query when it ends.")
                .filter(text -> notes.buildInProgress()))
        .flatMap(Optional::stream);
  }

  static String fileEditLine(final LatheFileEdit f) {
    return "  %s%s — %d edit(s)".formatted(f.path(), originTag(f.origin()), f.editCount());
  }

  static String diagnosticLine(final LatheDiagnostic d) {
    return "  %s [%d:%d] %s"
        .formatted(d.severity().label(), toWire(d.line()), toWire(d.column()), d.message());
  }

  // Agents read 1-based lines and columns; the server's positions are 0-based.
  static int toWire(final int zeroBased) {
    return zeroBased + 1;
  }

  static CallToolResult error(final String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }
}
