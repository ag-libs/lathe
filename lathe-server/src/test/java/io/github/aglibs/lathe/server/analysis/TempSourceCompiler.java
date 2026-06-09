package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class TempSourceCompiler implements JavaSourceCompiler {

  private final Path td;
  private final JavaCompiler compiler;
  private final StandardJavaFileManager fm;

  public TempSourceCompiler() {
    try {
      this.compiler = ToolProvider.getSystemJavaCompiler();
      this.fm = compiler.getStandardFileManager(null, null, null);
      this.td = Files.createTempDirectory("lathe-test-");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public StandardJavaFileManager fileManager() {
    return fm;
  }

  @Override
  public CompilerResult compile(final String uri, final String content, final CompileMode mode) {
    try {
      final var filename = Path.of(URI.create(uri)).getFileName();
      final var tempFile = td.resolve(filename);
      Files.writeString(tempFile, content);

      final var collector = new DiagnosticCollector<JavaFileObject>();
      final var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  fm,
                  collector,
                  List.of("-proc:none"),
                  null,
                  fm.getJavaFileObjects(tempFile));

      final CompilationUnitTree cu = JavaSourceCompiler.safeCompile(task);

      final Trees trees = Trees.instance(task);
      final AttributedFileAnalysis fileAnalysis;
      if (cu != null) {
        final List<SemanticToken> tokens = TokenScanner.scan(trees, cu);
        fileAnalysis =
            new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), cu, tokens);
      } else {
        fileAnalysis =
            new AttributedFileAnalysis(trees, task.getElements(), task.getTypes(), null, null);
      }

      return new CompilerResult(collector.getDiagnostics(), fileAnalysis);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static WorkspaceTypeIndex typeIndex(final Path shardPath, final TypeIndexEntry... entries)
      throws IOException {
    Json.write(
        new TypeIndexFile(
            "v1",
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin("test:lib:1.0", "/lib.jar", 0L, 0L)),
            List.of(entries)),
        shardPath);
    return WorkspaceTypeIndex.build(List.of(shardPath));
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      FileUtil.deleteDir(td);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
