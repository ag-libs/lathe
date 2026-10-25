package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheLayoutTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.CACHE_ROOT);
  }

  // The guard for LatheLayout.Maven: build-neutral modules never name a Maven-only constant. A
  // front-end module is scanned too, so a wrong path cannot make the guard pass vacuously.
  @Test
  void mavenNames_buildNeutralModulesNever_frontEndModuleDoes() {
    assertThat(List.of("lathe-server", "lathe-mcp-server", "lathe-test-runner", "lathe-junit"))
        .allSatisfy(module -> assertThat(filesNamingMaven(module)).as(module).isEmpty());
    assertThat(filesNamingMaven("lathe-maven-plugin")).isNotEmpty();
  }

  private static List<Path> filesNamingMaven(final String module) {
    final Path sources = Path.of("..", module, "src", "main", "java");
    return IOUtil.unchecked(() -> FileUtil.walkFiles(sources, FileUtil::isJavaFile)).stream()
        .filter(
            file -> IOUtil.unchecked(() -> Files.readString(file)).contains("LatheLayout.Maven"))
        .toList();
  }

  @Test
  void paramsSourceTree_paramsFileName_givesTheTreeBack() {
    assertThat(LatheLayout.paramsSourceTree(Path.of(LatheLayout.paramsFileName("test-classes"))))
        .isEqualTo("test-classes");
    assertThat(LatheLayout.isParamsFile(Path.of(LatheLayout.compiledStampsFileName("classes"))))
        .isFalse();
  }

  @Test
  void syncRemediations_syncedWorkspaceNamesScript_firstSyncNamesBuildCommand() {
    assertThat(LatheLayout.scopedSyncCommand(List.of("app", "core")))
        .isEqualTo("%s app core".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(LatheLayout.SCHEMA_REMEDIATION)
        .contains("`%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(LatheLayout.captureTestsCommand("app"))
        .isEqualTo("%s --tests app".formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
    // No script exists before the first sync, so setup names each front-end's first command.
    assertThat(LatheLayout.SETUP_REMEDIATION)
        .contains("`mvn process-test-classes`")
        .contains("`mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync`")
        .doesNotContain("lathe-sync.sh");
  }

  @Test
  void artifactDir_coordinates_nestsLikeLocalRepositoryWithoutColons(@TempDir final Path root) {
    final Path dir = LatheLayout.artifactDir(root, "org.example", "lib-core", "1.0-SNAPSHOT");

    assertThat(dir)
        .isEqualTo(root.resolve("org.example").resolve("lib-core").resolve("1.0-SNAPSHOT"));
    assertThat(root.relativize(dir).toString()).doesNotContain(":");
  }

  // Each OS gets only its own dialect; cmd reads a '/' path as a switch.
  @Test
  void scriptNamesAndTools_followTheOs() {
    assertThat(LatheLayout.scriptName("lathe-launcher", false)).isEqualTo("lathe-launcher.sh");
    assertThat(LatheLayout.scriptName("lathe-launcher", true)).isEqualTo("lathe-launcher.cmd");
    assertThat(LatheLayout.syncScriptCommand(false)).isEqualTo(".lathe/lathe-sync.sh");
    assertThat(LatheLayout.syncScriptCommand(true)).isEqualTo(".lathe\\lathe-sync.cmd");

    final var maven = new Properties();
    maven.setProperty("maven.home", "/opt/maven");
    assertThat(LatheLayout.Maven.runningTool(maven, true))
        .isEqualTo(Path.of("/opt/maven/bin/mvn.cmd"));
    maven.setProperty("mvnd.home", "/opt/mvnd");
    assertThat(LatheLayout.Maven.runningTool(maven, true))
        .isEqualTo(Path.of("/opt/mvnd/bin/mvnd.cmd"));
    assertThat(LatheLayout.Maven.runningTool(maven, false))
        .isEqualTo(Path.of("/opt/mvnd/bin/mvnd"));
  }

  @Test
  void logsDir_underCacheOverride_resolvesToLogsSubdir(@TempDir final Path cache) {
    System.setProperty(LatheFlags.CACHE_ROOT, cache.toString());

    assertThat(LatheLayout.logsDir()).isEqualTo(cache.resolve(LatheLayout.LOGS_DIR));
  }

  @Test
  void mcpSessionLog_withSessionId_namesFilePerSessionUnderLogsDir(@TempDir final Path cache) {
    System.setProperty(LatheFlags.CACHE_ROOT, cache.toString());

    final Path log = LatheLayout.mcpSessionLog("20260922-141233-48210");

    assertThat(log).isEqualTo(cache.resolve("logs").resolve("mcp-20260922-141233-48210.log"));
    assertThat(log.getFileName().toString())
        .startsWith(LatheLayout.MCP_LOG_PREFIX)
        .endsWith(LatheLayout.LOG_SUFFIX);
  }

  @Test
  void mcpSessionLog_pidOnlyFallbackId_stillNamesUnderLogsDir(@TempDir final Path cache) {
    System.setProperty(LatheFlags.CACHE_ROOT, cache.toString());

    assertThat(LatheLayout.mcpSessionLog("48210"))
        .isEqualTo(cache.resolve("logs").resolve("mcp-48210.log"));
  }
}
