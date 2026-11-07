package io.github.aglibs.lathe.analysis.testing;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.analysis.CursorElements;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.core.IOUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base class giving each test {@link #compiled}, the attributed {@code Sample.java}. Tests only
 * read it, so one compile is shared by every test on a thread; per thread because javac fills in
 * symbols lazily and is not thread-safe.
 */
public abstract class SampleFixture {

  private static final ThreadLocal<TestJavac.ParsedSource> SAMPLE =
      ThreadLocal.withInitial(() -> IOUtil.unchecked(SampleFixture::parse));

  @TempDir public Path tmp;

  public final TestJavac.ParsedSource compiled = SAMPLE.get();

  // The file outlives the parse: tests read the source text back through the compilation unit.
  private static TestJavac.ParsedSource parse() throws IOException {
    final var source =
        new String(
            Objects.requireNonNull(SampleFixture.class.getResourceAsStream("/Sample.java"))
                .readAllBytes());
    final Path dir = Files.createTempDirectory("lathe-sample-");
    final Path sourceFile = Files.writeString(dir.resolve("Sample.java"), source);
    dir.toFile().deleteOnExit();
    sourceFile.toFile().deleteOnExit();
    return TestJavac.parse(sourceFile);
  }

  public Element elementAt(final int line, final int character) {
    return CursorElements.elementAt(compiled.trees(), pathAt(line, character));
  }

  public VariableElement parameterAt(final int line, final int character) {
    return CursorElements.parameterElementAt(compiled.trees(), pathAt(line, character));
  }

  public TreePath pathAt(final int line, final int character) {
    final var offset = Positions.toOffset(compiled.cu(), line, character);
    return CursorElements.pathAt(compiled.trees(), compiled.cu(), offset);
  }

  public TreePath pathAt(final String expression, final String token) {
    return pathAt(compiled.trees(), compiled.cu(), expression, token);
  }

  public static TreePath pathAt(
      final Trees trees,
      final CompilationUnitTree cu,
      final String expression,
      final String token) {
    final String source = sourceContent(cu);
    final int expressionOffset = source.indexOf(expression);
    final int offset = source.indexOf(token, expressionOffset);
    return CursorElements.pathAt(trees, cu, offset);
  }

  public static String sourceContent(final CompilationUnitTree cu) {
    try {
      return cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  // The position of {@code token} at or after {@code context} in the raw source.
  public static Position posOf(final String source, final String context, final String token) {
    final int from = source.indexOf(context);
    final int offset = source.indexOf(token, from);
    return Positions.offsetToPosition(source, offset);
  }
}
