package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

// A class compiled into a tree of its own (classes-<name>: a Gradle source set, or a Maven compile
// writing to such a directory), and not into the module's main or test tree, has no captured launch
// yet: running it is refused, with the reason, rather than sent to the module's test or main
// launch, where the class is missing or the classpath is another tree's.
public final class SourceSetScope {

  private SourceSetScope() {}

  public static Optional<String> uncapturedReason(
      final Path workspaceRoot, final String moduleRel, final String className) {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final String classFile = "%s.class".formatted(className.replace('.', '/'));
    if (Stream.of(LatheLayout.CLASSES_DIR, LatheLayout.TEST_CLASSES_DIR)
        .anyMatch(tree -> Files.exists(moduleDir.resolve(tree).resolve(classFile)))) {
      return Optional.empty();
    }

    return IOUtil.unchecked(() -> FileUtil.filesIn(moduleDir, LatheLayout::isParamsFile)).stream()
        .map(LatheLayout::paramsSourceTree)
        .filter(tree -> tree.startsWith(LatheLayout.SOURCE_SET_TREE_PREFIX))
        .filter(tree -> Files.exists(moduleDir.resolve(tree).resolve(classFile)))
        .findFirst()
        .map(
            tree ->
                "%s is only in the %s classes, whose launch Lathe does not capture yet: run it with the build"
                    .formatted(className, LatheLayout.sourceSetName(tree)));
  }
}
