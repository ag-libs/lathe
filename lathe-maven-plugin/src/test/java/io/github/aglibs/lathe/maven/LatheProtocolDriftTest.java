package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheCommands;
import io.github.aglibs.lathe.core.LatheFlags;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

// Guards that the client's wire protocol stays in lockstep with the server's: the protocol integer
// (version.lua, LatheFlags.LATHE_PROTOCOL) and the command names (commands.lua, LatheCommands).
// It reads the Lua files from the sibling lathe-neovim module (Lua only, no test runner) by path.
class LatheProtocolDriftTest {

  private static final Path VERSION_LUA = Path.of("../lathe-neovim/runtime/lua/lathe/version.lua");
  private static final Pattern PROTOCOL = Pattern.compile("PROTOCOL\\s*=\\s*(\\d+)");
  private static final Path COMMANDS_LUA =
      Path.of("../lathe-neovim/runtime/lua/lathe/commands.lua");
  private static final Pattern COMMAND = Pattern.compile("(?m)^\\s*([A-Z_]+)\\s*=\\s*\"([^\"]+)\"");

  @Test
  void commandsLua_names_matchLatheCommands() throws Exception {
    final var lua = new TreeMap<String, String>();
    final var matcher = COMMAND.matcher(Files.readString(COMMANDS_LUA));
    while (matcher.find()) {
      lua.put(matcher.group(1), matcher.group(2));
    }

    final var java = new TreeMap<String, String>();
    for (final Field field : LatheCommands.class.getFields()) {
      if (field.getType() == String.class) {
        java.put(field.getName(), (String) field.get(null));
      }
    }

    assertThat(lua).isNotEmpty().isEqualTo(java);
    assertThat(LatheCommands.ALL).containsExactlyInAnyOrderElementsOf(java.values());
  }

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
