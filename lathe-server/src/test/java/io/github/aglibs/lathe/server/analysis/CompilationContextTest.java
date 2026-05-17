package io.github.aglibs.lathe.server.analysis;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

class CompilationContextTest {

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
    final var d = CompilationContext.toLsp(raw, "anything");
    assertThat(d.getRange().getStart()).isEqualTo(new Position(1, 4));
    assertThat(d.getRange().getEnd()).isEqualTo(new Position(1, 5));
  }

  // --- filterAndMap: NOTE filtering ---

  @Test
  void filterAndMap_positionlessNote_isDropped() {
    final var note = mockDiag(Diagnostic.Kind.NOTE, Diagnostic.NOPOS, Diagnostic.NOPOS);
    assertThat(CompilationContext.filterAndMap(List.of(note), "")).isEmpty();
  }

  @Test
  void filterAndMap_noteWithPosition_isKept() {
    final var note = mockDiag(Diagnostic.Kind.NOTE, 0L, 1L);
    assertThat(CompilationContext.filterAndMap(List.of(note), "x")).hasSize(1);
  }

  @Test
  void filterAndMap_errorAndPositionlessNote_returnsOnlyError() {
    final var error = mockDiag(Diagnostic.Kind.ERROR, 0L, 1L);
    final var note = mockDiag(Diagnostic.Kind.NOTE, Diagnostic.NOPOS, Diagnostic.NOPOS);
    final var result = CompilationContext.filterAndMap(List.of(error, note), "x");
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getSeverity()).isEqualTo(DiagnosticSeverity.Error);
  }

  // --- helpers ---

  private static org.eclipse.lsp4j.Diagnostic diag(
      final Diagnostic.Kind kind, final long start, final long end) {
    return CompilationContext.toLsp(mockDiag(kind, start, end), "hello world");
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
