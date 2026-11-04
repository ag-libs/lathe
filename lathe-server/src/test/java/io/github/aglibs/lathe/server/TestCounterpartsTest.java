package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCounterpartsTest {

  @TempDir private Path tmp;

  private Path sourceRoot;
  private Path sourceFile;
  private ModuleSourceConfig config;

  @BeforeEach
  void setUp() {
    sourceRoot = tmp.resolve("module/src/main/java");
    sourceFile = sourceRoot.resolve("com/example/Foo.java");
    config =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/module"), tmp.resolve("module/target/classes"), sourceRoot);
  }

  // Go to test: a main type's tests by the build's default test names, in its own module and in a
  // separate test module, same package first; a main-tree class named like a test is not one.
  // From a test, the type it tests, not a nested type of that name; a type without tests has none.
  @Test
  void testCounterparts_mainAndTestFiles_rankedTestsAndTheirSubject() throws Exception {
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var itCfg = TestCompiler.testTreeConfig(tmp, "it", "src/test/java");
    final var itMain =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/it"),
            tmp.resolve("it/target/classes"),
            tmp.resolve("it/src/main/java"));
    final List<ModuleSourceConfig> configs = List.of(config, testCfg, itCfg, itMain);
    final Map<String, Path> files =
        Map.of(
            "com.example.Foo", sourceFile,
            "com.example.Bar", sourceRoot.resolve("com/example/Bar.java"),
            "com.example.util.FooTest", sourceRoot.resolve("com/example/util/FooTest.java"),
            "com.example.FooTest", tmp.resolve("module/src/test/java/com/example/FooTest.java"),
            "com.example.FooIT", tmp.resolve("module/src/test/java/com/example/FooIT.java"),
            "com.example.FooTests", tmp.resolve("it/src/test/java/com/example/FooTests.java"),
            "com.other.TestFoo", tmp.resolve("it/src/test/java/com/other/TestFoo.java"),
            "com.other.Outer$Foo", tmp.resolve("it/src/main/java/com/other/Outer.java"));
    for (final Path file : files.values()) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, "");
    }

    final WorkspaceTypeIndex index =
        TempSourceCompiler.typeIndex(
            tmp.resolve("index.json"),
            List.of(),
            files.keySet().stream()
                .map(
                    name ->
                        new TypeIndexEntry(
                            name.substring(
                                Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1),
                            name,
                            name.substring(0, name.lastIndexOf('.')),
                            io.github.aglibs.lathe.core.typeindex.TypeKind.CLASS,
                            true,
                            List.of()))
                .toList());
    final Function<Path, Optional<ModuleSourceConfig>> configOf =
        path ->
            configs.stream()
                .filter(candidate -> candidate.sourceRoots().stream().anyMatch(path::startsWith))
                .findFirst();
    final List<Path> roots = configs.stream().flatMap(c -> c.searchRoots().stream()).toList();
    final Function<String, TestCounterparts> counterparts =
        name ->
            TestCounterparts.find(
                files.get(name), name.substring(0, name.lastIndexOf('.')), configOf, index, roots);

    assertThat(counterparts.apply("com.example.Foo").fromTest()).isFalse();
    assertThat(counterparts.apply("com.example.Foo").targets())
        .extracting(TestCounterparts.Target::qualifiedName)
        .containsExactly(
            "com.example.FooTest",
            "com.example.FooIT",
            "com.example.FooTests",
            "com.other.TestFoo");
    assertThat(counterparts.apply("com.example.Foo").targets().get(0).uri())
        .isEqualTo(LatheUri.canonical(files.get("com.example.FooTest").toUri().toString()));
    assertThat(counterparts.apply("com.example.FooIT").fromTest()).isTrue();
    assertThat(counterparts.apply("com.example.FooIT").targets())
        .extracting(TestCounterparts.Target::qualifiedName)
        .containsExactly("com.example.Foo");
    assertThat(counterparts.apply("com.other.TestFoo").targets())
        .extracting(TestCounterparts.Target::qualifiedName)
        .containsExactly("com.example.Foo");
    assertThat(counterparts.apply("com.example.Bar").targets()).isEmpty();
  }

  @Test
  void testCounterpartNames_conventions_testNamesAndTheSubjectTheyStripTo() {
    assertThat(TestCounterparts.testNames("Foo"))
        .containsExactly(
            "FooTest", "FooTests", "FooTestCase", "FooIT", "FooITCase", "TestFoo", "ITFoo");
    assertThat(TestCounterparts.subjectNames("FooTestCase")).containsExactly("Foo");
    assertThat(TestCounterparts.subjectNames("ITFoo")).containsExactly("Foo");
    assertThat(TestCounterparts.subjectNames("Testing")).isEmpty();
    assertThat(TestCounterparts.subjectNames("Test")).isEmpty();
  }
}
