package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether a discovered {@code main} class is a test-scope class (a {@code main} method
 * living in {@code src/test/java}) or a regular main-scope class — the routing signal for which
 * launch template a main run/debug uses. A test-scope main is mirrored only under the module's test
 * output, so it needs the module's test launch (patched module + test graph) rather than the
 * derived main launch, whose module path never carries the test classes.
 */
public final class MainClassScope {

  private MainClassScope() {}

  public static boolean isTestScope(
      final Path workspaceRoot, final String moduleRel, final String mainClass) {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final String classFile = mainClass.replace('.', '/') + ".class";
    final boolean inTest =
        Files.exists(moduleDir.resolve(LatheLayout.TEST_CLASSES_DIR).resolve(classFile));
    final boolean inMain =
        Files.exists(moduleDir.resolve(LatheLayout.CLASSES_DIR).resolve(classFile));
    return inTest && !inMain;
  }
}
