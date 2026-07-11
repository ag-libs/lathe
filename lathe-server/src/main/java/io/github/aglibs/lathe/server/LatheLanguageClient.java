package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.run.TestOutputParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Lathe's extension of the standard LSP client, adding the live test-output stream. The server
 * builds the client proxy against this interface (see {@code LatheServer}), so the proxy handed to
 * {@code connect} always implements it.
 */
public interface LatheLanguageClient extends LanguageClient {

  @JsonNotification("lathe/testOutput")
  void testOutput(TestOutputParams params);
}
