package io.github.aglibs.lathe.server.analysis;

import com.google.gson.JsonObject;
import javax.lang.model.element.ElementKind;

public final class CallHierarchyItemDataCodec {

  private CallHierarchyItemDataCodec() {}

  public static JsonObject encode(final CallHierarchyItemData data) {
    final var json = new JsonObject();
    json.addProperty("ownerBinaryName", data.ownerBinaryName());
    json.addProperty("methodName", data.methodName());
    json.addProperty("erasedDescriptor", data.erasedDescriptor());
    json.addProperty("kind", data.kind().name());
    json.addProperty("routingUri", data.routingUri());
    json.addProperty("scope", data.scope().name());
    return json;
  }

  public static CallHierarchyItemData decode(final Object data) {
    if (data instanceof final CallHierarchyItemData itemData) {
      return itemData;
    }

    if (data instanceof final JsonObject json) {
      return new CallHierarchyItemData(
          json.get("ownerBinaryName").getAsString(),
          json.get("methodName").getAsString(),
          json.get("erasedDescriptor").getAsString(),
          ElementKind.valueOf(json.get("kind").getAsString()),
          json.get("routingUri").getAsString(),
          ReferenceTarget.SearchScope.valueOf(json.get("scope").getAsString()));
    }

    return null;
  }
}
