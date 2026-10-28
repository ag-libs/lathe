package com.example.itsupport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

// The real MCP server, started from the fixture's MCP launcher as an agent's client starts it, driven
// over stdio with newline-delimited JSON-RPC.
public final class McpSession {

  public final String initResponse;
  private final Process process;
  private final OutputStream toServer;
  private final BufferedReader fromServer;
  private int nextId = 1;

  private McpSession(final Process process) throws IOException {
    this.process = process;
    this.toServer = process.getOutputStream();
    this.fromServer = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
    this.initResponse =
        request(
            "initialize",
            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"fixture\",\"version\":\"0\"}}");
    send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
  }

  // Started from the workspace root, where an agent's client starts it.
  public static McpSession start() throws IOException {
    final var pb = new ProcessBuilder(Os.launcher("lathe-mcp-launcher").toString());
    pb.directory(LspSession.ROOT.toFile());
    final Process process = pb.start();
    drainStderr(process);
    return new McpSession(process);
  }

  public void stop() {
    Os.stop(process);
  }

  // Requests are strictly sequential (send one, read its response before the next), so the next
  // line carrying a result or an error is this request's response, whatever log notifications the
  // server emits in between.
  public String request(final String method, final String params) throws IOException {
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

  public String callTool(final String tool, final String arguments) throws IOException {
    return request("tools/call", "{\"name\":\"%s\",\"arguments\":%s}".formatted(tool, arguments));
  }

  // The 1-based line and column of token's first occurrence in file, as the tools take them.
  public static int[] tokenPosition(final Path file, final String token) throws IOException {
    final List<String> lines = Files.readAllLines(file);
    return IntStream.range(0, lines.size())
        .filter(i -> lines.get(i).contains(token))
        .mapToObj(i -> new int[] {i + 1, lines.get(i).indexOf(token) + 1})
        .findFirst()
        .orElseThrow(() -> new AssertionError("token not found: %s".formatted(token)));
  }

  private void send(final String message) throws IOException {
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
