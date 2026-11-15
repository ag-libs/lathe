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
import io.github.aglibs.lathe.server.engine.LatheReference;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheRename;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.LatheTestFailure;
import io.github.aglibs.lathe.server.engine.LatheTestRun;
import io.github.aglibs.lathe.server.engine.LatheVerifyChange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// The text of every MCP answer: what an agent reads back from a tool, built from the engine's
// results and the notes on the files they cover.
final class McpText {

  private static final int LISTED_MODULES = 10;
  // Past this many declarations an outline is cut to the file's types, and one type's members lose
  // their code: whole, a 400-declaration file answered 57k characters, which the client saved to a
  // file instead of showing.
  static final int OUTLINE_MAX_DECLARATIONS = 200;
  // The most lines any outline answer lists, about 21k characters even with their code left out.
  static final int OUTLINE_MAX_LINES = 600;
  // The document symbol kinds of a class, an interface or annotation, an enum and a record.
  private static final Set<String> TYPE_KINDS = Set.of("class", "interface", "enum", "struct");

  private McpText() {}

  // Authority lines appended to semantic-result text so the agent trusts the result instead of
  // re-verifying with grep (the "distrust tax"). True by construction for Java: the search is
  // javac-resolved; Lathe reads no Kotlin, so where there is some the grep advice is left out.
  private static final String REFERENCES_AUTHORITY =
      "All javac-resolved references to this exact declaration; same-named but unrelated symbols are"
          + " already excluded";
  private static final String IMPLEMENTATIONS_AUTHORITY =
      "All javac-resolved implementers/overriders across modules; unrelated same-named types are"
          + " excluded";
  private static final String NO_GREP_NEEDED = " — no need to re-verify with grep.";
  private static final String RENAME_AUTHORITY =
      "Only javac-resolved uses of this declaration were renamed (overload- and scope-correct);"
          + " same-named unrelated symbols were left untouched.";

  static CallToolResult referencesResult(final LatheReferences refs, final LatheNotes notes) {
    return locationListResult(
        "reference",
        refs.total(),
        refs.truncated(),
        refs.references().stream().map(McpText::referenceLine).toList(),
        notes,
        vouched(REFERENCES_AUTHORITY, notes));
  }

  // The access goes after the position, which the agent copies into the next call as it is.
  static String referenceLine(final LatheReference reference) {
    final String tag =
        switch (reference.access()) {
          case READ -> " [read]";
          case WRITE -> " [write]";
          case OTHER -> "";
        };
    return "%s%s: %s"
        .formatted(position(reference.location()), tag, reference.location().snippet());
  }

  static CallToolResult implementationsResult(
      final LatheImplementations impls, final LatheNotes notes) {
    return locationListResult(
        "implementation",
        impls.total(),
        impls.truncated(),
        impls.implementations().stream().map(McpText::locationLine).toList(),
        notes,
        vouched(IMPLEMENTATIONS_AUTHORITY, notes));
  }

  static String vouched(final String authority, final LatheNotes notes) {
    return notes.kotlinModules().isEmpty() ? authority + NO_GREP_NEEDED : authority + ".";
  }

  // An answer about a symbol's uses: in a workspace with Kotlin sources, which Lathe does not read,
  // it names the modules to check with grep.
  static CallToolResult usesResult(final String text, final LatheNotes notes) {
    return result(
        notes.kotlinModules().isEmpty()
            ? text
            : "%s%n%nKotlin sources in %s are not searched; check them with grep."
                .formatted(text, listed(notes.kotlinModules())),
        notes);
  }

  // Shared rendering for a capped, ranked location list ("N noun(s)"); authority is appended when
  // the list is non-empty.
  static CallToolResult locationListResult(
      final String noun,
      final int total,
      final boolean truncated,
      final List<String> lines,
      final LatheNotes notes,
      final String authority) {
    final String header =
        truncated
            ? "%d %ss (showing first %d):".formatted(total, noun, lines.size())
            : "%d %s(s):".formatted(total, noun);
    final String text =
        lines.isEmpty()
            ? "No %ss found.".formatted(noun)
            : "%s%n%s%n%n%s"
                .formatted(header, String.join(System.lineSeparator(), lines), authority);
    return usesResult(text, notes);
  }

