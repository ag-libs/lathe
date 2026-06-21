package io.github.aglibs.lathe.core.typeindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassMetadataReaderTest {

  private static final long LONG_CONSTANT = 9_223_372_036_854_775_000L;
  private static final double DOUBLE_CONSTANT = 123.456;

  @Test
  void read_classWithSuperclassAndInterfaces_returnsMetadata() throws IOException {
    final var metadata = ClassMetadataReader.read(classFile(Child.class));

    assertThat(metadata).isPresent();
    assertThat(metadata.orElseThrow().binaryName())
        .isEqualTo("io.github.aglibs.lathe.core.typeindex.ClassMetadataReaderTest$Child");
    assertThat(metadata.orElseThrow().directSupertypes())
        .containsExactly(
            "io.github.aglibs.lathe.core.typeindex.ClassMetadataReaderTest$Parent",
            "java.lang.Runnable");
    assertThat(metadata.orElseThrow().access().kind()).isEqualTo(TypeKind.CLASS);
  }

  @Test
  void read_interfaceExtension_returnsMetadata() throws IOException {
    final var metadata = ClassMetadataReader.read(classFile(ChildInterface.class));

    assertThat(metadata).isPresent();
    assertThat(metadata.orElseThrow().directSupertypes())
        .containsExactly(
            "java.lang.Object",
            "io.github.aglibs.lathe.core.typeindex.ClassMetadataReaderTest$ParentInterface");
    assertThat(metadata.orElseThrow().access().kind()).isEqualTo(TypeKind.INTERFACE);
  }

  @Test
  void read_javaLangObject_returnsNoSupertypes() throws IOException {
    final var metadata = ClassMetadataReader.read(classFile(Object.class));

    assertThat(metadata).isPresent();
    assertThat(metadata.orElseThrow().binaryName()).isEqualTo("java.lang.Object");
    assertThat(metadata.orElseThrow().directSupertypes()).isEmpty();
  }

  @Test
  void read_longAndDoubleConstantPoolEntries_returnsMetadata() throws IOException {
    final var metadata = ClassMetadataReader.read(classFile(Constants.class));

    assertThat(metadata).isPresent();
    assertThat(metadata.orElseThrow().binaryName())
        .isEqualTo("io.github.aglibs.lathe.core.typeindex.ClassMetadataReaderTest$Constants");
  }

  @Test
  void read_invalidMagic_returnsEmpty() throws IOException {
    final var bytes = new byte[] {0, 0, 0, 0};

    assertThat(ClassMetadataReader.read(new ByteArrayInputStream(bytes))).isEmpty();
  }

  @Test
  void read_unsupportedConstantPoolTag_returnsEmpty() throws IOException {
    final var bytes = classHeader(99, 0);

    assertThat(ClassMetadataReader.read(new ByteArrayInputStream(bytes))).isEmpty();
  }

  @Test
  void read_invalidThisClassIndex_returnsEmpty() throws IOException {
    final var bytes = classHeader(7, 3);

    assertThat(ClassMetadataReader.read(new ByteArrayInputStream(bytes))).isEmpty();
  }

  @Test
  void metadata_invalidValues_throws() {
    assertThatThrownBy(() -> new ClassMetadata(new ClassAccess(0), " ", List.of()))
        .hasMessageContaining("binaryName");
    assertThatThrownBy(() -> new ClassMetadata(new ClassAccess(0), "example.Type", null))
        .hasMessageContaining("directSupertypes");
  }

  private static InputStream classFile(final Class<?> type) {
    final var resource = "/%s.class".formatted(type.getName().replace('.', '/'));
    return type.getResourceAsStream(resource);
  }

  private static byte[] classHeader(final int constantPoolTag, final int thisClassIndex)
      throws IOException {
    final var bytes = new ByteArrayOutputStream();
    try (final var data = new DataOutputStream(bytes)) {
      data.writeInt(0xCAFEBABE);
      data.writeShort(0);
      data.writeShort(61);
      data.writeShort(2);
      data.writeByte(constantPoolTag);
      if (constantPoolTag == 7) {
        data.writeShort(1);
      }
      data.writeShort(0x0001);
      data.writeShort(thisClassIndex);
      data.writeShort(0);
      data.writeShort(0);
    }
    return bytes.toByteArray();
  }

  private static class Parent {}

  private static final class Child extends Parent implements Runnable {
    @Override
    public void run() {}
  }

  private interface ParentInterface {}

  private interface ChildInterface extends ParentInterface {}

  @SuppressWarnings("unused")
  private static final class Constants {
    private static final long LONG = LONG_CONSTANT;
    private static final double DOUBLE = DOUBLE_CONSTANT;
  }
}
