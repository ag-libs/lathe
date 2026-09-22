package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheLayoutTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty("lathe.cache");
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