  static CallToolResult renameResult(final LatheRename rename, final LatheNotes notes) {
    if (rename.totalEdits() == 0) {
      return usesResult("No references to rename.", notes);
    }

    final String text =
        "Renamed to %s: %d edit(s) across %d file(s):%n%s%n%n%s%s%s"
            .formatted(
                rename.newName(),
                rename.totalEdits(),
                rename.files().size(),
                rename.files().stream()
                    .map(McpText::fileEditLine)
                    .collect(Collectors.joining(System.lineSeparator())),
                RENAME_AUTHORITY,
                mentionLines(rename),
                outsideLines(rename));
    return usesResult(text, notes);
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

  // A module the build's profiles leave out is not synced, nor is a test fixture outside its
  // module's source roots, so the rename never saw these files.
  static String outsideLines(final LatheRename rename) {
    if (rename.outsideCount() == 0) {
      return "";
    }

    final String more =
        rename.outsideCount() > rename.outside().size()
            ? " (showing first %d)".formatted(rename.outside().size())
            : "";
    return "%n%nNot checked — %d Java file(s) outside every synced source root name it%s:%n%s"
        .formatted(rename.outsideCount(), more, locationLines(rename.outside()));
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
  // type: the simple name of the one type to outline, or null for the whole file.
  static CallToolResult outlineResult(
      final Path file,
      final List<LatheOutlineItem> items,
      final String type,
      final LatheNotes notes) {
    if (items.isEmpty()) {
      return result("No declarations in %s.".formatted(file.getFileName()), notes);
    }

    final var shape = OutlineShape.of(items);
    if (type != null) {
      return typeOutline(file, items, shape, type, notes);
    }

    final String path = items.get(0).location().path();
    if (items.size() > OUTLINE_MAX_DECLARATIONS) {
      return result(
          "%s is too large for one outline: %d declarations. Its types, with their own members; call outline with type=<Name> for one:%n%s"
              .formatted(path, items.size(), typeLines(items, shape)),
          notes);
    }

    return result(
        "%d declaration(s) in %s:%n%s"
            .formatted(
                items.size(),
                path,
                items.stream()
                    .map(McpText::outlineLine)
                    .collect(Collectors.joining(System.lineSeparator()))),
        notes);
  }

  // Each type named so: its line and its own members, a nested type as one line with its member
  // count. Members past the cap lose their code, as a type that large is the file itself.
  private static CallToolResult typeOutline(
      final Path file,
      final List<LatheOutlineItem> items,
      final OutlineShape shape,
      final String type,
      final LatheNotes notes) {
    final List<Integer> matches =
        shape.memberTypes().stream().filter(i -> items.get(i).name().equals(type)).toList();
    if (matches.isEmpty()) {
      return error(
          "No type %s in %s; its types:%n%s"
              .formatted(type, file.getFileName(), typeLines(items, shape)));
    }

    return result(
        matches.stream()
            .map(i -> typeBlock(items, shape, i))
            .collect(Collectors.joining(System.lineSeparator().repeat(2))),
        notes);
  }

  private static String typeBlock(
      final List<LatheOutlineItem> items, final OutlineShape shape, final int typeIndex) {
    final LatheOutlineItem type = items.get(typeIndex);
    final List<Integer> members = memberIndexes(items, typeIndex);
    final boolean compact = members.size() > OUTLINE_MAX_DECLARATIONS;
    final String header =
        "%s %s in %s, %d member(s)%s:"
            .formatted(
                type.kind(),
                type.name(),
                type.location().path(),
                members.size(),
                compact
                    ? " (their code left out: over %d)".formatted(OUTLINE_MAX_DECLARATIONS)
                    : "");
    return "%s%n%s%n%s"
        .formatted(
            header,
            outlineLine(type),
            cappedLines(items, members, i -> memberLine(items, shape, i, compact), "members"));
  }

  private static String memberLine(
      final List<LatheOutlineItem> items,
      final OutlineShape shape,
      final int index,
      final boolean compact) {
    final LatheOutlineItem member = items.get(index);
    if (isType(member)) {
      return typeLine(items, shape, index);
    }

    return compact ? positionLine(member) : outlineLine(member);
  }

  // The file's member types, indented by nesting, with their position and own member count; a
  // class declared in a method is no member of the type around it.
  private static String typeLines(final List<LatheOutlineItem> items, final OutlineShape shape) {
    return cappedLines(items, shape.memberTypes(), i -> typeLine(items, shape, i), "types");
  }

  private static String typeLine(
      final List<LatheOutlineItem> items, final OutlineShape shape, final int typeIndex) {
    return "%s  %d member(s)"
        .formatted(positionLine(items.get(typeIndex)), shape.memberCounts().get(typeIndex));
  }

  // At most OUTLINE_MAX_LINES lines, then how many more and the line they start at, from which the
  // agent can read the file: a generated enum or a parser's types can run into the thousands.
  private static String cappedLines(
      final List<LatheOutlineItem> items,
      final List<Integer> indexes,
      final IntFunction<String> line,
      final String noun) {
    final String shown =
        indexes.stream()
            .limit(OUTLINE_MAX_LINES)
            .map(line::apply)
            .collect(Collectors.joining(System.lineSeparator()));
    if (indexes.size() <= OUTLINE_MAX_LINES) {
      return shown;
    }

    return "%s%n… and %d more %s, from line %d"
        .formatted(
            shown,
            indexes.size() - OUTLINE_MAX_LINES,
            noun,
            line(items.get(indexes.get(OUTLINE_MAX_LINES)).location()));
  }

  // The declarations directly in the type: the items after it, one level deeper, until the outline
  // leaves it.
  private static List<Integer> memberIndexes(
      final List<LatheOutlineItem> items, final int typeIndex) {
    final int depth = items.get(typeIndex).depth();
    return IntStream.range(typeIndex + 1, items.size())
        .takeWhile(i -> items.get(i).depth() > depth)
        .filter(i -> items.get(i).depth() == depth + 1)
        .boxed()
        .toList();
  }

  private static boolean isType(final LatheOutlineItem item) {
    return TYPE_KINDS.contains(item.kind());
  }

  // memberTypes: the indexes of the types every enclosing declaration of which is a type, a local
  // class left out. memberCounts: each declaration's own members, by index. Both follow from which
  // declaration each one is in, found in one pass.
  private record OutlineShape(List<Integer> memberTypes, List<Integer> memberCounts) {
    // The parent of a top-level declaration.
    private static final int NONE = -1;

    OutlineShape {
      memberTypes = List.copyOf(memberTypes);
      memberCounts = List.copyOf(memberCounts);
    }

    static OutlineShape of(final List<LatheOutlineItem> items) {
      final List<Integer> parents = parents(items);
      final Map<Integer, Long> membersByParent =
          parents.stream()
              .filter(parent -> parent != NONE)
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
      return new OutlineShape(
          IntStream.range(0, items.size())
              .filter(i -> isMemberType(items, parents, i))
              .boxed()
              .toList(),
          IntStream.range(0, items.size())
              .mapToObj(i -> membersByParent.getOrDefault(i, 0L).intValue())
              .toList());
    }

    // A type whose enclosing declarations, up to the top, are all types.
    private static boolean isMemberType(
        final List<LatheOutlineItem> items, final List<Integer> parents, final int index) {
      return isType(items.get(index))
          && Stream.iterate(parents.get(index), parent -> parent != NONE, parents::get)
              .allMatch(parent -> isType(items.get(parent)));
    }

    // Each declaration's parent index: the outline lists a declaration right after the one it is
    // in, so the open declarations form a stack.
    private static List<Integer> parents(final List<LatheOutlineItem> items) {
      final List<Integer> parents = new ArrayList<>();
      final Deque<Integer> open = new ArrayDeque<>();
      for (int i = 0; i < items.size(); i++) {
        while (!open.isEmpty() && items.get(open.peek()).depth() >= items.get(i).depth()) {
          open.pop();
        }

        parents.add(open.isEmpty() ? NONE : open.peek());
        open.push(i);
      }

      return List.copyOf(parents);
    }
  }

  // An outline line without its code.
  private static String positionLine(final LatheOutlineItem item) {
    return "%s%s %s  %d:%d"
        .formatted(
            "  ".repeat(item.depth()),
            item.kind(),
            item.name(),
            line(item.location()),
            toWire(item.location().column()));
  }

  // line:column of the name, the position the other tools take.
  static String outlineLine(final LatheOutlineItem item) {
    return "%s: %s".formatted(positionLine(item), item.location().snippet());
  }

  // The notes say why the list may be empty or stale: a .lathe/ from another Lathe version loads no
  // modules, and a pending build-file change awaits a sync.
  static CallToolResult modulesResult(
      final List<LatheModuleSummary> modules, final LatheNotes notes) {
    final String text =
        "%d module(s); build them with `%s`, one with `%s`.%n%s%s%n%nCapture a module's tests for run_test with `%s`."
            .formatted(
                modules.size(),
                LatheLayout.SYNC_SCRIPT_COMMAND,
                LatheLayout.scopedSyncCommand(List.of("<module>")),
                modules.stream()
                    .map(McpText::moduleLine)
                    .collect(Collectors.joining(System.lineSeparator())),
                kotlinSourcesLine(notes),
                LatheLayout.captureTestsCommand("<module>"));
    return result(text, notes);
  }

  // Every module with Kotlin sources, which Lathe does not analyze: one of only Kotlin sources is
  // none Lathe compiles, so the list above leaves it out. All named, as the summary's point is
  // the names.
  static String kotlinSourcesLine(final LatheNotes notes) {
    return notes.kotlinModules().isEmpty()
        ? ""
        : "%n%nKotlin sources, which Lathe does not analyze, in: %s"
            .formatted(String.join(", ", notes.kotlinModules()));
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
    // Its callees are on the classpath, Kotlin ones too; its callers may be in unread Kotlin.
    return ch.incoming() ? usesResult(text, notes) : result(text, notes);
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

  // A method's family both ways: what it overrides, by name, as a JDK or dependency method has no
  // location in the answer, then what overrides it, and apart what redeclares it abstract. Any
  // other symbol keeps one neutral line: a type's subtypes override nothing.
  static CallToolResult analyzeResult(final LatheChangeImpact impact, final LatheNotes notes) {
    final String family =
        impact.method()
            ? "overrides: %s%noverridden by: %s%s"
                .formatted(
                    joinOrNone(impact.overrides()),
                    familyLines(impact.overriddenBy()),
                    impact.redeclaredBy().isEmpty()
                        ? ""
                        : "%nredeclared by: %s".formatted(familyLines(impact.redeclaredBy())))
            : "override family: %s".formatted(familyLines(impact.overriddenBy()));
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
            family);
    return usesResult(text, notes);
  }

  private static String familyLines(final List<LatheLocation> members) {
    return members.isEmpty() ? "none" : "%d%n%s".formatted(members.size(), locationLines(members));
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
      return usesResult(
          "Verification deferred: %s. %s".formatted(deferralText(verify.deferral()), next), notes);
    }

    final int total = verify.perModule().stream().mapToInt(m -> m.diagnostics().size()).sum();
    final long errors = errorCount(verify);
    return usesResult(
        "%s%s%s%s"
            .formatted(
                verifyHeadline(errors, total - errors, notes.notChecked().size()),
                verifyDiagnosticLines(verify.perModule()),
                failedWritesNote(verify),
                crossModuleNote(verify, errors)),
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

  // A changed file the build's own compile fails has its last good classes in .lathe/: its callers
  // were checked against its edited source instead, which the answer says.
  static String failedWritesNote(final LatheVerifyChange verify) {
    return verify.failedWrites().isEmpty()
        ? ""
        : "%n%nThe build's compile fails %s; its callers were checked against its edited source."
            .formatted(String.join(", ", verify.failedWrites()));
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

  // The column tells two hits on one line apart, and is the position the other tools take.
  static String position(final LatheLocation location) {
    return "%s:%d:%d%s"
        .formatted(
            location.path(),
            line(location),
            toWire(location.column()),
            originTag(location.origin()));
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
