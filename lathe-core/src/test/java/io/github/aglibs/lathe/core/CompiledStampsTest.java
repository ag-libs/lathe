package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CompiledStampsTest {

  @TempDir private Path moduleDir;

  @Test
  void writeAllThenLoad_roundTripsAndRecordUpdatesOneEntry() throws IOException {
    CompiledStamps.writeAll(moduleDir, "classes", Map.of("com/example/Foo.java", 100L));

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsExactly(Map.entry("com/example/Foo.java", 100L));

    CompiledStamps.record(moduleDir, "classes", "com/example/Bar.java", 200L);
    CompiledStamps.record(moduleDir, "classes", "com/example/Foo.java", 150L);

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsOnly(
            Map.entry("com/example/Foo.java", 150L), Map.entry("com/example/Bar.java", 200L));
  }

  @Test
  void load_missingOrCorruptFile_returnsEmpty() throws IOException {
    assertThat(CompiledStamps.load(moduleDir, "test-classes")).isEmpty();

    Files.writeString(
        moduleDir.resolve(LatheLayout.compiledStampsFileName("classes")), "{ not json");
    assertThat(CompiledStamps.load(moduleDir, "classes")).isEmpty();
  }
}
