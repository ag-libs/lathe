package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.Test;

class CallHierarchyItemDataCodecTest {

  private static final CallHierarchyItemData SAMPLE =
      new CallHierarchyItemData(
          "com.example.Foo",
          "bar",
          "(java.lang.String,int)",
          ElementKind.METHOD,
          "file:///com/example/Foo.java",
          ReferenceTarget.SearchScope.REACTOR_MODULES);

  @Test
  void encode_decode_roundTrip_preservesAllFields() {
    final JsonObject encoded = CallHierarchyItemDataCodec.encode(SAMPLE);
    final CallHierarchyItemData decoded = CallHierarchyItemDataCodec.decode(encoded);

    assertThat(decoded).isEqualTo(SAMPLE);
  }

  @Test
  void decode_fromJsonObject_reconstructsRecord() {
    final var json = new JsonObject();
    json.addProperty("ownerBinaryName", "com.example.Foo");
    json.addProperty("methodName", "bar");
    json.addProperty("erasedDescriptor", "(java.lang.String,int)");
    json.addProperty("kind", "METHOD");
    json.addProperty("routingUri", "file:///com/example/Foo.java");
    json.addProperty("scope", "REACTOR_MODULES");

    final CallHierarchyItemData decoded = CallHierarchyItemDataCodec.decode(json);

    assertThat(decoded).isNotNull();
    assertThat(decoded.ownerBinaryName()).isEqualTo("com.example.Foo");
    assertThat(decoded.methodName()).isEqualTo("bar");
    assertThat(decoded.erasedDescriptor()).isEqualTo("(java.lang.String,int)");
    assertThat(decoded.kind()).isEqualTo(ElementKind.METHOD);
    assertThat(decoded.routingUri()).isEqualTo("file:///com/example/Foo.java");
    assertThat(decoded.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }
}
