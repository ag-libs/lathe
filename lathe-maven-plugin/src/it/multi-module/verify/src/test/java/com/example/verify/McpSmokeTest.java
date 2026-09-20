package com.example.verify;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the MCP launcher. Spawns {@code lathe-mcp-launcher.sh} (the classpath launcher) and
 * drives it over stdio with newline-delimited JSON-RPC, proving the launcher starts, the MCP server
 * initializes, the tools are exposed, and in-process javac — running as unnamed-module code on the
 * classpath — answers {@code get_diagnostics}.
 */
class McpSmokeTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path LAUNCHER =
      Path.of(System.getProperty("lathe.cache"))
          .resolve("servers")
          .resolve(System.getProperty("lathe.version"))
          .resolve("lathe-mcp-launcher.sh");

  private static Process process;
  private static OutputStream toServer;
  private static BufferedReader fromServer;
  private static int nextId = 1;

  @BeforeAll
  static void start() throws Exception {
    final var pb = new ProcessBuilder(LAUNCHER.toString());
    pb.directory(ROOT.toFile());
    process = pb.start();
    drainStderr(process);
    toServer = process.getOutputStream();
    fromServer = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));

    final String init =
        request(
            "initialize",
            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"smoke\",\"version\":\"0\"}}");
    assertThat(init).contains("\"name\":\"lathe\"").contains("protocolVersion");
    notify("notifications/initialized");
  }

  @AfterAll
  static void stop() {
    if (process != null) {
      process.destroyForcibly();
    }
  }

  @Test
  void toolsList_exposesLatheTools() throws Exception {
    final String response = request("tools/list", "{}");

    assertThat(response).contains("get_diagnostics").contains("get_definition");
  }

  @Test
  void getDiagnostics_reactorSource_compilesInProcess() throws Exception {
    final Path greeter = ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");

    final String response =
        request(
            "tools/call",
            "{\"name\":\"get_diagnostics\",\"arguments\":{\"file\":\"%s\"}}"
                .formatted(greeter));

    assertThat(response).contains("Greeter.java").doesNotContain("\"isError\":true");
  }

  // Requests are strictly sequential (send one, read its response before the next), so the next line
  // carrying a result/error is this request's response — robust to id whitespace and skips any log
  // notifications the server may emit.
  private static String request(final String method, final String params) throws IOException {
    send(
        "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"%s\",\"params\":%s}"
            .formatted(nextId++, method, params));
    String line;
    while ((line = fromServer.readLine()) != null) {
      if (line.contains("\"result\"") || line.contains("\"error\"")) {
        return line;
      }
    }

    throw new AssertionError("no response for %s".formatted(method));
  }

  private static void notify(final String method) throws IOException {
    send("{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":{}}".formatted(method));
  }

  private static void send(final String message) throws IOException {
    toServer.write((message + "\n").getBytes(UTF_8));
    toServer.flush();
  }

  private static void drainStderr(final Process process) {
    final var thread =
        new Thread(
            () -> {
              try (var reader =
                  new BufferedReader(new InputStreamReader(process.getErrorStream(), UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  System.out.println("[mcp] %s".formatted(line));
                }
              } catch (final IOException ignored) {
              }
            },
            "mcp-stderr");
    thread.setDaemon(true);
    thread.start();
  }
}
