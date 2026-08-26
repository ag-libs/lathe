package io.github.aglibs.lathe.server.analysis;

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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import javax.tools.StandardJavaFileManager;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.jupiter.api.Test;

class SourceAnalysisSessionTest {

  @Test
  void safeCompile_wrappedError_rethrowsFatalCause() throws IOException {
    final JavacTask task = mock(JavacTask.class);
    final var expected = new AssertionError("expected");
    when(task.parse()).thenReturn(List.of());
    when(task.analyze()).thenThrow(new IllegalStateException(expected));

    assertThatThrownBy(() -> JavaSourceCompiler.safeCompile(task)).isSameAs(expected);
  }

  @Test
  void searchReferences_cancelledAfterCompile_doesNotCacheAnalysis() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Test {}";
    final var compiler = mock(JavaSourceCompiler.class);
    final var result =
        new CompilerResult(List.of(), AttributedFileAnalysis.diagnosticsOnly(), Set.of());
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

  @Test
  void hover_staleCache_recompilesAndReturnsHover() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String cachedContent = "class Test { String value; }";
    final String currentContent = "class Test { Integer value; }";
    final var manifest = WorkspaceManifest.empty();
    final var pos =
        SourceLocator.offsetToPosition(currentContent, currentContent.indexOf("Integer"));

    try (var ctx = new SourceAnalysisSession(new TempSourceCompiler())) {
      ctx.compile(uri, cachedContent, 1, CompileMode.OPEN);

      assertThat(
              ctx.hover(new SourceFeatureRequest(uri, currentContent, 0, pos, List.of(), manifest)))
          .isNotNull();
    }
  }

  @Test
  void semanticTokens_afterEviction_recompilesAndReturnsTokens() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Test { String value; }";
    final var compiler = new CountingJavaSourceCompiler();

    try (var ctx = new SourceAnalysisSession(compiler)) {
      ctx.compile(uri, content, 1, CompileMode.OPEN);
      ctx.dropFromCache(uri);

      assertThat(ctx.semanticTokens(uri, content, 1)).isNotNull();
      assertThat(compiler.count(CompileMode.OPEN)).isEqualTo(2);
    }
  }

  @Test
  void semanticTokens_versionMismatch_recompiles() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Test { String value; }";
    final var compiler = new CountingJavaSourceCompiler();

    try (var ctx = new SourceAnalysisSession(compiler)) {
      ctx.compile(uri, content, 1, CompileMode.OPEN);

      assertThat(ctx.semanticTokens(uri, content, 2)).isNotNull();
      assertThat(compiler.count(CompileMode.OPEN)).isEqualTo(2);
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

  @Test
  void enclosingBinaryName_attributedNesting_resolvesTopNestedAndAnonymous() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String source =
        """
        class Outer {
          Runnable r =
              new Runnable() {
                public void run() {
                  int b = 2;
                }
              };

          void a() {
            int x = 1;
          }

          static class Inner {
            void c() {
              int y = 3;
            }
          }
        }
        """;

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(uri, source, 1, CompileMode.OPEN);

      assertThat(session.enclosingBinaryName(uri, 5)).contains("Outer$1");
      assertThat(session.enclosingBinaryName(uri, 11)).contains("Outer");
      assertThat(session.enclosingBinaryName(uri, 16)).contains("Outer$Inner");
    }
  }

  @Test
  void enclosingBinaryName_fileNotAttributed_returnsEmpty() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      assertThat(session.enclosingBinaryName(TempSourceCompiler.TEST_URI, 1)).isEmpty();
    }
  }

  private static final String EVAL_SAMPLE =
      """
      class Sample {
        int field = 3;

        void run(String arg) {
          int local = 5;
          System.out.println(arg);
        }
      }
      """;

  // The System.out.println line (1-based 6) is the breakpoint; arg, local, and field are in scope.
  private static final int EVAL_LINE = 6;

  // Attributes the expression at the sample's breakpoint line and runs the assertions on the
  // resolved node while the compile session is still open (Trees are valid only until it closes).
  private static void withAttribute(
      final String expression, final Consumer<AttributedExpression> assertions) {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final var attr =
          session.attributeExpression(
              TempSourceCompiler.TEST_URI, EVAL_SAMPLE, EVAL_LINE, expression);
      assertThat(attr).isPresent();
      assertions.accept(attr.get());
    }
  }

  private static TypeKind typeKind(final AttributedExpression e) {
    return e.trees().getTypeMirror(e.expression()).getKind();
  }

  private static Element element(final AttributedExpression e) {
    return e.trees().getElement(e.expression());
  }

  @Test
  void attributeExpression_localVariable_resolvesToLocalAndType() {
    withAttribute(
        "local",
        e -> {
          assertThat(element(e).getKind()).isEqualTo(ElementKind.LOCAL_VARIABLE);
          assertThat(element(e).getSimpleName().toString()).isEqualTo("local");
          assertThat(typeKind(e)).isEqualTo(TypeKind.INT);
        });
  }

  @Test
  void attributeExpression_field_resolvesToFieldInEnclosingClass() {
    withAttribute(
        "field",
        e -> {
          assertThat(element(e).getKind()).isEqualTo(ElementKind.FIELD);
          assertThat(element(e).getSimpleName().toString()).isEqualTo("field");
        });
  }

  @Test
  void attributeExpression_arithmetic_resolvesToPrimitiveType() {
    withAttribute("local + field", e -> assertThat(typeKind(e)).isEqualTo(TypeKind.INT));
  }

  @Test
  void attributeExpression_unresolvedName_attributesButTypeIsError() {
    withAttribute("nonexistent", e -> assertThat(typeKind(e)).isEqualTo(TypeKind.ERROR));
  }

  @Test
  void searchReferences_sourceReadFailure_throwsUncheckedIOException() throws IOException {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content =
        """
        class Test {
            String name;
            String get() { return name; }
        }
        """;

    // session.close() calls compiler.close(), so don't put compiler in try-with-resources
    final var compiler = new TempSourceCompiler();
    try (final var session = new SourceAnalysisSession(compiler)) {
      // Compile to get analysis for target construction and to warm the session cache
      final var analysis = compiler.compile(uri, content, CompileMode.OPEN).fileAnalysis();
      session.compile(uri, content, 1, CompileMode.OPEN);

      final var target =
          new ReferenceTarget(
              ElementKind.FIELD,
              "Test.name",
              "name",
              null,
              ReferenceTarget.SearchScope.DECLARING_FILE,
              List.of(),
              false);

      // Delete the source file to simulate a source-read failure
      final Path sourceFile = Path.of(analysis.tree().getSourceFile().toUri());
      Files.delete(sourceFile);

      assertThatThrownBy(() -> session.searchReferences(uri, content, 1, target, false))
          .isInstanceOf(UncheckedIOException.class);
    }
  }
}
