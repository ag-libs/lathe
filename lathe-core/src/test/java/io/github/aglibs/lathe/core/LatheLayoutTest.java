package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheLayoutTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty("lathe.cache");
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
  void syncRemediations_syncedWorkspaceNamesScript_firstSyncNamesBuildCommand() {
    assertThat(LatheLayout.scopedSyncCommand(List.of("app", "core")))
        .isEqualTo(".lathe/lathe-sync.sh app core");
    assertThat(LatheLayout.STALE_REMEDIATION).contains("`.lathe/lathe-sync.sh`");
    assertThat(LatheLayout.captureTestsCommand("app"))
        .isEqualTo(".lathe/lathe-sync.sh --tests app");
    // No script exists before the first sync, so setup names each front-end's first command.
    assertThat(LatheLayout.SETUP_REMEDIATION)
        .contains("`mvn process-test-classes`")
        .contains("`mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync`")
        .doesNotContain("lathe-sync.sh");
  }

  @Test
  void logsDir_underCacheOverride_resolvesToLogsSubdir(@TempDir final Path cache) {
    System.setProperty("lathe.cache", cache.toString());

    assertThat(LatheLayout.logsDir()).isEqualTo(cache.resolve(LatheLayout.LOGS_DIR));
  }

  @Test
  void mcpSessionLog_withSessionId_namesFilePerSessionUnderLogsDir(@TempDir final Path cache) {
    System.setProperty("lathe.cache", cache.toString());

    final Path log = LatheLayout.mcpSessionLog("20260922-141233-48210");

    assertThat(log).isEqualTo(cache.resolve("logs").resolve("mcp-20260922-141233-48210.log"));
    assertThat(log.getFileName().toString())
        .startsWith(LatheLayout.MCP_LOG_PREFIX)
        .endsWith(LatheLayout.LOG_SUFFIX);
  }

  @Test
  void mcpSessionLog_pidOnlyFallbackId_stillNamesUnderLogsDir(@TempDir final Path cache) {
    System.setProperty("lathe.cache", cache.toString());

    assertThat(LatheLayout.mcpSessionLog("48210"))
        .isEqualTo(cache.resolve("logs").resolve("mcp-48210.log"));
  }
}
