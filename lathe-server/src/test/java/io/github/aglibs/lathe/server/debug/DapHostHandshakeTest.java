package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the Microsoft java-debug {@link com.microsoft.java.debug.core.adapter.ProtocolServer} runs
 * in-process under JPMS and answers a raw DAP {@code initialize} — the Phase 0 GO/NO-GO. No
 * debuggee is launched or attached.
 */
final class DapHostHandshakeTest {

  private static final String CONTENT_LENGTH = "Content-Length: ";

  private static final String INITIALIZE_REQUEST =
      """
      {"seq": 1, "type": "request", "command": "initialize",
       "arguments": {"adapterID": "lathe", "clientID": "lathe-test"}}
      """;

  @Test
  @Timeout(10)
  void initialize_overDapSocket_returnsCapabilities() throws IOException {
    final DapHost host = DapHost.start(new LatheProviderContext(), () -> {});
    try (final Socket client = new Socket(InetAddress.getLoopbackAddress(), host.port())) {
      writeMessage(client.getOutputStream());

      final String response = readMessage(client.getInputStream());

      assertThat(response)
          .contains("\"type\":\"response\"")
          .contains("\"command\":\"initialize\"")
          .contains("\"success\":true");
    } finally {
      host.close();
    }
  }

  private static void writeMessage(final OutputStream out) throws IOException {
    final byte[] body = DapHostHandshakeTest.INITIALIZE_REQUEST.getBytes(StandardCharsets.UTF_8);
    out.write(
        "%s%d\r\n\r\n".formatted(CONTENT_LENGTH, body.length).getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }

  private static String readMessage(final InputStream in) throws IOException {
    int length = -1;
    String line;
    while (!(line = readLine(in)).isEmpty()) {
      if (line.startsWith(CONTENT_LENGTH)) {
        length = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
      }
    }

    return new String(in.readNBytes(length), StandardCharsets.UTF_8);
  }

  private static String readLine(final InputStream in) throws IOException {
    final var line = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (c != '\r') {
        line.append((char) c);
      }
    }

    return line.toString();
  }
}
