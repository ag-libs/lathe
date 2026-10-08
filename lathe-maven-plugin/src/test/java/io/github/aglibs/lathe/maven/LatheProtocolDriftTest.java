package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

// Guards that the client's wire-protocol integer stays in lockstep with the server's: the client
// carries it in version.lua, the server in LatheFlags.LATHE_PROTOCOL. The guard reads version.lua
// from the sibling lathe-neovim module (Lua-only, no test runner) by path.
class LatheProtocolDriftTest {

  private static final Path VERSION_LUA = Path.of("../lathe-neovim/runtime/lua/lathe/version.lua");
  private static final Pattern PROTOCOL = Pattern.compile("PROTOCOL\\s*=\\s*(\\d+)");

  @Test
  void versionLua_protocol_matchesLatheFlags() throws IOException {
    assertThat(luaProtocol()).isEqualTo(LatheFlags.LATHE_PROTOCOL);
  }

  @Test
  void versionLua_protocolField_isPresentAndParseable() throws IOException {
    final String content = Files.readString(VERSION_LUA);

    assertThat(PROTOCOL.matcher(content).find())
        .withFailMessage(
            "PROTOCOL = <int> not found in %s — did version.lua's schema change?", VERSION_LUA)
        .isTrue();
  }

  private static int luaProtocol() throws IOException {
    final String content = Files.readString(VERSION_LUA);
    final var matcher = PROTOCOL.matcher(content);
    assertThat(matcher.find())
        .withFailMessage("PROTOCOL = <int> not found in %s", VERSION_LUA)
        .isTrue();
    return Integer.parseInt(matcher.group(1));
  }
}
