package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

// The tree of its own a class is compiled into (classes-<name>: a Gradle source set, or a Maven
// compile writing to such a directory) when the module's main and test trees lack it: the class's
// tests and main methods replay from that tree's launch, not the module's.
public final class SourceSetScope {

  private SourceSetScope() {}

  public static Optional<String> treeOf(
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
        .findFirst();
  }
}
