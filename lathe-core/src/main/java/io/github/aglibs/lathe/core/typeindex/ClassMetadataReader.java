package io.github.aglibs.lathe.core.typeindex;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Optional;

final class ClassMetadataReader {

  private static final int CLASS_MAGIC = 0xCAFEBABE;
  private static final int CONSTANT_UTF8 = 1;
  private static final int CONSTANT_CLASS = 7;

  private ClassMetadataReader() {}

  static Optional<ClassMetadata> read(final InputStream in) throws IOException {
    final var data = new DataInputStream(in);
    if (data.readInt() != CLASS_MAGIC) {
      return Optional.empty();
    }

    data.readUnsignedShort();
    data.readUnsignedShort();
    final int constantPoolCount = data.readUnsignedShort();
    final var utf8Entries = new String[constantPoolCount];
    final var classNameIndexes = new int[constantPoolCount];
    for (int i = 1; i < constantPoolCount; i++) {
      final int tag = data.readUnsignedByte();
      final int slots;
      if (tag == CONSTANT_UTF8) {
        utf8Entries[i] = data.readUTF();
        slots = 1;
      } else if (tag == CONSTANT_CLASS) {
        classNameIndexes[i] = data.readUnsignedShort();
        slots = 1;
      } else {
        slots = skipConstantPoolEntry(data, tag);
      }
      if (slots == 0) {
        return Optional.empty();
      }

      i += slots - 1;
    }

    final var access = new ClassAccess(data.readUnsignedShort());
    final String binaryName =
        resolveClassName(data.readUnsignedShort(), utf8Entries, classNameIndexes);
    if (binaryName == null) {
      return Optional.empty();
    }

    final var directSupertypes = new ArrayList<String>();
    final int superClassIndex = data.readUnsignedShort();
    if (superClassIndex != 0) {
      final String superclass = resolveClassName(superClassIndex, utf8Entries, classNameIndexes);
      if (superclass == null) {
        return Optional.empty();
      }

      directSupertypes.add(superclass);
    }

    final int interfaceCount = data.readUnsignedShort();
    for (int i = 0; i < interfaceCount; i++) {
      final String interfaceName =
          resolveClassName(data.readUnsignedShort(), utf8Entries, classNameIndexes);
      if (interfaceName == null) {
        return Optional.empty();
      }

      directSupertypes.add(interfaceName);
    }

    return Optional.of(new ClassMetadata(access, binaryName, directSupertypes));
  }

  private static String resolveClassName(
      final int classIndex, final String[] utf8Entries, final int[] classNameIndexes) {
    if (classIndex <= 0 || classIndex >= classNameIndexes.length) {
      return null;
    }

    final int nameIndex = classNameIndexes[classIndex];
    if (nameIndex <= 0 || nameIndex >= utf8Entries.length) {
      return null;
    }

    final String internalName = utf8Entries[nameIndex];
    return internalName != null ? internalName.replace('/', '.') : null;
  }

  private static int skipConstantPoolEntry(final DataInputStream data, final int tag)
      throws IOException {
    return switch (tag) {
      // CONSTANT_Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType,
      // Dynamic, InvokeDynamic: fixed u4 payload.
      case 3, 4, 9, 10, 11, 12, 17, 18 -> skip(data, 4);
      // CONSTANT_Long and Double: fixed u8 payload and consume two constant-pool slots.
      case 5, 6 -> skip(data, 8) + 1;
      // CONSTANT_String, MethodType, Module, Package: fixed u2 payload.
      case 8, 16, 19, 20 -> skip(data, 2);
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
