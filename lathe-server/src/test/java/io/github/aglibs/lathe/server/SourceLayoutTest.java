package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLayoutTest {

  @TempDir private Path tmp;

  private Path sourceRoot;
  private Path sourceFile;
  private ModuleSourceConfig config;

  @BeforeEach
  void setUp() {
    sourceRoot = tmp.resolve("module/src/main/java");
    sourceFile = sourceRoot.resolve("com/example/Foo.java");
    config = TestCompiler.mainTreeConfig(tmp, "module");
  }

  @Test
  void packagesAndDirRun_moduleWithExtraSourceTree_listsItAsMainAndRunsOnlyTests()
      throws Exception {
    // A multi-release module's second compile (src/main/java21 into versions/21) is its own tree.
    final var java21Cfg =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/module"),
            tmp.resolve("module/target/classes/META-INF/versions/21"),
            tmp.resolve("module/src/main/java21"),
            null,
            "21");
    Files.createDirectories(tmp.resolve("module/src/main/java21/com/example/jdk21"));
    Files.writeString(
        tmp.resolve("module/src/main/java21/com/example/jdk21/Trees.java"),
        "package com.example.jdk21; class Trees {}");
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/verify/VerifyTest.java"),
        "package com.verify; class VerifyTest {}");
    final List<ModuleSourceConfig> configs = List.of(config, java21Cfg, testCfg);

    assertThat(new SourceLayout(configs, tmp).packages("module"))
        .contains(
            new PackageEntry("com.example.jdk21", "main"), new PackageEntry("com.verify", "test"));
    assertThat(new SourceLayout(configs, tmp).dirRun(tmp.resolve("module")).selections())
        .extracting(DirRun.Selector::selectorValue)
        .containsExactly("com.verify");
  }

  // A Gradle integrationTest source set is test code, but its tests have no captured launch: a
  // module run takes test's packages only, a dir inside it runs nothing, and a file there gives no
  // create-type context (a type created from it would land in test's folder). Its package is still
  // its own.
  @Test
  void dirRunAndContext_gradleTestSourceSet_leftOutOfRunsAndNewTypes() throws Exception {
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var integrationCfg =
        TestCompiler.sourceSetTreeConfig(tmp, "module", "integrationTest", true);
    final Path integrationFile =
        tmp.resolve("module/src/integrationTest/java/com/example/it/AppIT.java");
    Files.createDirectories(integrationFile.getParent());
    Files.writeString(integrationFile, "package com.example.it; class AppIT {}");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/verify/VerifyTest.java"),
        "package com.verify; class VerifyTest {}");
    final var layout = new SourceLayout(List.of(config, testCfg, integrationCfg), tmp);

    assertThat(layout.dirRun(tmp.resolve("module")).selections())
        .extracting(DirRun.Selector::selectorValue)
        .containsExactly("com.verify");
    assertThat(layout.dirRun(integrationFile.getParent()).selections()).isEmpty();
    assertThat(layout.resolveContext(integrationFile)).isNull();
    assertThat(layout.filePackage(integrationFile)).isEqualTo("com.example.it");
  }

  @Test
  void modules_distinctSortedModuleRels() {
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var otherCfg = TestCompiler.mainTreeConfig(tmp, "other");

    assertThat(new SourceLayout(List.of(config, testCfg, otherCfg), tmp).modules())
        .containsExactly("module", "other"); // "module" appears twice (main+test) → distinct
  }

  @Test
  void packages_onlyDirectoriesHoldingSourcesTaggedByScope() throws Exception {
    // real packages are the parent directories of `.java` files; intermediate dirs are not packages
    Files.createDirectories(sourceRoot.resolve("com/example/sub"));
    Files.writeString(
        sourceRoot.resolve("com/example/Foo.java"), "package com.example; class Foo {}");
    Files.writeString(
        sourceRoot.resolve("com/example/sub/Deep.java"), "package com.example.sub; class Deep {}");
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/verify/VerifyTest.java"),
        "package com.verify; class VerifyTest {}");

    final List<PackageEntry> pkgs =
        new SourceLayout(List.of(config, testCfg), tmp).packages("module");

    assertThat(pkgs)
        .contains(
            new PackageEntry("com.example", "main"),
            new PackageEntry("com.example.sub", "main"),
            new PackageEntry("com.verify", "test"));
    // intermediate directories and the (empty) default package are not offered
    assertThat(pkgs).extracting(PackageEntry::pkg).doesNotContain("com", "");

    // main-only module has no test-scope entries
    assertThat(new SourceLayout(List.of(config), tmp).packages("module"))
        .allMatch(entry -> entry.scope().equals("main"));
  }

  @Test
  void resolveContext_underMainTestAndOutsideRoot() {
    assertThat(new SourceLayout(List.of(config), tmp).resolveContext(sourceFile))
        .isEqualTo(new ContextInfo("module", "main", "com.example"));

    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var testFile = tmp.resolve("module/src/test/java/com/verify/FooTest.java");
    assertThat(new SourceLayout(List.of(testCfg), tmp).resolveContext(testFile))
        .isEqualTo(new ContextInfo("module", "test", "com.verify"));

    assertThat(new SourceLayout(List.of(config), tmp).resolveContext(tmp.resolve("loose/Bar.java")))
        .isNull();
  }

  @Test
  void dirRun_packageDirInsideTestTree_singlePackageSelector() {
    // A package dir resolves by pure path arithmetic (its package relative to the test root), so no
    // sources on disk are needed -- unlike the module fan-out below, which walks for packages.
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var pkgDir = tmp.resolve("module/src/test/java/com/verify");

    final DirRun run = new SourceLayout(List.of(config, testCfg), tmp).dirRun(pkgDir);

    assertThat(run.moduleRel()).isEqualTo("module");
    assertThat(run.selections()).containsExactly(new DirRun.Selector("PACKAGE", "com.verify"));
  }

  @Test
  void dirRun_moduleRootAndTestSourceRoot_fanOutAllTestPackages() throws Exception {
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/a"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/a/ATest.java"), "package com.a; class ATest {}");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/b"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/b/BTest.java"), "package com.b; class BTest {}");

    final DirRun fromRoot =
        new SourceLayout(List.of(config, testCfg), tmp).dirRun(tmp.resolve("module"));
    assertThat(fromRoot.moduleRel()).isEqualTo("module");
    assertThat(fromRoot.selections())
        .containsExactlyInAnyOrder(
            new DirRun.Selector("PACKAGE", "com.a"), new DirRun.Selector("PACKAGE", "com.b"));

    // The test source root itself carries the default package, so it runs the whole module too.
    final DirRun fromTestRoot =
        new SourceLayout(List.of(config, testCfg), tmp).dirRun(tmp.resolve("module/src/test/java"));
    assertThat(fromTestRoot.selections()).isEqualTo(fromRoot.selections());
  }

  @Test
  void dirRun_reactorRootOrUnmatchedDir_empty() {
    // Enclosure is decided from the configured source roots, not from files on disk.
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var otherTestCfg = TestCompiler.testTreeConfig(tmp, "other", "src/test/java");

    // The reactor root encloses two modules -- not a single run target.
    final DirRun reactor = new SourceLayout(List.of(testCfg, otherTestCfg), tmp).dirRun(tmp);
    assertThat(reactor.moduleRel()).isEmpty();
    assertThat(reactor.selections()).isEmpty();

    // A directory under no source root resolves to nothing either.
    assertThat(new SourceLayout(List.of(testCfg), tmp).dirRun(tmp.resolve("loose")).selections())
        .isEmpty();
  }

  @Test
  void testSources_resolvesClassesToFilesAndStripsInnerAndLeavesUnknownEmpty() throws Exception {
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    Files.createDirectories(tmp.resolve("module/src/test/java/com/verify"));
    Files.writeString(
        tmp.resolve("module/src/test/java/com/verify/FooTest.java"),
        "package com.verify; class FooTest {}");

    final List<TestSource> sources =
        new SourceLayout(List.of(config, testCfg), tmp)
            .testSources(
                "module",
                List.of("com.verify.FooTest", "com.verify.FooTest$Nested", "com.verify.GoneTest"));

    final String foo = tmp.resolve("module/src/test/java/com/verify/FooTest.java").toString();
    assertThat(sources)
        .containsExactly(
            new TestSource("com.verify.FooTest", foo),
            // inner class resolves to its top-level source file
            new TestSource("com.verify.FooTest$Nested", foo),
            // a class with no source file on disk resolves to an empty path
            new TestSource("com.verify.GoneTest", ""));
  }

  @Test
  void queries_beforeTheWorkspaceLoads_answerEmpty() {
    final var unloaded = new SourceLayout(List.of(), null);

    assertThat(unloaded.modules()).isEmpty();
    assertThat(unloaded.testModules()).isEmpty();
    assertThat(unloaded.packages("module")).isEmpty();
    assertThat(unloaded.resolveContext(sourceFile)).isNull();
    assertThat(unloaded.dirRun(sourceRoot).selections()).isEmpty();
    assertThat(unloaded.testSources("module", List.of("com.example.FooTest")))
        .containsExactly(new TestSource("com.example.FooTest", ""));
  }
}
