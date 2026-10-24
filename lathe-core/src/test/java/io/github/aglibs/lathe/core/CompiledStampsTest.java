package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CompiledStampsTest {

  private static final Path FOO = Path.of("com", "example", "Foo.java");
  private static final Path BAR = Path.of("com", "example", "Bar.java");
  private static final Path GONE = Path.of("com", "example", "Gone.java");
  private static final ContentFingerprint BUILT = new ContentFingerprint(42, 7);

  @TempDir private Path moduleDir;

  @Test
  void writeAllThenLoad_roundTripsAndRecordUpdatesOneMtimeButKeepsWhatTheBuildCompiled()
      throws IOException {
    CompiledStamps.writeAll(moduleDir, "classes", Map.of(FOO, 100L), Map.of(FOO, BUILT));

    assertThat(CompiledStamps.load(moduleDir, "classes")).containsExactly(Map.entry(FOO, 100L));
    // Saved '/'-separated on every OS, so a .lathe/ written on Windows reads the same elsewhere.
    assertThat(moduleDir.resolve(LatheLayout.compiledStampsFileName("classes")))
        .content()
        .contains("\"com/example/Foo.java\"");

    CompiledStamps.record(moduleDir, "classes", BAR, 200L);
    CompiledStamps.record(moduleDir, "classes", FOO, 150L);

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsOnly(Map.entry(FOO, 150L), Map.entry(BAR, 200L));
    assertThat(CompiledStamps.loadBuilt(moduleDir, "classes"))
        .as("the server's writes leave what the build compiled")
        .containsExactly(Map.entry(FOO, BUILT));
  }

  @Test
  void prune_dropsNamedEntriesAndIgnoresUnknownKeys() throws IOException {
    CompiledStamps.writeAll(
        moduleDir, "classes", Map.of(FOO, 100L, GONE, 200L), Map.of(FOO, BUILT, GONE, BUILT));

    CompiledStamps.prune(moduleDir, "classes", Set.of(GONE));
    assertThat(CompiledStamps.load(moduleDir, "classes")).containsExactly(Map.entry(FOO, 100L));
    assertThat(CompiledStamps.loadBuilt(moduleDir, "classes")).containsOnlyKeys(FOO);

    // An unknown key prunes nothing and leaves the survivor in place.
    CompiledStamps.prune(moduleDir, "classes", Set.of(Path.of("com", "example", "Absent.java")));
    assertThat(CompiledStamps.load(moduleDir, "classes")).containsExactly(Map.entry(FOO, 100L));
  }

  @Test
  void load_missingOrCorruptFile_returnsEmpty() throws IOException {
    assertThat(CompiledStamps.load(moduleDir, "test-classes")).isEmpty();

    Files.writeString(
        moduleDir.resolve(LatheLayout.compiledStampsFileName("classes")), "{ not json");
    assertThat(CompiledStamps.load(moduleDir, "classes")).isEmpty();
  }
}
