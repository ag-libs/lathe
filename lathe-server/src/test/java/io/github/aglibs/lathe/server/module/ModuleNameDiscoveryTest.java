package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleNameDiscoveryTest {

  @TempDir Path tmp;

  @Test
  void systemModuleNames_defaultJdk_includesPlatformModules() {
    assertThat(ModuleNameDiscovery.systemModuleNames())
        .contains("java.base", "java.logging", "java.net.http");
  }

  @Test
  void modulePathModuleNames_dependencyJar_returnsModuleName() throws IOException {
    final Path sourceRoot = tmp.resolve("dep-src");
    final Path classDir = tmp.resolve("dep-classes");
    final Path jar = tmp.resolve("dep.jar");
    writeModule(sourceRoot, "com.example.dep", "com.example.dep");
    TestCompiler.compileToJar(
        jar,
        classDir,
        List.of(),
        sourceRoot.resolve("module-info.java"),
        sourceRoot.resolve("com/example/dep/Dep.java"));

    final ModuleSourceConfig config = configWithModulePath(List.of(jar));

    assertThat(ModuleNameDiscovery.modulePathModuleNames(config)).contains("com.example.dep");
  }

  @Test
  void modulePathModuleNames_reactorClassesDir_returnsModuleName() throws IOException {
    final Path sourceRoot = tmp.resolve("reactor-src");
    final Path originalTarget = tmp.resolve("reactor/target/classes");
    final Path remappedTarget = tmp.resolve(".lathe/reactor/classes");
    writeModule(sourceRoot, "com.example.reactor", "com.example.reactor");
    TestCompiler.compileToDir(
        remappedTarget,
        sourceRoot.resolve("module-info.java"),
        sourceRoot.resolve("com/example/reactor/Reactor.java"));

    final ModuleSourceConfig config = configWithModulePath(List.of(originalTarget));

    assertThat(ModuleNameDiscovery.modulePathModuleNames(config)).contains("com.example.reactor");
  }

  private ModuleSourceConfig configWithModulePath(final List<Path> modulepath) {
    return new ModuleSourceConfig(
        tmp.resolve(".lathe/app"),
        "classes",
        tmp.resolve("app/target/classes"),
        null,
        List.of(tmp.resolve("app/src/main/java")),
        List.of(),
        modulepath,
        List.of(),
        "21",
        "UTF-8",
        false,
        false,
        null,
        List.of());
  }

  private static void writeModule(
      final Path sourceRoot, final String moduleName, final String packageName) throws IOException {
    final Path packageDir = sourceRoot.resolve(packageName.replace('.', '/'));
    Files.createDirectories(packageDir);
    Files.writeString(
        sourceRoot.resolve("module-info.java"),
        "module %s { exports %s; }".formatted(moduleName, packageName));
    Files.writeString(
        packageDir.resolve("%s.java".formatted(className(packageName))),
        "package %s; public final class %s {}".formatted(packageName, className(packageName)));
  }

  private static String className(final String packageName) {
    final int index = packageName.lastIndexOf('.');
    final String segment = index >= 0 ? packageName.substring(index + 1) : packageName;
    return "%s%s".formatted(Character.toUpperCase(segment.charAt(0)), segment.substring(1));
  }
}
