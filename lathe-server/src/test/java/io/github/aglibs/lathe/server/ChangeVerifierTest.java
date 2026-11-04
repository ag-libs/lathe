package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.server.engine.LatheModuleSummary;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.lathe.server.module.WorkspaceModuleGraph;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeVerifierTest {

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

  @Test
  void moduleSummaries_mainAndTestTrees_hasTestsOnlyWhereATestTreeExists() {
    final var testCfg = TestCompiler.testTreeConfig(tmp, "module", "src/test/java");
    final var otherCfg =
        TestCompiler.moduleConfig(
            tmp.resolve(".lathe/other"),
            tmp.resolve("other/target/classes"),
            tmp.resolve("other/src/main/java"));
    final List<ModuleSourceConfig> configs = List.of(config, testCfg, otherCfg);

    assertThat(WorkspaceSession.moduleSummaries(configs, tmp, WorkspaceModuleGraph.build(configs)))
        .extracting(LatheModuleSummary::module, LatheModuleSummary::hasTests)
        .containsExactly(tuple("module", true), tuple("other", false));
  }
}
