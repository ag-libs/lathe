package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceModuleGraphTest {

  private static final Path WORKSPACE = Path.of("/workspace");
  private static final Path LATHE_DIR = WORKSPACE.resolve(".lathe");

  private static ModuleSourceConfig config(
      final String moduleRel, final String sourceTree, final List<Path> classpath) {
    return new ModuleSourceConfig(
        LATHE_DIR.resolve(moduleRel),
        sourceTree,
        WORKSPACE.resolve(moduleRel).resolve("target").resolve(sourceTree),
        null,
        List.of(WORKSPACE.resolve(moduleRel).resolve("src/main/java")),
        classpath,
        List.of(),
        List.of(),
        "21",
        "UTF-8",
        false,
        false,
        null,
        List.of());
  }

  private static Path reactorTarget(final String moduleRel) {
    return WORKSPACE.resolve(moduleRel).resolve("target/classes");
  }

  @Test
  void build_empty_producesEmptyGraph() {
    final var graph = WorkspaceModuleGraph.build(List.of());
    assertThat(graph.configsForModule(LATHE_DIR.resolve("core"))).isEmpty();
  }

  @Test
  void configsForModule_singleConfig_returnsThatConfig() {
    final var coreMain = config("core", "classes", List.of());
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain));
    assertThat(graph.configsForModule(LATHE_DIR.resolve("core"))).containsExactly(coreMain);
  }

  @Test
  void configsForModule_multipleSourceTrees_returnsAll() {
    final var coreMain = config("core", "classes", List.of());
    final var coreTest = config("core", "test-classes", List.of(reactorTarget("core")));
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain, coreTest));
    assertThat(graph.configsForModule(LATHE_DIR.resolve("core")))
        .containsExactlyInAnyOrder(coreMain, coreTest);
  }

  @Test
  void referenceSearchScope_noDownstream_returnsDeclaringOnly() {
    final var coreMain = config("core", "classes", List.of());
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain));
    assertThat(graph.referenceSearchScope(coreMain)).containsExactly(coreMain);
  }

  @Test
  void referenceSearchScope_directDownstream_includesDownstream() {
    final var coreMain = config("core", "classes", List.of());
    final var appMain = config("app", "classes", List.of(reactorTarget("core")));
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain, appMain));
    assertThat(graph.referenceSearchScope(coreMain)).containsExactlyInAnyOrder(coreMain, appMain);
  }

  @Test
  void referenceSearchScope_transitiveDownstream_includesTransitive() {
    final var apiMain = config("api", "classes", List.of());
    final var serviceMain = config("service", "classes", List.of(reactorTarget("api")));
    final var appMain = config("app", "classes", List.of(reactorTarget("service")));
    final var graph = WorkspaceModuleGraph.build(List.of(apiMain, serviceMain, appMain));
    assertThat(graph.referenceSearchScope(apiMain))
        .containsExactlyInAnyOrder(apiMain, serviceMain, appMain);
  }

  @Test
  void referenceSearchScope_upstream_notIncluded() {
    final var coreMain = config("core", "classes", List.of());
    final var appMain = config("app", "classes", List.of(reactorTarget("core")));
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain, appMain));
    assertThat(graph.referenceSearchScope(appMain)).containsExactly(appMain);
  }

  @Test
  void referenceSearchScope_unrelatedModule_notIncluded() {
    final var coreMain = config("core", "classes", List.of());
    final var otherMain = config("other", "classes", List.of());
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain, otherMain));
    assertThat(graph.referenceSearchScope(coreMain)).containsExactly(coreMain);
  }

  @Test
  void referenceSearchScope_allSourceTreesOfDownstreamIncluded() {
    final var coreMain = config("core", "classes", List.of());
    final var appMain = config("app", "classes", List.of(reactorTarget("core")));
    final var appTest =
        config("app", "test-classes", List.of(reactorTarget("app"), reactorTarget("core")));
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain, appMain, appTest));
    assertThat(graph.referenceSearchScope(coreMain))
        .containsExactlyInAnyOrder(coreMain, appMain, appTest);
  }

  @Test
  void referenceSearchScope_selfReferenceOnClasspath_notDuplicated() {
    final var appMain = config("app", "classes", List.of(reactorTarget("app")));
    final var graph = WorkspaceModuleGraph.build(List.of(appMain));
    assertThat(graph.referenceSearchScope(appMain)).containsExactly(appMain);
  }

  @Test
  void downstreamModuleDirs_leafModule_returnsSelfOnly() {
    final var coreMain = config("core", "classes", List.of());
    final var otherMain = config("other", "classes", List.of());
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain, otherMain));
    assertThat(graph.downstreamModuleDirs(LATHE_DIR.resolve("other")))
        .containsExactly(LATHE_DIR.resolve("other"));
  }

  @Test
  void downstreamModuleDirs_transitiveDependents_includesSelfAndAllDownstream() {
    final var apiMain = config("api", "classes", List.of());
    final var serviceMain = config("service", "classes", List.of(reactorTarget("api")));
    final var appMain = config("app", "classes", List.of(reactorTarget("service")));
    final var graph = WorkspaceModuleGraph.build(List.of(apiMain, serviceMain, appMain));

    assertThat(graph.downstreamModuleDirs(LATHE_DIR.resolve("api")))
        .containsExactlyInAnyOrder(
            LATHE_DIR.resolve("api"), LATHE_DIR.resolve("service"), LATHE_DIR.resolve("app"));
    // A downstream module carries only itself; its upstream is unaffected by its edits.
    assertThat(graph.downstreamModuleDirs(LATHE_DIR.resolve("app")))
        .containsExactly(LATHE_DIR.resolve("app"));
  }

  @Test
  void downstreamModuleDirs_unknownModule_returnsSelfOnly() {
    final var coreMain = config("core", "classes", List.of());
    final var graph = WorkspaceModuleGraph.build(List.of(coreMain));
    assertThat(graph.downstreamModuleDirs(LATHE_DIR.resolve("ghost")))
        .containsExactly(LATHE_DIR.resolve("ghost"));
  }

  @Test
  void upstreamFirst_chain_ordersDependencyBeforeDependent() {
    final var apiMain = config("api", "classes", List.of());
    final var serviceMain = config("service", "classes", List.of(reactorTarget("api")));
    final var appMain = config("app", "classes", List.of(reactorTarget("service")));
    final var graph = WorkspaceModuleGraph.build(List.of(apiMain, serviceMain, appMain));

    assertThat(
            graph.upstreamFirst(
                Set.of(
                    LATHE_DIR.resolve("app"),
                    LATHE_DIR.resolve("api"),
                    LATHE_DIR.resolve("service"))))
        .containsExactly(
            LATHE_DIR.resolve("api"), LATHE_DIR.resolve("service"), LATHE_DIR.resolve("app"));
  }

  @Test
  void upstreamFirst_diamond_dependencyPrecedesBothDependents() {
    final var apiMain = config("api", "classes", List.of());
    final var leftMain = config("left", "classes", List.of(reactorTarget("api")));
    final var rightMain = config("right", "classes", List.of(reactorTarget("api")));
    final var appMain =
        config("app", "classes", List.of(reactorTarget("left"), reactorTarget("right")));
    final var graph = WorkspaceModuleGraph.build(List.of(apiMain, leftMain, rightMain, appMain));

    final List<Path> order =
        graph.upstreamFirst(
            Set.of(
                LATHE_DIR.resolve("app"),
                LATHE_DIR.resolve("left"),
                LATHE_DIR.resolve("right"),
                LATHE_DIR.resolve("api")));

    assertThat(order).startsWith(LATHE_DIR.resolve("api")).endsWith(LATHE_DIR.resolve("app"));
  }
}
