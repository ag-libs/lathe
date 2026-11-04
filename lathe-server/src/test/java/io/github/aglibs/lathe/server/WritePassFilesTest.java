package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WritePassFilesTest {

  @TempDir private Path tmp;

  private Path sourceRoot;
  private Path sourceFile;
  private ModuleSourceConfig config;
  private Path outputDir;

  @BeforeEach
  void setUp() throws IOException {
    sourceRoot = tmp.resolve("module/src/main/java");
    sourceFile = sourceRoot.resolve("com/example/Foo.java");
    config = config(sourceRoot);
    outputDir = config.latheClassesDir().resolve("com/example");
    Files.createDirectories(outputDir);
  }

  @Test
  void deleteClassOutputs_javaSource_removesTopLevelAndNestedClassFiles() throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");
    Files.writeString(outputDir.resolve("Foobar.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    final int deleted = WritePass.deleteClassOutputs(config, sourceFile, Set.of());

    assertThat(deleted).isEqualTo(3);
    assertThat(outputDir.resolve("Foo.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
    assertThat(outputDir.resolve("Foobar.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  @Test
  void deleteClassOutputs_sourceAtTheTreeRoot_prunesItsClass_skipsPackageDirectories()
      throws Exception {
    // module-info.java's class sits in the classes root, beside the package directories.
    final Path moduleInfo = sourceRoot.resolve("module-info.java");
    Files.createDirectories(sourceRoot);
    Files.writeString(moduleInfo, "module app {}");
    final Path classesRoot = config.latheClassesDir();
    Files.writeString(classesRoot.resolve("module-info.class"), "");

    assertThat(WritePass.deleteClassOutputs(config, moduleInfo, Set.of("module-info")))
        .as("kept: the write produced it")
        .isZero();
    assertThat(WritePass.deleteClassOutputs(config, moduleInfo, Set.of())).isEqualTo(1);
    assertThat(classesRoot.resolve("com")).isDirectory();
  }

  @Test
  void deleteClassOutputs_nonJavaSource_removesNothing() throws Exception {
    final var txtFile = tmp.resolve("module/src/main/java/com/example/Foo.txt");
    Files.writeString(outputDir.resolve("Foo.class"), "");

    final int deleted = WritePass.deleteClassOutputs(config, txtFile, Set.of());

    assertThat(deleted).isZero();
    assertThat(outputDir.resolve("Foo.class")).exists();
  }

  @Test
  void deleteOrphanedClassOutputs_deletedSource_removesClassesAndPrunesStamp() throws Exception {
    writeJava("Live", 2_000L);
    Files.writeString(outputDir.resolve("Live.class"), "");
    Files.writeString(outputDir.resolve("Ghost.class"), "");
    Files.writeString(outputDir.resolve("Ghost$Inner.class"), "");
    writeStamps(
        Map.of(
            Path.of("com/example/Live.java"), 2_000L, Path.of("com/example/Ghost.java"), 1_000L));

    final int removed = WritePass.deleteOrphanedClassOutputs(TreeState.read(config));

    assertThat(removed).isEqualTo(2);
    assertThat(outputDir.resolve("Ghost.class")).doesNotExist();
    assertThat(outputDir.resolve("Ghost$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Live.class")).exists();
    assertThat(CompiledStamps.load(config.moduleDir(), config.sourceTree()))
        .containsOnlyKeys(Path.of("com/example/Live.java"));
  }

  @Test
  void deleteOrphanedClassOutputs_allSourcesPresent_noOpKeepsStamps() throws Exception {
    writeJava("Live", 2_000L);
    Files.writeString(outputDir.resolve("Live.class"), "");
    writeStamps(Map.of(Path.of("com/example/Live.java"), 2_000L));

    final int removed = WritePass.deleteOrphanedClassOutputs(TreeState.read(config));

    assertThat(removed).isZero();
    assertThat(outputDir.resolve("Live.class")).exists();
    assertThat(CompiledStamps.load(config.moduleDir(), config.sourceTree()))
        .containsOnlyKeys(Path.of("com/example/Live.java"));
  }

  @Test
  void deleteClassOutputs_nestedAndAnonymousRemoved_deletesOnlyUnkeptOutputsOfTheSource()
      throws Exception {
    Files.writeString(outputDir.resolve("Foo.class"), "");
    Files.writeString(outputDir.resolve("Foo$Inner.class"), "");
    Files.writeString(outputDir.resolve("Foo$1.class"), "");
    Files.writeString(outputDir.resolve("Bar.class"), "");

    final int deleted = WritePass.deleteClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(2);
    assertThat(outputDir.resolve("Foo$Inner.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo$1.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
  }

  @Test
  void deleteClassOutputs_packagePrivateSiblingRemoved_deletesOnlyItsOwnSibling() throws Exception {
    // Helper was declared in Foo.java and is gone from the latest compile; Other is a sibling of
    // Bar.java, which this save did not touch.
    compileIntoMirror(
        Map.of(
            "Foo.java", "class Foo {} class Helper {}", "Bar.java", "class Bar {} class Other {}"));

    final int deleted = WritePass.deleteClassOutputs(config, sourceFile, Set.of("com.example.Foo"));

    assertThat(deleted).isEqualTo(1);
    assertThat(outputDir.resolve("Helper.class")).doesNotExist();
    assertThat(outputDir.resolve("Foo.class")).exists();
    assertThat(outputDir.resolve("Bar.class")).exists();
    assertThat(outputDir.resolve("Other.class")).exists();
  }

  @Test
  void wroteClassOf_sourceDeclaringNoTypeNamedAfterIt_matchesOnlyItsOwnWrittenClasses()
      throws Exception {
    // Foo.java declares no Foo, so its outputs are tied to it only by the SourceFile attribute.
    compileIntoMirror(
        Map.of(
            "Foo.java", "class Alpha {} class Beta {}", "Bar.java", "class Bar {} class Other {}"));

    assertThat(WritePass.wroteClassOf(config, sourceFile, Set.of("com.example.Alpha"))).isTrue();
    // A failed compile writes nothing; another source's classes do not count either.
    assertThat(WritePass.wroteClassOf(config, sourceFile, Set.of())).isFalse();
    assertThat(WritePass.wroteClassOf(config, sourceFile, Set.of("com.example.Other"))).isFalse();
  }

  @Test
  void withoutFailedCompiles_unchangedSinceFailure_skipped_editedOrNeverFailedKept() {
    final var broken = sourceRoot.resolve("com/example/Broken.java");
    final var edited = sourceRoot.resolve("com/example/Edited.java");
    final var fresh = sourceRoot.resolve("com/example/Fresh.java");
    final Map<Path, Long> stale = Map.of(broken, 100L, edited, 200L, fresh, 300L);
    final Map<Path, WritePass.FailedWrite> failed =
        Map.of(
            broken,
            new WritePass.FailedWrite(100L, "1: error"),
            edited,
            new WritePass.FailedWrite(150L, "1: error"));

    assertThat(WritePass.withoutFailedCompiles(stale, failed)).containsOnlyKeys(edited, fresh);
  }

  @Test
  void treeBatch_readyNotHeld_withFailedRidingAlong_emptyWhenNothingReady() {
    final var settled = sourceRoot.resolve("com/example/Settled.java");
    final var moving = sourceRoot.resolve("com/example/Moving.java");
    final var renamed = sourceRoot.resolve("com/example/Renamed.java");
    final var failed = sourceRoot.resolve("com/example/Failed.java");
    final List<Path> tree = List.of(settled, moving, renamed, failed);

    // Moving has not settled across two ticks; Renamed waits for its open rename's commit; Failed
    // failed unchanged since and rides along, as this write may bring what it was missing.
    assertThat(WritePass.treeBatch(tree, Set.of(settled, renamed), Set.of(failed), Set.of(renamed)))
        .as("ready and not held, plus failed-unchanged, in the tree's order")
        .containsExactly(settled, failed);
    assertThat(WritePass.treeBatch(tree, Set.of(renamed), Set.of(failed), Set.of(renamed)))
        .as("only a held file is ready: nothing writes, so the failed one does not ride alone")
        .isEmpty();
    assertThat(WritePass.treeBatch(tree, Set.of(), Set.of(failed), Set.of()))
        .as("nothing ready")
        .isEmpty();
  }

  @Test
  void stableSources_unchangedMtimeAcrossTicks_included_newOrMovedExcluded() {
    final var settled = sourceRoot.resolve("com/example/Settled.java");
    final var moving = sourceRoot.resolve("com/example/Moving.java");
    final var fresh = sourceRoot.resolve("com/example/Fresh.java");
    final Map<Path, Long> previous = Map.of(settled, 100L, moving, 100L);
    final Map<Path, Long> current = Map.of(settled, 100L, moving, 200L, fresh, 300L);

    // settled held its mtime across both ticks; moving changed since last tick; fresh is brand new.
    assertThat(WritePass.stableSources(current, previous)).containsExactly(settled);
  }

  @Test
  void staleModules_editedAddedOrRestoredOlder_stale_matchingStampFresh() throws Exception {
    writeJava("Edited", 5_000L); // stamped at 1000, edited after → stale
    writeJava("Added", 9_000L); // no stamp (a newly added file) → stale, and the newest
    writeJava("Restored", 1_000L); // older than its stamp, as a restore keeping file times → stale
    writeJava("Fresh", 2_000L); // stamp matches its mtime → up to date, ignored
    writeStamps(
        Map.of(
            Path.of("com/example/Edited.java"),
            1_000L,
            Path.of("com/example/Restored.java"),
            4_000L,
            Path.of("com/example/Fresh.java"),
            2_000L));

    final var scan = staleModules(config);

    assertThat(scan.modules()).containsExactly(config);
    assertThat(scan.staleFiles())
        .extracting(path -> path.getFileName().toString())
        .containsExactlyInAnyOrder("Edited.java", "Added.java", "Restored.java");
  }

  @Test
  void staleModules_missingClassButStampPresent_notStale() throws Exception {
    // A mismatched source whose class never lands at the derived path — a matching stamp is fresh.
    writeJava("Mismatch", 3_000L);
    writeStamps(Map.of(Path.of("com/example/Mismatch.java"), 3_000L));

    final var scan = staleModules(config);

    assertThat(scan.modules()).isEmpty();
  }

  @Test
  void staleModules_orphanStampForDeletedSource_ignored() throws Exception {
    writeJava("Live", 2_000L);
    writeStamps(
        Map.of(
            Path.of("com/example/Live.java"), 2_000L, Path.of("com/example/Ghost.java"), 1_000L));

    final var scan = staleModules(config);

    assertThat(scan.modules()).isEmpty();
  }

  @Test
  void staleModules_includesOpenFiles_excludesGeneratedRoots() throws Exception {
    // The write pass compiles the whole changed set together, so a saved rename's open files must
    // be
    // included -- an interface rename saves its users, which resolve the new type only if they
    // compile in the same batch.
    writeJava("Open", 9_000L); // stale and open -> still counted
    writeJava("Real", 5_000L);

    // A module whose sole source root IS its annotation-processor output: still wholly excluded.
    final var genRoot = tmp.resolve("gen-module/target/generated-sources/annotations");
    TestCompiler.writeAt(genRoot.resolve("com/example/Gen.java"), "", 8_000L);
    final var genConfig =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/gen-module"),
            tmp.resolve("gen-module/target/classes"),
            genRoot,
            genRoot);

    final var scan = staleModules(config, genConfig);

    assertThat(scan.modules()).containsExactly(config);
    assertThat(scan.staleFiles())
        .extracting(path -> path.getFileName().toString())
        .containsExactlyInAnyOrder("Open.java", "Real.java");
  }

  @Test
  void staleMtimes_samePathStaleUnderTwoConfigs_dedupesNotThrows() {
    // Overlapping build-tool source roots can report the same file as stale under two configs; its
    // mtime is identical either way, so the scan dedupes by path rather than crashing on a dup key.
    final var shared = tmp.resolve("make/jdk/src/classes/build/tools/depend/Depend.java");
    final var scan =
        new WritePass.StaleScan(
            Map.of(config, List.of(shared), config(tmp.resolve("other/src")), List.of(shared)));

    assertThat(WritePass.staleMtimes(scan)).containsOnlyKeys(shared);
  }

  private Path writeJava(final String typeName, final long mtime) throws IOException {
    return TestCompiler.writeAt(sourceRoot.resolve("com/example/" + typeName + ".java"), "", mtime);
  }

  private void writeStamps(final Map<Path, Long> stamps) throws IOException {
    CompiledStamps.writeAll(
        config.moduleDir(),
        config.sourceTree(),
        stamps.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> new SourceStamp(entry.getValue(), null))),
        Map.of());
  }

  private static WritePass.StaleScan staleModules(final ModuleSourceConfig... configs) {
    return WritePass.staleModules(Arrays.stream(configs).map(TreeState::read).toList());
  }

  private ModuleSourceConfig config(final Path sourceRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"), tmp.resolve("module/target/classes"), sourceRoot);
  }

  // Real class files, so each carries the SourceFile attribute naming the source it came from.
  private void compileIntoMirror(final Map<String, String> bodiesByFileName) throws IOException {
    final var sourceDir = Files.createDirectories(tmp.resolve("compile-src/com/example"));
    bodiesByFileName.forEach(
        (fileName, body) ->
            IOUtil.unchecked(
                () ->
                    Files.writeString(
                        sourceDir.resolve(fileName), "package com.example; %s".formatted(body))));
    final String[] args =
        Stream.concat(
                Stream.of("-d", config.latheClassesDir().toString()),
                bodiesByFileName.keySet().stream().map(sourceDir::resolve).map(Path::toString))
            .toArray(String[]::new);

    assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args)).isZero();
  }
}
