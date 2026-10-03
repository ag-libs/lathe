package io.github.aglibs.lathe.openjdk;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Emits the OpenJDK-native {@code .lathe/}: per-module compiled-deps params + stamps
 * (MODULE_SYSTEM), the non-module build tools (CLASSPATH, reading the build's own output dir),
 * {@code workspace.json}, and {@code java-home} (the exploded JDK = the read-only base). No
 * bytecode mirror — dependencies are referenced from the build's exploded {@code modules/} / tool
 * output dirs, never copied.
 */
public final class OpenJdkSync {

  private static final String SOURCE_TREE = "classes";
  private static final String BUILDTOOLS_DIR = "buildtools";
  private static final String BATCH_SUFFIX = "_batch.cmdline";

  private OpenJdkSync() {}

  /**
   * Discovers every built module under {@code buildDir/jdk/modules} plus the build tools, and
   * syncs.
   */
  public static void sync(
      final Path buildDir,
      final Path workspaceRoot,
      final String serverVersion,
      final Consumer<String> log)
      throws IOException {
    final Path explodedJdk = buildDir.resolve("jdk");
    final Path modulesDir = explodedJdk.resolve("modules");
    if (!Files.isDirectory(modulesDir)) {
      throw new IOException("no built modules at %s — build the JDK first".formatted(modulesDir));
    }

    int modules = 0;
    final var moduleRoots = new ArrayList<Path>();
    try (Stream<Path> dirs = Files.list(modulesDir)) {
      for (final Path moduleOut : dirs.filter(Files::isDirectory).sorted().toList()) {
        final var module = moduleOut.getFileName().toString();
        final Path cmdline = moduleOut.resolve("_the.%s%s".formatted(module, BATCH_SUFFIX));
        if (!Files.isRegularFile(cmdline)) {
          log.accept("[sync] %s skipped — not compiled (no .cmdline)".formatted(module));
          continue;
        }
        moduleRoots.addAll(syncModule(module, moduleOut, cmdline, workspaceRoot));
        modules++;
      }
    }

    final int tools = syncBuildTools(buildDir, workspaceRoot, List.copyOf(moduleRoots), log);

    writeWorkspace(workspaceRoot, serverVersion);
    writeJavaHome(workspaceRoot, explodedJdk);
    final int moduleCount = modules;
    log.accept(
        "[sync] %d module(s) + %d tool(s) -> %s"
            .formatted(moduleCount, tools, latheDir(workspaceRoot)));
  }

  // Returns the module's source roots, so the build-tool pass can skip tools that overlap a module.
  private static List<Path> syncModule(
      final String module, final Path moduleOut, final Path cmdlineFile, final Path workspaceRoot)
      throws IOException {
    final CmdlineReader.Parsed parsed = CmdlineReader.read(Files.readString(cmdlineFile), module);
    final List<Path> sourceRoots = existingRoots(parsed.sourceRootPatterns());
    final var config =
        new ModuleConfigData(
            SOURCE_TREE,
            moduleOut.toString(),
            null,
            sourceRoots.stream().map(Path::toString).toList(),
            List.of(),
            List.of(),
            List.of(),
            "",
            parsed.encoding(),
            false,
            false,
            null,
            parsed.compilerArgs(),
            AnalysisMode.MODULE_SYSTEM,
            true);
    final List<Path> sources = readFilelist(siblingFilelist(cmdlineFile));
    writeConfig(latheDir(workspaceRoot).resolve(module), config, sources, sourceRoots);
    return sourceRoots;
  }

  // Non-module build tools: plain classpath javac, captured as CLASSPATH configs that read the
  // build's output dir directly. Tools whose roots overlap a real module are skipped (interim
  // modules; data-gen steps that compile a few module sources, e.g. break-iterator for java.base),
  // since the module already covers those files and a second config would mislabel its packages.
  private static int syncBuildTools(
      final Path buildDir,
      final Path workspaceRoot,
      final List<Path> moduleRoots,
      final Consumer<String> log)
      throws IOException {
    final Path toolsRoot = buildDir.resolve(BUILDTOOLS_DIR);
    int synced = 0;
    for (final Path cmdline : toolCmdlines(toolsRoot)) {
      final String content = Files.readString(cmdline);
      if (content.contains("--module-source-path")) {
        continue;
      }

      final Path outputDir = cmdline.getParent();
      final List<Path> sources = readFilelist(siblingFilelist(cmdline));
      final List<Path> sourceRoots = inferRoots(outputDir, sources);
      if (sources.isEmpty() || sourceRoots.isEmpty() || overlapsModule(sourceRoots, moduleRoots)) {
        continue;
      }

      final CmdlineReader.ToolParsed parsed = CmdlineReader.readTool(content);
      final var config =
          new ModuleConfigData(
              SOURCE_TREE,
              outputDir.toString(),
              null,
              sourceRoots.stream().map(Path::toString).toList(),
              parsed.classpath(),
              List.of(),
              List.of(),
              "",
              parsed.encoding(),
              false,
              false,
              null,
              parsed.compilerArgs(),
              AnalysisMode.CLASSPATH,
              true);
      writeConfig(
          latheDir(workspaceRoot).resolve(batchName(cmdline)), config, sources, sourceRoots);
      synced++;
    }
    return synced;
  }

  private static List<Path> toolCmdlines(final Path toolsRoot) throws IOException {
    return FileUtil.walkFiles(
        toolsRoot, path -> path.getFileName().toString().endsWith(BATCH_SUFFIX));
  }

  // "_the.<BATCH>_batch.cmdline" -> "<BATCH>"; the batch name is unique, unlike the leaf output dir
  // which can collide with a module (e.g. break_iterator_classes/java.base).
  private static String batchName(final Path cmdline) {
    final String name = cmdline.getFileName().toString();
    return name.substring("_the.".length(), name.length() - BATCH_SUFFIX.length());
  }

  private static Path siblingFilelist(final Path cmdline) {
    final String name = cmdline.getFileName().toString();
    return cmdline.resolveSibling(
        name.substring(0, name.length() - ".cmdline".length()) + ".filelist");
  }

  // Source roots inferred from the compiled output layout: the -d dir mirrors the package structure
  // (via FileUtil.packagesWithClasses), so a file's root is its parent minus the package path.
  private static List<Path> inferRoots(final Path outputDir, final List<Path> sources)
      throws IOException {
    final List<String> packages = FileUtil.packagesWithClasses(outputDir);
    return sources.stream().map(source -> rootOfSource(source, packages)).distinct().toList();
  }

  private static Path rootOfSource(final Path source, final List<String> packages) {
    final Path parent = source.getParent();
    return packages.stream()
        .map(pkg -> Path.of(pkg.replace('.', '/')))
        .filter(parent::endsWith)
        .max(Comparator.comparingInt(Path::getNameCount))
        .map(pkg -> stripSuffix(parent, pkg.getNameCount()))
        .orElse(parent);
  }

  private static Path stripSuffix(final Path path, final int segments) {
    final Path rel = path.subpath(0, path.getNameCount() - segments);
    return path.getRoot() != null ? path.getRoot().resolve(rel) : rel;
  }

  private static List<Path> existingRoots(final List<String> patterns) {
    return patterns.stream().map(Path::of).filter(Files::isDirectory).toList();
  }

  // A tool root overlaps a module when one contains the other (equal or nested either way).
  private static boolean overlapsModule(final List<Path> toolRoots, final List<Path> moduleRoots) {
    return toolRoots.stream()
        .anyMatch(
            tool -> moduleRoots.stream().anyMatch(m -> tool.startsWith(m) || m.startsWith(tool)));
  }

  private static void writeConfig(
      final Path latheModuleDir,
      final ModuleConfigData config,
      final List<Path> sources,
      final List<Path> roots)
      throws IOException {
    Files.createDirectories(latheModuleDir);
    Json.write(config, latheModuleDir.resolve(LatheLayout.paramsFileName(SOURCE_TREE)));
    CompiledStamps.writeAll(latheModuleDir, SOURCE_TREE, stampsFor(sources, roots));
  }

  // filelist -> {sourceRoot-relative path -> mtime}, keyed as LatheCompiler.writeCompiledStamps
  // does.
  private static Map<String, Long> stampsFor(final List<Path> sources, final List<Path> roots)
      throws IOException {
    final var stamps = new HashMap<String, Long>();
    for (final Path source : sources) {
      final Optional<Path> root = rootOf(source, roots);
      if (root.isPresent()) {
        stamps.put(
            root.get().relativize(source).toString(), Files.getLastModifiedTime(source).toMillis());
      }
    }
    return stamps;
  }

  private static List<Path> readFilelist(final Path filelist) throws IOException {
    if (!Files.isRegularFile(filelist)) {
      return List.of();
    }

    return Files.readAllLines(filelist).stream()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(Path::of)
        .toList();
  }

  private static Path latheDir(final Path workspaceRoot) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR);
  }

  private static Optional<Path> rootOf(final Path source, final List<Path> roots) {
    return roots.stream()
        .filter(source::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount));
  }

  private static void writeWorkspace(final Path workspaceRoot, final String serverVersion)
      throws IOException {
    final var manifest =
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            workspaceRoot.toString(),
            serverVersion,
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of());
    Json.write(manifest, latheDir(workspaceRoot).resolve(LatheLayout.WORKSPACE_JSON));
  }

  private static void writeJavaHome(final Path workspaceRoot, final Path explodedJdk)
      throws IOException {
    Files.writeString(
        latheDir(workspaceRoot).resolve(LatheLayout.JAVA_HOME_FILE),
        explodedJdk + System.lineSeparator());
  }
}
