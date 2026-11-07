package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.SourceStamp;
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
  private static final ContentFingerprint EDITED = new ContentFingerprint(43, 8);

  @TempDir private Path moduleDir;

  // A stamp without content (OpenJDK's), and one from before 1970, round-trip too; the server's
  // batch
  // write replaces and adds stamps in one go, leaving what the build compiled.
  @Test
  void writeAllThenLoad_roundTripsAndRecordUpdatesStampsButKeepsWhatTheBuildCompiled()
      throws IOException {
    CompiledStamps.writeAll(
        moduleDir,
        "classes",
        Map.of(FOO, new SourceStamp(100L, BUILT), GONE, new SourceStamp(-50L, null)),
        Map.of(FOO, BUILT));

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsOnly(
            Map.entry(FOO, new SourceStamp(100L, BUILT)),
            Map.entry(GONE, new SourceStamp(-50L, null)));
    // Saved '/'-separated on every OS, so a .lathe/ written on Windows reads the same elsewhere.
    assertThat(moduleDir.resolve(LatheLayout.compiledStampsFileName("classes")))
        .content()
        .contains("\"com/example/Foo.java\"");

    CompiledStamps.record(
        moduleDir,
        "classes",
        Map.of(FOO, new SourceStamp(150L, EDITED), BAR, new SourceStamp(200L, BUILT)));

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsOnly(
            Map.entry(FOO, new SourceStamp(150L, EDITED)),
            Map.entry(BAR, new SourceStamp(200L, BUILT)),
            Map.entry(GONE, new SourceStamp(-50L, null)));
    assertThat(CompiledStamps.loadBuilt(moduleDir, "classes"))
        .as("the server's writes leave what the build compiled")
        .containsExactly(Map.entry(FOO, BUILT));
  }

  @Test
  void prune_dropsNamedStampsKeepsWhatTheBuildCompiledAndIgnoresUnknownKeys() throws IOException {
    CompiledStamps.writeAll(
        moduleDir,
        "classes",
        Map.of(FOO, new SourceStamp(100L, BUILT), GONE, new SourceStamp(200L, BUILT)),
        Map.of(FOO, BUILT, GONE, BUILT));

    CompiledStamps.prune(moduleDir, "classes", Set.of(GONE));
    assertThat(CompiledStamps.load(moduleDir, "classes")).containsOnlyKeys(FOO);
    assertThat(CompiledStamps.loadBuilt(moduleDir, "classes"))
        .as("what the build compiled survives a deletion, for a restore to match")
        .containsOnlyKeys(FOO, GONE);

    // An unknown key prunes nothing and leaves the survivor in place.
    CompiledStamps.prune(moduleDir, "classes", Set.of(Path.of("com", "example", "Absent.java")));
    assertThat(CompiledStamps.load(moduleDir, "classes")).containsOnlyKeys(FOO);
  }

  @Test
  void load_missingOrCorruptFile_returnsEmpty() throws IOException {
    assertThat(CompiledStamps.load(moduleDir, "test-classes")).isEmpty();

    Files.writeString(
        moduleDir.resolve(LatheLayout.compiledStampsFileName("classes")), "{ not json");
    assertThat(CompiledStamps.load(moduleDir, "classes")).isEmpty();
  }
}
