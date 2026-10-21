package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

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

  @TempDir private Path moduleDir;

  @Test
  void writeAllThenLoad_roundTripsAndRecordUpdatesOneEntry() throws IOException {
    CompiledStamps.writeAll(moduleDir, "classes", Map.of(FOO, 100L));

    assertThat(CompiledStamps.load(moduleDir, "classes")).containsExactly(Map.entry(FOO, 100L));
    // Saved '/'-separated on every OS, so a .lathe/ written on Windows reads the same elsewhere.
    assertThat(moduleDir.resolve(LatheLayout.compiledStampsFileName("classes")))
        .content()
        .contains("\"com/example/Foo.java\"");

    CompiledStamps.record(moduleDir, "classes", BAR, 200L);
    CompiledStamps.record(moduleDir, "classes", FOO, 150L);

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsOnly(Map.entry(FOO, 150L), Map.entry(BAR, 200L));
  }

  @Test
  void prune_dropsNamedEntriesAndIgnoresUnknownKeys() throws IOException {
    CompiledStamps.writeAll(moduleDir, "classes", Map.of(FOO, 100L, GONE, 200L));

    CompiledStamps.prune(moduleDir, "classes", Set.of(GONE));
    assertThat(CompiledStamps.load(moduleDir, "classes")).containsExactly(Map.entry(FOO, 100L));

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
