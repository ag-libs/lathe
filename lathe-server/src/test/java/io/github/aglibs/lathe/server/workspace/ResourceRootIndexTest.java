package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.ResourceRootData;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceRootIndexTest {

  private static final Path WS = Path.of("/ws");

  private static ResourceRootIndex index(final List<ResourceRootData> roots) {
    return ResourceRootIndex.build(WS, roots);
  }

  private static List<ResourceRootData> standardRoots() {
    return List.of(
        new ResourceRootData(
            "app/src/main/resources", "app/target/classes", "", false),
        new ResourceRootData(
            "app/src/test/resources", "app/target/test-classes", "", false));
  }

  @Test
  void destinationFor_mainResource_mapsToClassesUnderLathe() {
    final var result =
        index(standardRoots())
            .destinationFor(Path.of("/ws/app/src/main/resources/config/app.conf"));

    assertThat(result).contains(Path.of("/ws/.lathe/app/classes/config/app.conf"));
  }

  @Test
  void destinationFor_testResource_mapsToTestClassesUnderLathe() {
    final var result =
        index(standardRoots())
            .destinationFor(Path.of("/ws/app/src/test/resources/fixtures/data.json"));

    assertThat(result).contains(Path.of("/ws/.lathe/app/test-classes/fixtures/data.json"));
  }

  @Test
  void destinationFor_targetPath_prependsUnderOutput() {
    final var roots =
        List.of(new ResourceRootData("app/config", "app/target/classes", "conf", false));

    final var result = index(roots).destinationFor(Path.of("/ws/app/config/db/schema.sql"));

    assertThat(result).contains(Path.of("/ws/.lathe/app/classes/conf/db/schema.sql"));
  }

  @Test
  void destinationFor_fileOutsideAnyResourceRoot_isEmpty() {
    final var result =
        index(standardRoots())
            .destinationFor(Path.of("/ws/app/src/main/java/com/example/App.java"));

    assertThat(result).isEmpty();
  }

  @Test
  void destinationFor_nestedRoots_longestMatchWins() {
    final var roots =
        List.of(
            new ResourceRootData("app/src/main/resources", "app/target/classes", "", false),
            new ResourceRootData(
                "app/src/main/resources/extra", "app/target/classes", "nested", false));

    final var result =
        index(roots).destinationFor(Path.of("/ws/app/src/main/resources/extra/thing.txt"));

    // The deeper root (…/extra) wins, so its targetPath "nested" applies and the rel is under it.
    assertThat(result).contains(Path.of("/ws/.lathe/app/classes/nested/thing.txt"));
  }

  @Test
  void destinationFor_emptyIndex_isEmpty() {
    assertThat(ResourceRootIndex.empty().destinationFor(Path.of("/ws/app/src/main/resources/x")))
        .isEmpty();
  }
}
