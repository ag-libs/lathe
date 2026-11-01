package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.jupiter.api.Test;

class LatheServerTest {

  @Test
  void run_endOfInput_returnsWithinBound() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> LatheServer.run(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
  }

  @Test
  void run_unsupportedRequest_answersMethodNotFound() throws Exception {
    final String request =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"textDocument/typeDefinition\",\"params\":"
            + "{\"textDocument\":{\"uri\":\"file:///A.java\"},\"position\":{\"line\":0,\"character\":0}}}";
    final byte[] body = request.getBytes(StandardCharsets.UTF_8);
    final var in =
        new ByteArrayInputStream(
            "Content-Length: %d\r\n\r\n%s"
                .formatted(body.length, request)
                .getBytes(StandardCharsets.UTF_8));
    final var out = new ByteArrayOutputStream();

    LatheServer.run(in, out);

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("\"code\":%d".formatted(ResponseErrorCode.MethodNotFound.getValue()));
    // Thrown by Lathe's own code, it is still an internal error.
    assertThat(LatheServer.responseError(new UnsupportedOperationException()).getCode())
        .isEqualTo(ResponseErrorCode.InternalError.getValue());
  }
}
