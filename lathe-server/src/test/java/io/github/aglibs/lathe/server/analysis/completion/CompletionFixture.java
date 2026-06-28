package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CachedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

final class CompletionFixture implements AutoCloseable {

  private final SourceParser sourceParser;
  private final TempSourceCompiler compiler;
  private final CompletionEngine engine;
  private final WorkspaceTypeIndex typeIndex;
  private final Path tmpDir;

  CompletionFixture() {
    this(WorkspaceTypeIndex.empty(), null);
  }

  CompletionFixture(final WorkspaceTypeIndex typeIndex) {
    this(typeIndex, null);
  }

  CompletionFixture(final WorkspaceTypeIndex typeIndex, final Path tmpDir) {
    this.sourceParser = new SourceParser();
    this.compiler = new TempSourceCompiler();
    this.engine = new CompletionEngine(sourceParser, compiler);
    this.typeIndex = typeIndex;
    this.tmpDir = tmpDir;
  }

  List<CompletionItem> complete(final String markedSource) {
    return outcome(markedSource).items();
  }

  CompletionOutcome outcome(final String markedSource) {
    final var cursor = CursorFixture.cursor(markedSource);
    final var cached = new CachedFileAnalysis(cursor.content(), 0, compile(cursor.content()));
    return engine.complete(request(cursor, cached));
  }

  List<CompletionItem> completeWithCache(final String cachedSource, final String markedSource) {
    final var cursor = CursorFixture.cursor(markedSource);
    final var cached = new CachedFileAnalysis(cachedSource, 0, compile(cachedSource));
    return engine.complete(request(cursor, cached)).items();
  }

  List<CompletionItem> completeModuleInfo(final String markedContent) {
    final var cursor = CursorFixture.cursor(markedContent);
    return engine
        .complete(
            new CompletionRequest(
                "file:///module-info.java",
                cursor.content(),
                new Position(cursor.lspLine(), cursor.lspChar()),
                null,
                null,
                typeIndex))
        .items();
  }

  List<CompletionItem> completeWithJpms(final String markedSource, final String moduleInfo)
      throws IOException {
    return completeWithJpms(markedSource, moduleInfo, List.of());
  }

  List<CompletionItem> completeWithJpms(
      final String markedSource, final String moduleInfo, final List<String> extraOptions)
      throws IOException {
    final var cursor = CursorFixture.cursor(markedSource);
    final var cached =
        new CachedFileAnalysis(
            cursor.content(), 0, jpmsAnalysis(cursor.content(), moduleInfo, extraOptions));
    return engine.complete(request(cursor, cached)).items();
  }

  static WorkspaceTypeIndex typeIndex(final Path shardPath, final TypeIndexEntry... entries)
      throws IOException {
    return TempSourceCompiler.typeIndex(shardPath, entries);
  }

  static TypeIndexEntry typeEntry(
      final String simpleName, final String qualifiedName, final TypeKind kind) {
    final int packageEnd = qualifiedName.lastIndexOf('.');
    final String packageName = packageEnd >= 0 ? qualifiedName.substring(0, packageEnd) : "";
    return new TypeIndexEntry(simpleName, qualifiedName, packageName, kind, true, List.of());
  }

  private AttributedFileAnalysis compile(final String source) {
    return compiler.compile(TempSourceCompiler.TEST_URI, source, CompileMode.FULL).fileAnalysis();
  }

  private AttributedFileAnalysis jpmsAnalysis(
      final String source, final String moduleInfo, final List<String> extraOptions)
      throws IOException {
    final Path moduleDir = tmpDir.resolve("jpms");
    final Path moduleInfoFile = moduleDir.resolve("module-info.java");
    final Path sourceFile = moduleDir.resolve("com/example/app/Test.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(moduleInfoFile, moduleInfo);
    Files.writeString(sourceFile, source);
    final List<String> options =
        Stream.concat(Stream.of("-proc:none"), extraOptions.stream()).toList();
    try (final var parsed = TestCompiler.parse(sourceFile, options, moduleInfoFile)) {
      return new AttributedFileAnalysis(
          parsed.trees(),
          parsed.task().getElements(),
          parsed.task().getTypes(),
          parsed.cu(),
          List.of());
    }
  }

  private CompletionRequest request(
      final CursorFixture.Cursor cursor, final CachedFileAnalysis cached) {
    return new CompletionRequest(
        TempSourceCompiler.TEST_URI,
        cursor.content(),
        new Position(cursor.lspLine(), cursor.lspChar()),
        null,
        cached,
        typeIndex);
  }

  @Override
  public void close() {
    compiler.close();
    sourceParser.close();
  }
}
