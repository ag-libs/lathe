package io.github.aglibs.lathe.server.analysis;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.source.util.JavacTask;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.jupiter.api.Test;

class SourceAnalysisSessionTest {

  @Test
  void safeCompile_wrappedOutOfMemory_rethrowsFatalCause() throws IOException {
    final JavacTask task = mock(JavacTask.class);
    final var expected = new OutOfMemoryError("expected");
    when(task.parse()).thenReturn(List.of());
    when(task.analyze()).thenThrow(new IllegalStateException(expected));

    assertThatThrownBy(() -> JavaSourceCompiler.safeCompile(task)).isSameAs(expected);
  }

  @Test
  void searchReferences_cancelledAfterCompile_doesNotCacheAnalysis() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Test {}";
    final var compiler = mock(JavaSourceCompiler.class);
    final var result = new CompilerResult(List.of(), AttributedFileAnalysis.diagnosticsOnly());
    final var cancelled = new AtomicBoolean();
    final CancelChecker cancelChecker =
        () -> {
          if (cancelled.get()) {
            throw new CancellationException();
          }
        };
    final ReferenceTarget target = mock(ReferenceTarget.class);
    when(compiler.compile(eq(uri), eq(content), eq(CompileMode.OPEN), any()))
        .thenAnswer(
            ignored -> {
              cancelled.set(true);
              return result;
            })
        .thenReturn(result);

