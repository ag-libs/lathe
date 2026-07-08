package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReactorRewrite {

  private ReactorRewrite() {}

  public static String toLathe(final String path, final Path workspaceRoot) {
    final var candidate = Path.of(path).normalize();
    final var root = workspaceRoot.normalize();
    if (!candidate.startsWith(root)) {
      return path;
    }

    final var relative = root.relativize(candidate);
    if (relative.getParent() == null) {
      return path;
    }

    final String sourceTree = relative.getFileName().toString();
    final Path parent = relative.getParent();
    if (!LatheLayout.TARGET_DIR.equals(parent.getFileName().toString())) {
      return path;
    }

    final String latheSourceTree = latheSourceTree(sourceTree);
    if (latheSourceTree == null) {
      return path;
    }

    final Path moduleRel = parent.getParent();
    return moduleRel != null
        ? root.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel).resolve(latheSourceTree).toString()
        : root.resolve(LatheLayout.LATHE_DIR).resolve(latheSourceTree).toString();
  }

  public static List<String> toLathe(final List<String> paths, final Path workspaceRoot) {
    return paths.stream().map(path -> toLathe(path, workspaceRoot)).toList();
  }

  public static Map<String, String> toLathe(
      final Map<String, String> paths, final Path workspaceRoot) {
    final var rewritten = new LinkedHashMap<String, String>();
    for (final Map.Entry<String, String> entry : paths.entrySet()) {
      rewritten.put(entry.getKey(), toLathe(entry.getValue(), workspaceRoot));
    }

    return Collections.unmodifiableMap(rewritten);
  }

  private static String latheSourceTree(final String sourceTree) {
    if (LatheLayout.CLASSES_DIR.equals(sourceTree)
        || LatheLayout.TEST_CLASSES_DIR.equals(sourceTree)) {
      return sourceTree;
    }

    if (sourceTree.endsWith("-tests.jar")) {
      return LatheLayout.TEST_CLASSES_DIR;
    }

    if (sourceTree.endsWith(".jar")) {
      return LatheLayout.CLASSES_DIR;
    }

    return null;
  }
}
