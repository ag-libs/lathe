package io.github.aglibs.lathe.server;

import com.google.gson.JsonObject;
import io.github.aglibs.lathe.server.analysis.DiagnosticPayload;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;

final class DiagnosticPayloadCodec {

  private DiagnosticPayloadCodec() {}

  static void serializeDiagnosticData(final List<Diagnostic> diags) {
    for (final Diagnostic d : diags) {
      if (d.getData() instanceof DiagnosticPayload p) {
        d.setData(toJson(p));
      }
    }
  }

  static JsonObject toJson(final DiagnosticPayload payload) {
    final var jo = new JsonObject();
    jo.addProperty("kind", payload.kind().name());
    jo.addProperty("name", payload.name());
    return jo;
  }

  static DiagnosticPayload extractPayload(final Object data) {
    if (data instanceof DiagnosticPayload dp) {
      return dp;
    }

    if (data instanceof JsonObject jo) {
      return new DiagnosticPayload(
          DiagnosticPayload.Kind.valueOf(jo.get("kind").getAsString()),
          jo.get("name").getAsString());
    }

    return null;
  }
}
