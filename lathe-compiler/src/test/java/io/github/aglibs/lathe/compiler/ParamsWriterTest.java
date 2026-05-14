package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParamsWriterTest {

  @TempDir Path tmp;

  @Test
  void write_producesCorrectJson() throws Exception {
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

    final var result =
        Json.read(latheModuleDir.resolve("lsp-params-classes.json"), ModuleConfigData.class);
    assertThat(result.sourceTree()).isEqualTo("classes");
    assertThat(result.outputDir()).isEqualTo(outputDir.toString());
    assertThat(result.release()).isEqualTo("25");
    assertThat(result.encoding()).isEqualTo("UTF-8");
    assertThat(result.sourceRoots())
        .containsExactly(moduleRoot.resolve("src/main/java").toString());
    assertThat(result.classpath()).containsExactly("/root/.m2/repository/guava.jar");
  }

  @Test
  void write_omitsNullForMissingOptionalFields() throws Exception {
    final var moduleRoot = tmp.resolve("module-c");
    final var latheModuleDir = tmp.resolve(".lathe/module-c");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(moduleRoot.resolve("target/classes").toString());

    ParamsWriter.write(config, latheModuleDir);

    final var result =
        Json.read(latheModuleDir.resolve("lsp-params-classes.json"), ModuleConfigData.class);
    assertThat(result.classpath()).isEmpty();
    assertThat(result.modulepath()).isEmpty();
    assertThat(result.processorPath()).isEmpty();
    assertThat(result.proc()).isNull();
    assertThat(result.release()).isNull();
  }

  @Test
  void write_classpathOrderIsPreserved() throws Exception {
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

    final var result =
        Json.read(latheModuleDir.resolve("lsp-params-classes.json"), ModuleConfigData.class);
    assertThat(result.classpath())
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

    final var result =
        Json.read(latheModuleDir.resolve("lsp-params-classes.json"), ModuleConfigData.class);
    assertThat(result.compilerArgs())
        .containsExactly("--module-version", "27.0.0-SNAPSHOT", "-Werror");
  }

  @Test
  void write_producesReadableJsonFile() throws Exception {
    final var moduleRoot = tmp.resolve("module-f");
    final var latheModuleDir = tmp.resolve(".lathe/module-f");
    Files.createDirectories(latheModuleDir);

    final var config = new CompilerConfiguration();
    config.setWorkingDirectory(moduleRoot.toFile());
    config.setOutputLocation(moduleRoot.resolve("target/classes").toString());
    config.addClasspathEntry("/b.jar");
    config.addClasspathEntry("/a.jar");

    ParamsWriter.write(config, latheModuleDir);

    final var jsonFile = latheModuleDir.resolve("lsp-params-classes.json");
    assertThat(Files.exists(jsonFile)).isTrue();
    final var content = Files.readString(jsonFile);
    assertThat(content).contains("\"sourceTree\"").contains("\"classpath\"");
    final var result = Json.read(jsonFile, ModuleConfigData.class);
    assertThat(result.classpath()).containsExactly("/b.jar", "/a.jar");
  }
}
