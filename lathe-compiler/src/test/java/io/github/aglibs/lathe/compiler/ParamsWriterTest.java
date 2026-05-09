package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.ParamStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParamsWriterTest {

  @TempDir Path tmp;

  @Test
  void write_producesCorrectProperties() throws Exception {
    final var moduleRoot = tmp.resolve("module-a");
    final var outputDir = moduleRoot.resolve("target/classes");
    final var latheModuleDir = tmp.resolve(".lathe/module-a");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(outputDir.toString());
    config.setSourceLocations(List.of(moduleRoot.resolve("src/main/java").toString()));
    config.addClasspathEntry("/root/.m2/repository/guava.jar");
    config.setSourceEncoding("UTF-8");
    config.setReleaseVersion("25");

    ParamsWriter.write(config, latheModuleDir);

    final var store = ParamStore.load(latheModuleDir.resolve("lsp-params-classes.properties"));
    assertThat(store.get("sourceTree")).isEqualTo("classes");
    assertThat(store.get("outputDir")).isEqualTo(outputDir.toString());
    assertThat(store.get("release")).isEqualTo("25");
    assertThat(store.get("encoding")).isEqualTo("UTF-8");
    assertThat(store.readList("sourceRoots"))
        .containsExactly(moduleRoot.resolve("src/main/java").toString());
    assertThat(store.readList("classpath")).containsExactly("/root/.m2/repository/guava.jar");
    assertThat(store.get("formatVersion")).isNull();
    assertThat(store.get("projectDir")).isNull();
    assertThat(store.get("buildDir")).isNull();
  }

  @Test
  void write_omitsEmptyOptionalSections() throws Exception {
    final var moduleRoot = tmp.resolve("module-c");
    final var latheModuleDir = tmp.resolve(".lathe/module-c");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(moduleRoot.resolve("target/classes").toString());

    ParamsWriter.write(config, latheModuleDir);

    final var store = ParamStore.load(latheModuleDir.resolve("lsp-params-classes.properties"));
    assertThat(store.readList("classpath")).isEmpty();
    assertThat(store.readList("modulepath")).isEmpty();
    assertThat(store.readList("processorPath")).isEmpty();
    assertThat(store.get("proc")).isNull();
    assertThat(store.get("release")).isNull();
  }

  @Test
  void write_indexedListsAreCorrectlyNumbered() throws Exception {
    final var moduleRoot = tmp.resolve("module-d");
    final var latheModuleDir = tmp.resolve(".lathe/module-d");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(moduleRoot.resolve("target/classes").toString());
    config.addClasspathEntry("/path/to/dep-a.jar");
    config.addClasspathEntry("/path/to/dep-b.jar");
    config.addClasspathEntry("/path/to/dep-c.jar");

    ParamsWriter.write(config, latheModuleDir);

    final var store = ParamStore.load(latheModuleDir.resolve("lsp-params-classes.properties"));
    assertThat(store.readList("classpath"))
        .containsExactly("/path/to/dep-a.jar", "/path/to/dep-b.jar", "/path/to/dep-c.jar");
  }

  @Test
  void write_compilerArgsStoredAsList() throws Exception {
    final var moduleRoot = tmp.resolve("module-e");
    final var latheModuleDir = tmp.resolve(".lathe/module-e");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(moduleRoot.resolve("target/classes").toString());
    config.addCompilerCustomArgument("--module-version", "27.0.0-SNAPSHOT");
    config.addCompilerCustomArgument("-Werror", null);

    ParamsWriter.write(config, latheModuleDir);

    final var store = ParamStore.load(latheModuleDir.resolve("lsp-params-classes.properties"));
    assertThat(store.readList("compilerArgs"))
        .containsExactly("--module-version", "27.0.0-SNAPSHOT", "-Werror");
  }

  @Test
  void write_fileHasNoTimestampAndSortedKeys() throws Exception {
    final var moduleRoot = tmp.resolve("module-f");
    final var latheModuleDir = tmp.resolve(".lathe/module-f");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(moduleRoot.resolve("target/classes").toString());
    config.addClasspathEntry("/b.jar");
    config.addClasspathEntry("/a.jar");

    ParamsWriter.write(config, latheModuleDir);

    final var lines =
        Files.readAllLines(latheModuleDir.resolve("lsp-params-classes.properties")).stream()
            .filter(l -> !l.isBlank())
            .toList();
    assertThat(lines).noneMatch(l -> l.startsWith("#"));
    final var keys = lines.stream().map(l -> l.split("=", 2)[0]).toList();
    assertThat(keys).isSortedAccordingTo(String::compareTo);
  }
}
