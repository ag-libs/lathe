package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class TypeHierarchyItemDataCodecTest {

  private static final TypeHierarchyItemData SAMPLE =
      new TypeHierarchyItemData("com.example.Foo", "file:///com/example/Foo.java");

  @Test
  void encode_decode_roundTrip_preservesAllFields() {
    final JsonObject encoded = TypeHierarchyItemDataCodec.encode(SAMPLE);
    final TypeHierarchyItemData decoded = TypeHierarchyItemDataCodec.decode(encoded);

    assertThat(decoded).isEqualTo(SAMPLE);
  }

  @Test
  void decode_fromJsonObject_reconstructsRecord() {
    final var json = new JsonObject();
    json.addProperty("binaryName", "com.example.Foo");
    json.addProperty("routingUri", "file:///com/example/Foo.java");

    final TypeHierarchyItemData decoded = TypeHierarchyItemDataCodec.decode(json);

    assertThat(decoded).isNotNull();
    assertThat(decoded.binaryName()).isEqualTo("com.example.Foo");
    assertThat(decoded.routingUri()).isEqualTo("file:///com/example/Foo.java");
  }

  @Test
  void decode_missingBinaryName_throwsIllegalArgument() {
    final var json = new JsonObject();
    json.addProperty("routingUri", "file:///com/example/Foo.java");

    assertThatThrownBy(() -> TypeHierarchyItemDataCodec.decode(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binaryName");
  }

  @Test
  void decode_missingRoutingUri_throwsIllegalArgument() {
    final var json = new JsonObject();
    json.addProperty("binaryName", "com.example.Foo");

    assertThatThrownBy(() -> TypeHierarchyItemDataCodec.decode(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routingUri");
  }

  @Test
  void decode_nullInput_returnsNull() {
    assertThat(TypeHierarchyItemDataCodec.decode(null)).isNull();
  }
}
