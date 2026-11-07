package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.run.TestSource;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

// Where the workspace's modules keep their sources, packages and tests, read from the module
// configs and the disk alone: no javac and no session state, so it is covered without a loaded
// workspace. Before the workspace loads there are no configs and no root, and every query answers
// empty.
record SourceLayout(List<ModuleSourceConfig> configs, Path workspaceRoot) {

  private static final Logger LOG = Logger.getLogger(SourceLayout.class.getName());

  SourceLayout {
    ValidCheck.check().notNull(configs, "configs").validate();
    configs = List.copyOf(configs);
  }

  // A module's .lathe directory as the reactor-relative module path the tools and the sync use.
  static String moduleRel(final Path workspaceRoot, final Path moduleDir) {
    return FileUtil.slashed(workspaceRoot.resolve(LatheLayout.LATHE_DIR).relativize(moduleDir));
  }

  // The module's reactor-relative path, as the tools and the sync name it.
  String moduleRel(final ModuleSourceConfig config) {
    return moduleRel(workspaceRoot, config.moduleDir());
  }

  // A module path as a person reads it: "." for the module at the reactor root.
  static String displayed(final String moduleRel) {
    return moduleRel.isEmpty() ? "." : moduleRel;
  }

  // The module's directory under .lathe/, the inverse of moduleRel.
  static Path moduleLatheDir(final Path workspaceRoot, final String moduleRel) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
  }

  List<String> modules() {
    return configs.stream().map(config -> moduleRel(config)).distinct().sorted().toList();
  }

  List<String> testModules() {
    return testConfigs().stream().map(config -> moduleRel(config)).distinct().sorted().toList();
  }

  List<PackageEntry> packages(final String moduleRel) {
    return configs.stream()
        .filter(config -> moduleRel(config).equals(moduleRel))
        .flatMap(SourceLayout::packageEntries)
        .distinct()
        .sorted(Comparator.comparing(PackageEntry::pkg).thenComparing(PackageEntry::scope))
        .toList();
  }

  ContextInfo resolveContext(final Path path) {
    return configs.stream()
        .flatMap(config -> contextIn(config, path).stream())
        .findFirst()
        .orElse(null);
  }

  // The real (non-generated) source directory a new file of the module and scope lands in.
  Path sourceRootFor(final String moduleRel, final SourceScope scope) {
    return configsFor(moduleRel).stream()
        .filter(config -> scope.sourceTree.equals(config.sourceTree()))
        .flatMap(config -> primarySourceRoot(config).stream())
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no %s source root for module '%s'".formatted(scope, moduleRel)));
  }

  // Test selectors to run for a directory, from the real reactor layout, not the path string.
  // A dir inside a module's test tree runs just that package (selectPackage recurses); the
  // source root, or anything above it (module root, src/), runs every test package in one
  // launch. A dir enclosing more than one module (the reactor root) resolves to nothing.
  DirRun dirRun(final Path dirPath) {
    final List<ModuleSourceConfig> testConfigs = testConfigs();

    for (final ModuleSourceConfig config : testConfigs) {
      final Optional<Path> root = primarySourceRoot(config);
      if (root.isEmpty() || !dirPath.startsWith(root.get())) {
        continue;
      }

      final String pkg = packageOf(root.get(), dirPath);
      final String moduleRel = moduleRel(config);
      if (pkg.isEmpty()) {
        return moduleRun(moduleRel);
      }

      return new DirRun(
          moduleRel, List.of(new DirRun.Selector(TestSelectionKind.PACKAGE.name(), pkg)));
    }

    // At or above a module's test tree (module root, src/): its real test root is a descendant
    // of the dir -- deterministic, no path parsing. Only when exactly one module lives under it;
    // several is a multi-module directory, which is not run.
    final List<ModuleSourceConfig> enclosed =
        testConfigs.stream().filter(config -> testRootUnder(config, dirPath)).toList();
    if (enclosed.size() == 1) {
      return moduleRun(moduleRel(enclosed.get(0)));
    }

    return new DirRun("", List.of());
  }

  // Source file for each test class, resolved from the module's real test source roots by path math
  // (top-level class -> <root>/<pkg>/<Simple>.java, existence-checked). No attribution, so it works
  // for classes whose files were never opened. Unresolved classes get an empty path.
  List<TestSource> testSources(final String moduleRel, final List<String> classNames) {
    final List<Path> roots =
        testConfigs().stream()
            .filter(config -> moduleRel(config).equals(moduleRel))
            .flatMap(config -> config.sourceRoots().stream())
            .toList();
    return classNames.stream()
        .distinct()
        .map(name -> new TestSource(name, sourcePath(roots, name)))
        .toList();
  }

  private List<ModuleSourceConfig> configsFor(final String moduleRel) {
    final List<ModuleSourceConfig> matching =
        configs.stream().filter(config -> moduleRel(config).equals(moduleRel)).toList();
    if (matching.isEmpty()) {
      throw new IllegalStateException("no module for " + moduleRel);
    }

    return matching;
  }

  private List<ModuleSourceConfig> testConfigs() {
    return configs.stream()
        .filter(config -> SourceScope.ofSourceTree(config.sourceTree()) == SourceScope.TEST)
        .toList();
  }

  // Every test package of the module, in one launch.
  DirRun moduleRun(final String moduleRel) {
    final List<DirRun.Selector> selectors =
        packages(moduleRel).stream()
            .filter(entry -> entry.scope().equals(SourceScope.TEST.wire))
            .map(PackageEntry::pkg)
            .filter(pkg -> !pkg.isEmpty())
            .distinct()
            .map(pkg -> new DirRun.Selector(TestSelectionKind.PACKAGE.name(), pkg))
            .toList();
    return new DirRun(moduleRel, selectors);
  }

  private Optional<ContextInfo> contextIn(final ModuleSourceConfig config, final Path path) {
    return primarySourceRoot(config)
        .filter(path::startsWith)
        .map(
            root ->
                new ContextInfo(
                    moduleRel(config), scopeWire(config), packageOf(root, path.getParent())));
  }

  // The real (non-generated) source directory a new file lands in.
  private static Optional<Path> primarySourceRoot(final ModuleSourceConfig config) {
    return config.sourceRoots().stream().findFirst();
  }

  private static boolean testRootUnder(final ModuleSourceConfig config, final Path dirPath) {
    return primarySourceRoot(config).filter(root -> root.startsWith(dirPath)).isPresent();
  }

  private static String sourcePath(final List<Path> roots, final String className) {
    final int inner = className.indexOf('$');
    final String topLevel = inner < 0 ? className : className.substring(0, inner);
    final String rel = topLevel.replace('.', '/') + ".java";
    for (final Path root : roots) {
      final Path candidate = root.resolve(rel);
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    return "";
  }

  private static Stream<PackageEntry> packageEntries(final ModuleSourceConfig config) {
    final String scope = scopeWire(config);
    return primarySourceRoot(config).stream()
        .flatMap(root -> packagesUnder(root).stream().map(pkg -> new PackageEntry(pkg, scope)));
  }

  private static String scopeWire(final ModuleSourceConfig config) {
    return SourceScope.ofSourceTree(config.sourceTree()).wire;
  }

  // A package is a directory that holds Java sources (the parent of each `.java`).
  // Intermediate dirs like `com` are not packages and must not be offered.
  private static List<String> packagesUnder(final Path root) {
    try {
      return FileUtil.walkFiles(root, FileUtil::isJavaFile).stream()
          .map(source -> packageOf(root, source.getParent()))
          .distinct()
          .sorted()
          .toList();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[packages] scan failed under %s".formatted(root));
      return List.of();
    }
  }

  private static String packageOf(final Path root, final Path dir) {
    return root.relativize(dir).toString().replace(root.getFileSystem().getSeparator(), ".");
  }
}
