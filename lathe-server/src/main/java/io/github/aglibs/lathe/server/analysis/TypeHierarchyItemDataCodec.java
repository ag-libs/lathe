package io.github.aglibs.lathe.server.analysis;

import com.google.gson.JsonObject;

public final class TypeHierarchyItemDataCodec {

  private TypeHierarchyItemDataCodec() {}

  public static JsonObject encode(final TypeHierarchyItemData data) {
    final var json = new JsonObject();
    json.addProperty("binaryName", data.binaryName());
    json.addProperty("routingUri", data.routingUri());
    return json;
  }

  public static TypeHierarchyItemData decode(final Object data) {
    if (data instanceof final TypeHierarchyItemData itemData) {
      return itemData;
    }

    if (data instanceof final JsonObject json) {
      if (json.get("binaryName") == null) {
        throw new IllegalArgumentException("missing field: binaryName");
      }

      if (json.get("routingUri") == null) {
        throw new IllegalArgumentException("missing field: routingUri");
      }

      return new TypeHierarchyItemData(
          json.get("binaryName").getAsString(), json.get("routingUri").getAsString());
    }

    return null;
  }
}
