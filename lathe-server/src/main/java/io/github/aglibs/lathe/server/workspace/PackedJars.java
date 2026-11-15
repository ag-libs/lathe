package io.github.aglibs.lathe.server.workspace;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.OutputData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// A Gradle jar can pack another source set's classes beside its own tree's, as Caffeine's packs its
// generated codeGen classes, yet the build lists it under its own tree only: a classpath holding
// the jar needs that tree's mirror too. The jar's entries tell; Gradle's task wiring cannot be read
// under Isolated Projects.
final class PackedJars {

  private static final Logger LOG = Logger.getLogger(PackedJars.class.getName());
  private static final String CLASS_SUFFIX = ".class";
  private static final String META_INF = "META-INF/";

  private PackedJars() {}

  // The outputs, with a jar listed once more under each other tree of its module whose mirror holds
  // a class the jar packs and its own tree's mirror lacks. A jar no build has made yet adds none.
  static List<OutputData> withPackedTrees(
      final Path workspaceRoot, final List<OutputData> outputs) {
    final Map<String, Set<String>> treesByModule =
        outputs.stream()
            .collect(
                Collectors.groupingBy(
                    OutputData::module,
                    Collectors.mapping(
                        OutputData::tree, Collectors.toCollection(LinkedHashSet::new))));
    return Stream.concat(
            outputs.stream(),
            outputs.stream()
                .filter(output -> FileUtil.isJarFile(Path.of(output.path())))
                .flatMap(jar -> packedTrees(workspaceRoot, jar, treesByModule.get(jar.module()))))
        .toList();
  }

  private static Stream<OutputData> packedTrees(
      final Path workspaceRoot, final OutputData jar, final Set<String> moduleTrees) {
    final Path jarFile = workspaceRoot.resolve(jar.path());
    final List<String> otherTrees =
        moduleTrees.stream().filter(tree -> !tree.equals(jar.tree())).toList();
    if (otherTrees.isEmpty() || !Files.isRegularFile(jarFile)) {
      return Stream.empty();
    }

    final Path moduleMirror = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(jar.module());
    final List<String> notInOwnTree =
        classEntries(jarFile).stream()
            .filter(entry -> !Files.exists(moduleMirror.resolve(jar.tree()).resolve(entry)))
            .toList();
    return otherTrees.stream()
        .filter(
            tree ->
                notInOwnTree.stream()
                    .anyMatch(entry -> Files.exists(moduleMirror.resolve(tree).resolve(entry))))
        .map(tree -> new OutputData(jar.path(), jar.module(), tree));
  }

  // A jar being rewritten by a build may not read: it then stands for its own tree, as before.
  private static List<String> classEntries(final Path jarFile) {
    try (final var zip = new ZipFile(jarFile.toFile())) {
      return zip.stream()
          .map(ZipEntry::getName)
          .filter(name -> name.endsWith(CLASS_SUFFIX) && !name.startsWith(META_INF))
          .toList();
    } catch (final IOException e) {
      LOG.log(
          Level.FINE, e, () -> "[manifest] %s unreadable, its own tree only".formatted(jarFile));
      return List.of();
    }
  }
}
