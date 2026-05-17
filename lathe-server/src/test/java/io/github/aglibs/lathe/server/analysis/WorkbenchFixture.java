package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.completion.CompletionPipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

public abstract class WorkbenchFixture {

  protected static final String SOURCE;
  protected static final TestCompiler.ParsedSource COMPILED;

  private static final String WORKBENCH_URI = "file:///workbench/Workbench.java";
  private static final TempSourceCompiler COMPILER = new TempSourceCompiler();
  private static final CompletionPipeline COMPLETION_PIPELINE = new CompletionPipeline(COMPILER);

  static {
    try {
      SOURCE =
          new String(
              Objects.requireNonNull(WorkbenchFixture.class.getResourceAsStream("/Workbench.java"))
                  .readAllBytes());
      final Path tempDir = Files.createTempDirectory("lathe-workbench-");
      final Path sourceFile = tempDir.resolve("Workbench.java");
      Files.writeString(sourceFile, SOURCE);
      COMPILED = TestCompiler.parse(sourceFile);
      tempDir.toFile().deleteOnExit();
      sourceFile.toFile().deleteOnExit();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  protected Position cursor(final String snippet, final String token) {
    return cursor(snippet, token, 0);
  }

  protected Position cursor(final String snippet, final String token, final int occurrence) {
    final int snippetStart = SOURCE.indexOf(snippet);
    if (snippetStart < 0) {
      throw new IllegalArgumentException("snippet not found in Workbench.java: [" + snippet + "]");
    }
    final int snippetEnd = snippetStart + snippet.length();
    int searchFrom = snippetStart;
    int tokenIdx = -1;
    for (int i = 0; i <= occurrence; i++) {
      tokenIdx = SOURCE.indexOf(token, searchFrom);
      if (tokenIdx < 0 || tokenIdx >= snippetEnd) {
        throw new IllegalArgumentException(
            "token [" + token + "] occurrence " + i + " not found in snippet [" + snippet + "]");
      }
      searchFrom = tokenIdx + token.length();
    }
    return toPosition(SOURCE, tokenIdx);
  }

  protected Position cursorAfter(final String snippet, final String token) {
    final var start = cursor(snippet, token);
    return new Position(start.getLine(), start.getCharacter() + token.length());
  }

  protected CompletionPoint inject(final String replace, final String withIncomplete) {
    final int idx = SOURCE.indexOf(replace);
    if (idx < 0) {
      throw new IllegalArgumentException(
          "expression not found in Workbench.java: [" + replace + "]");
    }
    final String modified =
        SOURCE.substring(0, idx) + withIncomplete + SOURCE.substring(idx + replace.length());
    final String prefix = SOURCE.substring(0, idx) + withIncomplete;
    return new CompletionPoint(modified, toPosition(prefix, prefix.length()));
  }

  protected Optional<String> hoverAt(final Position pos) {
    final var path = pathAt(pos);
    final var element = SourceLocator.elementAt(COMPILED.trees(), path);
    final var type = path != null ? COMPILED.trees().getTypeMirror(path) : null;
    final var javadoc =
        new JavadocLocator(COMPILED.parser())
            .locate(element, COMPILED.trees(), List.of())
            .orElse(null);
    return HoverFormatter.format(element, type, javadoc, null);
  }

  protected Element elementAt(final Position pos) {
    return SourceLocator.elementAt(COMPILED.trees(), pathAt(pos));
  }

  protected VariableElement parameterAt(final Position pos) {
    return SourceLocator.parameterElementAt(COMPILED.trees(), pathAt(pos));
  }

  protected TypeMirror typeAt(final Position pos) {
    final var path = pathAt(pos);
    return path != null ? COMPILED.trees().getTypeMirror(path) : null;
  }

  private TreePath pathAt(final Position pos) {
    final long offset = SourceLocator.toOffset(COMPILED.cu(), pos.getLine(), pos.getCharacter());
    return SourceLocator.pathAt(COMPILED.trees(), COMPILED.cu(), offset);
  }

  private static Position toPosition(final String text, final int offset) {
    int line = 0;
    int lineStart = 0;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    return new Position(line, offset - lineStart);
  }

  protected List<CompletionItem> complete(final CompletionPoint point) {
    return COMPLETION_PIPELINE.complete(
        WORKBENCH_URI, point.source(), point.position(), null, null);
  }

  protected record CompletionPoint(String source, Position position) {}
}
