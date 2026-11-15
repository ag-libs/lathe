package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// What one compile leaves in .lathe/<module>: its lsp-params, and, after a successful compile, the
// bytecode and generated-sources mirrors and the compile stamps. The build tool decides when each
// runs: a failed compile keeps the last good mirror, so its sources stay stale for the server.
public final class CompileCapture {

  private static final Set<String> VERSION_AND_PREVIEW_ARGS =
      Set.of("--release", "-source", "--source", "-target", "--target", "--enable-preview");

  private CompileCapture() {}

  public static void writeParams(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    Json.write(config, moduleDir.resolve(LatheLayout.paramsFileName(config.sourceTree())));
  }

  // A compile with no --release passes javac the -source and -target the build tool keeps apart
  // from the args it lists: added to them, so the server's javac, maybe a newer JDK's, writes the
  // same class file version and checks the same language level. Not where the args name a version
  // themselves (javac refuses --release beside -source), nor with preview, which javac allows only
  // at its own version.
  public static List<String> withSourceAndTarget(
      final List<String> args,
      final String source,
      final String target,
      final String release,
      final boolean enablePreview) {
    if (isBlank(source)
        || isBlank(target)
        || !isBlank(release)
        || enablePreview
        || args.stream().anyMatch(CompileCapture::isVersionOrPreviewArg)) {
      return List.copyOf(args);
    }

    return Stream.concat(Stream.of("-source", source, "-target", target), args.stream()).toList();
  }

  private static boolean isVersionOrPreviewArg(final String arg) {
    return VERSION_AND_PREVIEW_ARGS.stream()
        .anyMatch(flag -> arg.equals(flag) || arg.startsWith("%s=".formatted(flag)));
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  // Each source's mtime, taken just before javac runs: compared after it, they tell which sources
  // were written while it compiled.
  public static Map<Path, Long> sourceMtimes(final Collection<Path> sources) {
    return sources.stream()
        .collect(
            Collectors.toUnmodifiableMap(
                CompileCapture::normalized, CompileCapture::mtime, (first, ignored) -> first));
  }

  // resourceDirs join the classes in the bytecode mirror: Gradle processes resources into their
  // own directory, where Maven puts them beside the classes, and a replay that stands the mirror
  // in for a project's jar needs both. mtimesBeforeCompile: from sourceMtimes, just before javac.
  public static void mirror(
      final Path moduleDir,
      final ModuleConfigData config,
      final List<Path> resourceDirs,
      final Map<Path, Long> mtimesBeforeCompile)
      throws IOException {
    final Path classesMirror = moduleDir.resolve(config.sourceTree());
    FileUtil.replaceDir(Path.of(config.outputDir()), classesMirror);
    for (final Path resources : resourceDirs) {
      if (Files.isDirectory(resources)) {
        FileUtil.copyDir(resources, classesMirror);
      }
    }

    mirrorGeneratedSources(moduleDir, config);
    writeStamps(moduleDir, config, mtimesBeforeCompile);
  }

  // To a scope-specific target, so the test compile does not overwrite the main compile's sources.
  // A compile without processor output removes the mirror, so an earlier processor's types do not
  // linger in the editor.
  private static void mirrorGeneratedSources(final Path moduleDir, final ModuleConfigData config)
      throws IOException {
    final Path mirror = moduleDir.resolve(LatheLayout.generatedSourcesDir(config.sourceTree()));
    if (config.generatedSourcesDir() == null
        || !Files.isDirectory(Path.of(config.generatedSourcesDir()))) {
      FileUtil.deleteDir(mirror);
      return;
    }

    FileUtil.replaceDir(Path.of(config.generatedSourcesDir()), mirror);
  }

  // Each source's stamp (its mtime and the content compiled) and, from the same hashes, what the
  // build compiled. A source written while javac compiled, or new since it started, may hold other
  // content than javac read: it gets neither, so the server compiles it as never built.
  private static void writeStamps(
      final Path moduleDir,
      final ModuleConfigData config,
      final Map<Path, Long> mtimesBeforeCompile)
      throws IOException {
    final Map<Path, Path> sources = CompiledStamps.sourcesByKey(config);
    // Hashed first, then kept only if the mtime is still the one before javac ran: a write while
    // hashing moves it too, so the content hashed is what javac read.
    final Map<Path, ContentFingerprint> hashed = CompiledStamps.builtOf(sources);
    final Map<Path, Long> unchanged =
        sources.entrySet().stream()
            .filter(source -> unchangedSince(mtimesBeforeCompile, source.getValue()))
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    source -> mtimesBeforeCompile.get(normalized(source.getValue()))));
    final Map<Path, ContentFingerprint> built =
        unchanged.keySet().stream().collect(Collectors.toUnmodifiableMap(key -> key, hashed::get));
    final Map<Path, SourceStamp> stamps =
        unchanged.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry -> new SourceStamp(entry.getValue(), built.get(entry.getKey()))));
    CompiledStamps.writeAll(moduleDir, config.sourceTree(), stamps, built);
  }

  private static boolean unchangedSince(final Map<Path, Long> mtimesBefore, final Path source) {
    return Long.valueOf(mtime(source)).equals(mtimesBefore.get(normalized(source)));
  }

  private static Path normalized(final Path source) {
    return source.toAbsolutePath().normalize();
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0L;
    }
  }
}
