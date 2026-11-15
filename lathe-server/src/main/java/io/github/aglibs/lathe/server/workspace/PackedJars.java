package io.github.aglibs.lathe.server.workspace;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.server.files.ServerFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// A Gradle jar can pack another source set's classes beside its own tree's, as Caffeine's packs its
// generated codeGen classes, yet the build lists it under its own tree only: a class path holding
// the jar needs that tree's mirror too. A shaded jar packs relocated dependencies no mirror holds,
// so the jar itself is needed too. The jar's entries tell; Gradle's task wiring cannot be read
// under Isolated Projects.
final class PackedJars {

  private static final Logger LOG = Logger.getLogger(PackedJars.class.getName());
  private static final String CLASS_SUFFIX = ".class";
  private static final String META_INF = "META-INF/";

  private PackedJars() {}

  // The outputs, each path once under the first tree the build lists it under, and a jar once more
  // under each source-set tree of its module that packs a class of it. A jar no build has made yet
  // stands for its own tree only.
  static List<OutputData> withPackedTrees(
      final Path workspaceRoot, final List<OutputData> outputs) {
    final List<OutputData> firstPerPath =
        List.copyOf(
            outputs.stream()
                .collect(
                    Collectors.toMap(
                        OutputData::path,
                        output -> output,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values());
    final Map<String, Set<String>> treesByModule = treesByModule(outputs);
    return Stream.concat(
            firstPerPath.stream(),
            firstPerPath.stream()
                .filter(output -> FileUtil.isJarFile(Path.of(output.path())))
                .flatMap(
                    jar ->
                        packedTrees(workspaceRoot, jar, treesByModule.get(jar.module())).stream()))
        .toList();
  }

  // The jars holding classes in a package no mirror of their module has: a shaded jar's relocated
  // dependencies (ReactorRewrite.keeping says where the jar then goes). A jar no build has made yet
  // is not one.
  static Set<Path> shadedJars(final Path workspaceRoot, final List<OutputData> outputs) {
    final Map<String, Set<String>> treesByModule = treesByModule(outputs);
    return outputs.stream()
        .filter(output -> FileUtil.isJarFile(Path.of(output.path())))
        .filter(jar -> holdsPackageNoMirrorHas(workspaceRoot, jar, treesByModule.get(jar.module())))
        .map(jar -> workspaceRoot.resolve(jar.path()).normalize())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Map<String, Set<String>> treesByModule(final List<OutputData> outputs) {
    return outputs.stream()
        .collect(
            Collectors.groupingBy(
                OutputData::module,
                Collectors.mapping(OutputData::tree, Collectors.toCollection(LinkedHashSet::new))));
  }

  private static boolean holdsPackageNoMirrorHas(
      final Path workspaceRoot, final OutputData jar, final Set<String> moduleTrees) {
    final Path jarFile = workspaceRoot.resolve(jar.path());
    if (!Files.isRegularFile(jarFile)) {
      return false;
    }

    final Path moduleMirror = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(jar.module());
    try {
      final boolean shaded =
          ServerFiles.listEntries(jarFile, PackedJars::isClassEntry).stream()
              .map(entry -> Path.of(entry).getParent())
              .filter(Objects::nonNull)
              .distinct()
              .anyMatch(
                  pkg ->
                      moduleTrees.stream()
                          .noneMatch(
                              tree -> Files.isDirectory(moduleMirror.resolve(tree).resolve(pkg))));
      if (shaded) {
        LOG.fine(
            () ->
                "[manifest] %s holds packages no mirror has, kept as a shaded jar"
                    .formatted(jarFile));
      }

      return shaded;
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[manifest] %s not read, not kept as a shaded jar".formatted(jarFile));
      return false;
    }
  }

  // The source-set trees whose classes the jar packs and its own tree's mirror lacks. Test code
  // never joins: a class moved to a test source set since the jar was built must not put test code
  // on the class paths of the projects using the jar.
  private static List<OutputData> packedTrees(
      final Path workspaceRoot, final OutputData jar, final Set<String> moduleTrees) {
    final Path jarFile = workspaceRoot.resolve(jar.path());
    final List<String> sourceSetTrees =
        moduleTrees.stream()
            .filter(tree -> tree.startsWith(LatheLayout.SOURCE_SET_TREE_PREFIX))
            .filter(tree -> !tree.equals(jar.tree()))
            .toList();
    if (sourceSetTrees.isEmpty() || !Files.isRegularFile(jarFile)) {
      return List.of();
    }

    final Path moduleMirror = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(jar.module());
    try {
      final Set<String> packed =
          Set.copyOf(ServerFiles.listEntries(jarFile, PackedJars::isClassEntry));
      final var found = new ArrayList<OutputData>();
      for (final String tree : sourceSetTrees) {
        if (isMainCode(moduleMirror, tree)
            && packsClassOf(packed, moduleMirror.resolve(jar.tree()), moduleMirror.resolve(tree))) {
          found.add(new OutputData(jar.path(), jar.module(), tree));
        }
      }

      return List.copyOf(found);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[manifest] %s not read, stands for its own tree only".formatted(jarFile));
      return List.of();
    }
  }

  private static boolean isClassEntry(final String name) {
    return name.endsWith(CLASS_SUFFIX) && !name.startsWith(META_INF);
  }

  private static boolean isMainCode(final Path moduleMirror, final String tree) throws IOException {
    final Path params = moduleMirror.resolve(LatheLayout.paramsFileName(tree));
    return Files.isRegularFile(params)
        && !Boolean.TRUE.equals(Json.read(params, ModuleConfigData.class).testCode());
  }

  // The tree's mirror is walked, usually small (generated code), not the jar, which can hold
  // thousands of classes.
  private static boolean packsClassOf(final Set<String> packed, final Path ownTree, final Path tree)
      throws IOException {
    return FileUtil.walkFiles(tree, FileUtil::isClassFile).stream()
        .map(file -> FileUtil.slashed(tree.relativize(file)))
        .anyMatch(entry -> packed.contains(entry) && !Files.exists(ownTree.resolve(entry)));
  }
}
