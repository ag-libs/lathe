package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.JavacTask;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.server.TestJavac;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public final class TempSourceCompiler implements JavaSourceCompiler {

  public static final String TEST_URI = "file:///Test.java";

  private static final List<String> OPTIONS = List.of("-proc:none");

  private final Path td;
  private final List<Path> classpath;
  private final List<String> options;
  private final StandardJavaFileManager fm;

  public TempSourceCompiler() {
    this(List.of());
  }

  public TempSourceCompiler(final List<Path> extraClasspath) {
    this(extraClasspath, List.of());
  }

  // Build options that change how javac recovers from errors, such as Error Prone's
  // -XDcompilePolicy=simple with --should-stop=ifError=FLOW.
  public TempSourceCompiler(final List<Path> extraClasspath, final List<String> extraOptions) {
    this.classpath = List.copyOf(extraClasspath);
    this.options = Stream.concat(OPTIONS.stream(), extraOptions.stream()).toList();
    this.fm = TestJavac.fileManager(classpath, options);
    this.td = IOUtil.unchecked(() -> Files.createTempDirectory("lathe-test-"));
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
              COMPILER.getTask(null, fm, collector, options, null, fm.getJavaFileObjects(tempFile));

      return JavaSourceCompiler.runAnalysis(task, collector);
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

  public static WorkspaceTypeIndex typeIndex(
      final Path shardPath,
      final List<TypeIndexEntry> dependencyEntries,
      final List<TypeIndexEntry> reactorEntries)
      throws IOException {
    Json.write(
        new TypeIndexFile(
            "v1",
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin("test:lib:1.0", "/lib.jar", 0L, 0L)),
            dependencyEntries),
        shardPath);
    return WorkspaceTypeIndex.build(List.of(shardPath), List.of(reactorEntries));
  }

  @Override
  public void close() {
    TestJavac.release(classpath, fm);
    IOUtil.unchecked(() -> FileUtil.deleteDir(td));
  }
}
