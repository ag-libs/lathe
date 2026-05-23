package io.github.aglibs.lathe.maven.typeindex;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

final class ClassAccessReader {

  private static final int CLASS_MAGIC = 0xCAFEBABE;

  private ClassAccessReader() {}

  static Optional<ClassAccess> read(final InputStream in) throws IOException {
    final DataInputStream data = new DataInputStream(in);
    if (data.readInt() != CLASS_MAGIC) {
      return Optional.empty();
    }

    data.readUnsignedShort();
    data.readUnsignedShort();
    final int constantPoolCount = data.readUnsignedShort();
    for (int i = 1; i < constantPoolCount; i++) {
      final int tag = data.readUnsignedByte();
      final int slots = skipConstantPoolEntry(data, tag);
      if (slots == 0) {
        return Optional.empty();
      }

      i += slots - 1;
    }

    return Optional.of(new ClassAccess(data.readUnsignedShort()));
  }

  private static int skipConstantPoolEntry(final DataInputStream data, final int tag)
      throws IOException {
    return switch (tag) {
      // CONSTANT_Utf8: u2 length, then length bytes.
      case 1 -> skip(data, data.readUnsignedShort());
      // CONSTANT_Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType,
      // Dynamic, InvokeDynamic: fixed u4 payload.
      case 3, 4, 9, 10, 11, 12, 17, 18 -> skip(data, 4);
      // CONSTANT_Long and Double: fixed u8 payload and consume two constant-pool slots.
      case 5, 6 -> skip(data, 8) + 1;
      // CONSTANT_Class, String, MethodType, Module, Package: fixed u2 payload.
      case 7, 8, 16, 19, 20 -> skip(data, 2);
      // CONSTANT_MethodHandle: u1 reference_kind plus u2 reference_index.
      case 15 -> skip(data, 3);
      default -> 0;
    };
  }

  private static int skip(final DataInputStream data, final int bytes) throws IOException {
    data.skipNBytes(bytes);
    return 1;
  }
}
