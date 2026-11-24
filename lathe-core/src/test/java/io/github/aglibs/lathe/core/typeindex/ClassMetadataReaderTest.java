package io.github.aglibs.lathe.core.typeindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
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

  // A record carries no flag of its own; its superclass makes it one, and a plain class stays one.
  @Test
  void kind_recordAndPlainClass_recordOnlyForTheRecord() throws IOException {
    assertThat(ClassMetadataReader.read(classFile(Point.class)).orElseThrow().kind())
        .isEqualTo(TypeKind.RECORD);
    assertThat(ClassMetadataReader.read(classFile(Child.class)).orElseThrow().kind())
        .isEqualTo(TypeKind.CLASS);
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
  void read_argumentPositionLambdaAndMethodRef_capturesFunctionalInterfaceTargets()
      throws IOException {
    final var metadata = ClassMetadataReader.read(classFile(ArgumentLambdas.class));

    // ArgumentLambdas never names Transformer in a declaration — only passes lambdas / method refs
    // to a Transformer-typed parameter — yet the bytecode records the target interface.
    assertThat(metadata).isPresent();
    assertThat(metadata.orElseThrow().lambdaTargets())
        .containsExactly(
            "io.github.aglibs.lathe.core.typeindex.ClassMetadataReaderTest$Transformer");
  }

  @Test
  void read_signatureUseWithoutLambda_hasNoLambdaTargets() throws IOException {
    // Registrar declares a Transformer-typed parameter (so Transformer is in its pool) but converts
    // no lambda to it; the invokedynamic-return-type read must not treat the signature use as a
    // target.
    final var metadata = ClassMetadataReader.read(classFile(Registrar.class));

    assertThat(metadata).isPresent();
    assertThat(metadata.orElseThrow().lambdaTargets()).isEmpty();
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
  void read_withSourceFile_readsTheAttribute() throws IOException {
    final var metadata =
        ClassMetadataReader.read(
            new ByteArrayInputStream(classWithSourceFile("Host.java", true)), true);

    assertThat(metadata.orElseThrow().sourceFile()).isEqualTo("Host.java");
    assertThat(
            ClassMetadataReader.read(
                    new ByteArrayInputStream(classWithSourceFile("Host.java", true)))
                .orElseThrow()
                .sourceFile())
        .isNull();
  }

  // The rest of the class is read by then: a blank name or members cut short lose only the name.
  @Test
  void read_withSourceFileBlankOrCutShort_keepsTheClassWithoutAName() throws IOException {
    final var blank =
        ClassMetadataReader.read(new ByteArrayInputStream(classWithSourceFile("", true)), true);
    final var cutShort =
        ClassMetadataReader.read(
            new ByteArrayInputStream(classWithSourceFile("Host.java", false)), true);

    assertThat(blank.orElseThrow().binaryName()).isEqualTo("Bare");
    assertThat(blank.orElseThrow().sourceFile()).isNull();
    assertThat(cutShort.orElseThrow().binaryName()).isEqualTo("Bare");
    assertThat(cutShort.orElseThrow().sourceFile()).isNull();
  }

  @Test
  void read_invalidThisClassIndex_returnsEmpty() throws IOException {
    final var bytes = classHeader(7, 3);

    assertThat(ClassMetadataReader.read(new ByteArrayInputStream(bytes))).isEmpty();
  }

  @Test
  void metadata_invalidValues_throws() {
    assertThatThrownBy(
            () -> new ClassMetadata(new ClassAccess(0), " ", List.of(), Set.of(), Set.of(), null))
        .hasMessageContaining("binaryName");
    assertThatThrownBy(
            () ->
                new ClassMetadata(
                    new ClassAccess(0), "example.Type", null, Set.of(), Set.of(), null))
        .hasMessageContaining("directSupertypes");
    assertThatThrownBy(
            () ->
                new ClassMetadata(
                    new ClassAccess(0), "example.Type", List.of(), null, Set.of(), null))
        .hasMessageContaining("referencedTypes");
    assertThatThrownBy(
            () ->
                new ClassMetadata(
                    new ClassAccess(0), "example.Type", List.of(), Set.of(), null, null))
        .hasMessageContaining("lambdaTargets");
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

  // A class Bare whose SourceFile attribute names sourceFile; without its members table when not
  // complete, as a file cut short after the interfaces.
  private static byte[] classWithSourceFile(final String sourceFile, final boolean complete)
      throws IOException {
    final var bytes = new ByteArrayOutputStream();
    try (final var data = new DataOutputStream(bytes)) {
      data.writeInt(0xCAFEBABE);
      data.writeShort(0);
      data.writeShort(61);
      data.writeShort(5);
      data.writeByte(1);
      data.writeUTF("Bare");
      data.writeByte(7);
      data.writeShort(1);
      data.writeByte(1);
      data.writeUTF("SourceFile");
      data.writeByte(1);
      data.writeUTF(sourceFile);
      data.writeShort(0x0020);
      data.writeShort(2);
      data.writeShort(0);
      data.writeShort(0);
      if (complete) {
        data.writeShort(0);
        data.writeShort(0);
        data.writeShort(1);
        data.writeShort(3);
        data.writeInt(2);
        data.writeShort(4);
      }
    }
    return bytes.toByteArray();
  }

  private static class Parent {}

  private record Point(int x, int y) {
    private Point {}
  }

  private static final class Child extends Parent implements Runnable {
    @Override
    public void run() {}
  }

  private interface ParentInterface {}

  private interface ChildInterface extends ParentInterface {}

  @FunctionalInterface
  private interface Transformer {
    String apply(String value);
  }

  private static final class Registrar {
    static void register(final Transformer transformer) {}
  }

  @SuppressWarnings("unused")
  private static final class ArgumentLambdas {
    void go() {
      Registrar.register(value -> value.trim());
      Registrar.register(String::toUpperCase);
    }
  }

  @SuppressWarnings("unused")
  private static final class Constants {
    private static final long LONG = LONG_CONSTANT;
    private static final double DOUBLE = DOUBLE_CONSTANT;
  }
}
