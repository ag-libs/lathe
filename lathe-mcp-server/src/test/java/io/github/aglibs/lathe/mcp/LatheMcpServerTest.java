package io.github.aglibs.lathe.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheMcpServerTest {

  @TempDir private Path logs;

  // A server killed outright leaves its log's lock behind; a live session's lock has its log.
  @Test
  void isOrphanLock_sessionLocks_trueOnlyForALockWhoseLogIsGone() throws IOException {
    final Path live = Files.writeString(logs.resolve("mcp-20261024-090000-1.log.lck"), "");
    Files.writeString(logs.resolve("mcp-20261024-090000-1.log"), "");
    final Path orphan = Files.writeString(logs.resolve("mcp-20261001-090000-2.log.lck"), "");
    final Path foreign = Files.writeString(logs.resolve("other.log.lck"), "");

    assertThat(LatheMcpServer.isOrphanLock(orphan)).isTrue();
    assertThat(LatheMcpServer.isOrphanLock(live)).isFalse();
    assertThat(LatheMcpServer.isOrphanLock(foreign)).isFalse();
    assertThat(LatheMcpServer.isOrphanLock(logs.resolve("mcp-20261024-090000-1.log"))).isFalse();
  }
}
