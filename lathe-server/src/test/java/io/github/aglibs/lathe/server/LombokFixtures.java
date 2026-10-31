package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Path;
import java.util.List;

// Lombok for tests. The build copies its jar into target/, never onto the test class path; a test
// module puts it on its class path and processor path, as a Lombok project does.
public final class LombokFixtures {

  // The newest JDK the Lombok the build pins (lombok.version) supports; one past its range stopped
  // 1.18.38 with ExceptionInInitializerError.
  public static final int MAX_JDK = 27;

  private static final Path PROCESSORS = Path.of("target", "test-processors");

  private LombokFixtures() {}

  public static Path jar() {
    return IOUtil.unchecked(
            () -> FileUtil.walkFiles(PROCESSORS, path -> path.toString().endsWith(".jar")))
        .get(0)
        .toAbsolutePath();
  }

  // A module of workspaceRoot whose build has Lombok, with proc its <proc> (null: the default).
  public static ModuleSourceConfig moduleConfig(
      final Path workspaceRoot, final Path sourceRoot, final String proc) {
    final ModuleSourceConfig base = TestCompiler.moduleConfig(workspaceRoot, sourceRoot);
    return new ModuleSourceConfig(
        base.moduleDir(),
        base.sourceTree(),
        base.outputDir(),
        base.originalGenSourcesDir(),
        base.sourceRoots(),
        List.of(jar()),
        base.modulepath(),
        List.of(jar()),
        base.release(),
        base.encoding(),
        base.parameters(),
        base.enablePreview(),
        proc,
        base.compilerArgs(),
        base.analysisMode(),
        base.externalOutput(),
        base.reactorRewrite());
  }
}