    try (final var session = new SourceAnalysisSession(compiler)) {
      assertThatThrownBy(
              () -> session.searchReferences(uri, content, 1, target, false, cancelChecker))
          .isInstanceOf(CancellationException.class);
      cancelled.set(false);

      assertThat(session.searchReferences(uri, content, 1, target, false)).isEmpty();
      verify(compiler, times(2)).compile(eq(uri), eq(content), eq(CompileMode.OPEN), any());
    }
  }

  // --- offsetToPosition ---

  @Test
  void offsetToPosition_variousOffsets_returnsCorrectLineCol() {
    assertThat(offsetToPosition("hello", 0)).isEqualTo(new Position(0, 0));
    assertThat(offsetToPosition("hello world", 6)).isEqualTo(new Position(0, 6));
    assertThat(offsetToPosition("hello\nworld", 6)).isEqualTo(new Position(1, 0));
    assertThat(offsetToPosition("hello\nworld", 8)).isEqualTo(new Position(1, 2));
    assertThat(offsetToPosition("hi", 100)).isEqualTo(new Position(0, 2));
  }

  // --- toLsp: severity ---

  @Test
  void toLsp_error_mapsToError() {
    assertThat(diag(Diagnostic.Kind.ERROR, 0, 1).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
  }

  @Test
  void toLsp_warning_mapsToWarning() {
    assertThat(diag(Diagnostic.Kind.WARNING, 0, 1).getSeverity())
        .isEqualTo(DiagnosticSeverity.Warning);
  }

  @Test
  void toLsp_mandatoryWarning_mapsToWarning() {
    assertThat(diag(Diagnostic.Kind.MANDATORY_WARNING, 0, 1).getSeverity())
        .isEqualTo(DiagnosticSeverity.Warning);
  }

  @Test
  void toLsp_note_mapsToHint() {
    assertThat(diag(Diagnostic.Kind.NOTE, 0, 1).getSeverity()).isEqualTo(DiagnosticSeverity.Hint);
  }

  // --- toLsp: range with offsets ---

  @Test
  void toLsp_withStartAndEndOffsets_spansCorrectRange() {
    final var d = diag(Diagnostic.Kind.ERROR, 2, 7);
    assertThat(d.getRange().getStart()).isEqualTo(new Position(0, 2));
    assertThat(d.getRange().getEnd()).isEqualTo(new Position(0, 7));
  }

  @Test
  void toLsp_endEqualsStart_endIsStartPlusOne() {
    final var d = diag(Diagnostic.Kind.ERROR, 3, 3);
    assertThat(d.getRange().getStart()).isEqualTo(new Position(0, 3));
    assertThat(d.getRange().getEnd()).isEqualTo(new Position(0, 4));
  }

  @Test
  void toLsp_noPosition_fallsBackToLineCol() {
    final var raw = mockDiag(Diagnostic.Kind.ERROR, Diagnostic.NOPOS, Diagnostic.NOPOS);
    when(raw.getLineNumber()).thenReturn(2L);
    when(raw.getColumnNumber()).thenReturn(5L);
    final var d = SourceAnalysisSession.toLsp(raw, "anything");
    assertThat(d.getRange().getStart()).isEqualTo(new Position(1, 4));
    assertThat(d.getRange().getEnd()).isEqualTo(new Position(1, 5));
  }

  // --- filterAndMap: NOTE filtering ---

  @Test
  void filterAndMap_positionlessNote_isDropped() {
    final var note = mockDiag(Diagnostic.Kind.NOTE, Diagnostic.NOPOS, Diagnostic.NOPOS);
    assertThat(SourceAnalysisSession.filterAndMap(List.of(note), "")).isEmpty();
  }

  @Test
  void filterAndMap_noteWithPosition_isKept() {
    final var note = mockDiag(Diagnostic.Kind.NOTE, 0L, 1L);
    assertThat(SourceAnalysisSession.filterAndMap(List.of(note), "x")).hasSize(1);
  }

  @Test
  void filterAndMap_errorAndPositionlessNote_returnsOnlyError() {
    final var error = mockDiag(Diagnostic.Kind.ERROR, 0L, 1L);
    final var note = mockDiag(Diagnostic.Kind.NOTE, Diagnostic.NOPOS, Diagnostic.NOPOS);
    final var result = SourceAnalysisSession.filterAndMap(List.of(error, note), "x");
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getSeverity()).isEqualTo(DiagnosticSeverity.Error);
  }

  @Test
  void hover_staleCache_returnsNull() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String cachedContent = "class Test { String value; }";
    final String currentContent = "class Test { Integer value; }";
    final var manifest = WorkspaceManifest.empty();
    final var pos =
        SourceLocator.offsetToPosition(currentContent, currentContent.indexOf("Integer"));

    try (var ctx = new SourceAnalysisSession(new TempSourceCompiler())) {
      ctx.compile(uri, cachedContent, 1, CompileMode.OPEN);

      assertThat(ctx.hover(new SourceFeatureRequest(uri, currentContent, pos, List.of(), manifest)))
          .isNull();
    }
  }

  @Test
  void documentSymbol_withoutCompile_returnsParseOnlySymbols() {
    final var compiler = mock(JavaSourceCompiler.class);
    final var fileManager = mock(StandardJavaFileManager.class);
    when(compiler.fileManager()).thenReturn(fileManager);
    final String source = "class Test { String field; void method() {} }";

    final var session = new SourceAnalysisSession(compiler);
    final var symbols = session.documentSymbol(TempSourceCompiler.TEST_URI, source);

    assertThat(symbols).extracting(DocumentSymbol::getKind).containsExactly(SymbolKind.Class);
    assertThat(symbols.getFirst().getChildren())
        .extracting(DocumentSymbol::getName)
        .containsExactly("field", "method");
    verify(compiler, never()).compile(TempSourceCompiler.TEST_URI, source, CompileMode.OPEN);
    verify(compiler, never()).compile(TempSourceCompiler.TEST_URI, source, CompileMode.FAST);
    verify(compiler, never()).compile(TempSourceCompiler.TEST_URI, source, CompileMode.FULL);
    session.close();
  }

  @Test
  void foldingRange_withoutCompile_returnsParseOnlyRanges() {
    final var compiler = mock(JavaSourceCompiler.class);
    final var fileManager = mock(StandardJavaFileManager.class);
    when(compiler.fileManager()).thenReturn(fileManager);
    final String source =
        """
        import java.util.List;
        import java.util.Map;

        class Test {
        }
        """;

    final var session = new SourceAnalysisSession(compiler);
    final var ranges = session.foldingRange(TempSourceCompiler.TEST_URI, source);

    assertThat(ranges).extracting(FoldingRange::getKind).contains(FoldingRangeKind.Imports);
    verify(compiler, never()).compile(TempSourceCompiler.TEST_URI, source, CompileMode.OPEN);
    verify(compiler, never()).compile(TempSourceCompiler.TEST_URI, source, CompileMode.FAST);
    verify(compiler, never()).compile(TempSourceCompiler.TEST_URI, source, CompileMode.FULL);
    session.close();
  }

  @Test
  void structuralNavigation_incompleteSource_returnsBestEffortResults() {
    final String source = "class Test { void method() {";

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      assertThat(session.documentSymbol(TempSourceCompiler.TEST_URI, source))
          .extracting(DocumentSymbol::getName)
          .contains("Test");
      assertThat(session.foldingRange(TempSourceCompiler.TEST_URI, source)).isNotNull();
    }
  }

  @Test
  void compile_unknownTypeInDeclarationAndConstructor_singleErrorOnLine() {
    // Gap: javac emits two separate "cannot find symbol" errors when the same unresolved type
    // appears in both the variable-type position and the constructor call on the same line.
    final var source =
        """
        class Test {
          public void method() {
            UnknownType foo = new UnknownType();
          }
        }
        """;

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final var diags = session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

      final long errorsOnDeclarationLine =
          diags.stream()
              .filter(d -> d.getSeverity() == DiagnosticSeverity.Error)
              .filter(d -> d.getRange().getStart().getLine() == 2)
              .count();

      assertThat(errorsOnDeclarationLine).isEqualTo(1);
    }
  }

  // --- helpers ---

  private static org.eclipse.lsp4j.Diagnostic diag(
      final Diagnostic.Kind kind, final long start, final long end) {
    return SourceAnalysisSession.toLsp(mockDiag(kind, start, end), "hello world");
  }

  @SuppressWarnings("unchecked")
  private static Diagnostic<JavaFileObject> mockDiag(
      final Diagnostic.Kind kind, final long start, final long end) {
    final var d = (Diagnostic<JavaFileObject>) mock(Diagnostic.class);
    when(d.getKind()).thenReturn(kind);
    when(d.getStartPosition()).thenReturn(start);
    when(d.getEndPosition()).thenReturn(end);
    when(d.getPosition()).thenReturn(start);
    when(d.getMessage(Locale.ENGLISH)).thenReturn("msg");
    return d;
  }
}
