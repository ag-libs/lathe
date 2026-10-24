package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapturePrunerTest {

  @TempDir Path root;

  // Module a keeps its main tree but lost its tests; a/b nests inside it and is current; gone left
  // the build. Only what the build no longer has goes, file by file.
  @Test
  void prune_removedModuleAndTree_deletesOnlyThoseCaptures() throws Exception {
    capture("", LatheLayout.CLASSES_DIR);
    capture("a", LatheLayout.CLASSES_DIR);
    capture("a", LatheLayout.TEST_CLASSES_DIR);
    capture("a/b", LatheLayout.CLASSES_DIR);
    capture("gone", LatheLayout.CLASSES_DIR);
    write(".lathe/a/" + LatheLayout.TEST_LAUNCH_FILE);
    write(".lathe/gone/" + LatheLayout.MAIN_LAUNCH_FILE);
    write(".lathe/gone/" + LatheLayout.TEST_DISCOVERY_FILE);
    write(".lathe/a/" + LatheLayout.TEST_FILTER_FILE);
    write(".lathe/" + LatheLayout.WORKSPACE_JSON);

    CapturePruner.prune(
        root,
        Map.of(
            Path.of(""), Set.of(LatheLayout.CLASSES_DIR),
            Path.of("a"), Set.of(LatheLayout.CLASSES_DIR),
            Path.of("a/b"), Set.of(LatheLayout.CLASSES_DIR)));

    assertCaptured("", LatheLayout.CLASSES_DIR, true);
    assertCaptured("a", LatheLayout.CLASSES_DIR, true);
    assertCaptured("a", LatheLayout.TEST_CLASSES_DIR, false);
    assertCaptured("a/b", LatheLayout.CLASSES_DIR, true);
    assertCaptured("gone", LatheLayout.CLASSES_DIR, false);
    assertThat(lathe("a").resolve(LatheLayout.TEST_LAUNCH_FILE)).exists();
    assertThat(lathe("gone").resolve(LatheLayout.MAIN_LAUNCH_FILE)).doesNotExist();
    assertThat(lathe("gone").resolve(LatheLayout.TEST_DISCOVERY_FILE)).doesNotExist();
    assertThat(lathe("a").resolve(LatheLayout.TEST_FILTER_FILE)).exists();
    assertThat(lathe("").resolve(LatheLayout.WORKSPACE_JSON)).exists();
  }

  @Test
  void prune_noLatheDirectory_doesNothing() {
    CapturePruner.prune(root, Map.of());

    assertThat(lathe("")).doesNotExist();
  }

  // What one compile leaves: its params and stamps, the bytecode mirror and the processor output.
  private void capture(final String module, final String tree) throws Exception {
    final String dir = ".lathe/%s/".formatted(module);
    write(dir + LatheLayout.paramsFileName(tree));
    write(dir + LatheLayout.compiledStampsFileName(tree));
    write("%s%s/com/example/A.class".formatted(dir, tree));
    write("%s%s/com/example/Gen.java".formatted(dir, LatheLayout.generatedSourcesDir(tree)));
  }

  private void assertCaptured(final String module, final String tree, final boolean captured) {
    final Path dir = lathe(module);
    assertThat(
            Set.of(
                dir.resolve(LatheLayout.paramsFileName(tree)),
                dir.resolve(LatheLayout.compiledStampsFileName(tree)),
                dir.resolve(tree),
                dir.resolve(LatheLayout.generatedSourcesDir(tree))))
        .allMatch(path -> Files.exists(path) == captured);
  }

  private Path lathe(final String module) {
    return root.resolve(LatheLayout.LATHE_DIR).resolve(module);
  }

  private void write(final String relative) throws Exception {
    final Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x");
  }
}
