package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.analysis.testing.TempSourceCompiler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
import org.junit.jupiter.api.io.TempDir;

// Code actions over a session compiling temp sources, with the helpers the provider families share.
abstract class CodeActionTestBase {
  @TempDir protected Path tmp;

  protected WorkspaceTypeIndex typeIndex;
  protected SourceAnalysisSession session;

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

  // Asserts the `«…»` selection in the member offers an action titled with the prefix, and that
  // applying it leaves code that compiles; returns that code.
  protected String assertExtracts(final String member, final String titlePrefix) {
    final String source = inTestClass(member);
    final var actions = markedActions(source);
    assertThat(rightTitles(actions)).as(member).anyMatch(t -> t.startsWith(titlePrefix));
    final String rewritten =
        applyEdits(unmarked(source), editsOfActionStartingWith(actions, titlePrefix));
    assertCompilesClean(rewritten);
    return rewritten;
  }

  protected static String inTestClass(final String member) {
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
  protected List<Either<Command, CodeAction>> markedActions(final String marked) {
    final int start = marked.indexOf('«');
    final int end = marked.indexOf('»') - 1;
    final String source = unmarked(marked);
    final Position from = positionOf(source, start);
    final Position to = positionOf(source, end);
    return extractActionsSpanning(
        source, from.getLine(), from.getCharacter(), to.getLine(), to.getCharacter());
  }

  protected static String unmarked(final String marked) {
    return marked.replace("«", "").replace("»", "");
  }

  protected static Position positionOf(final String source, final int offset) {
    final int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
    final int line = (int) source.substring(0, lineStart).chars().filter(c -> c == '\n').count();
    return new Position(line, offset - lineStart);
  }

  protected static List<TextEdit> addCtorEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Add constructor parameter");
  }

  protected void assertCompilesClean(final String source) {
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 99, CompileMode.OPEN);
    assertThat(diags)
        .filteredOn(d -> d.getSeverity() == DiagnosticSeverity.Error)
        .extracting(Diagnostic::getMessage)
        .isEmpty();
  }

  protected static int count(final String haystack, final String needle) {
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
  protected static String applyEdits(final String source, final List<TextEdit> edits) {
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

  protected static int offsetOf(final String source, final Position position) {
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

  protected List<Either<Command, CodeAction>> replaceVarActionsAt(
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

  protected List<Either<Command, CodeAction>> extractActionsSpanning(
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

  protected static List<TextEdit> editsOfActionStartingWith(
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

  protected List<Either<Command, CodeAction>> diagnosticActions(
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

  protected static Range rangeAt(final int line, final int character) {
    final var pos = new Position(line, character);
    return new Range(pos, pos);
  }

  protected static List<String> rightTitles(final List<Either<Command, CodeAction>> actions) {
    return actions.stream().filter(Either::isRight).map(a -> a.getRight().getTitle()).toList();
  }

  protected static List<CodeActionRequest> toRequests(final List<Diagnostic> diags) {
    return diags.stream()
        .filter(d -> d.getData() instanceof DiagnosticPayload)
        .map(
            d ->
                new CodeActionRequest(
                    TempSourceCompiler.TEST_URI, d, (DiagnosticPayload) d.getData()))
        .toList();
  }
}
