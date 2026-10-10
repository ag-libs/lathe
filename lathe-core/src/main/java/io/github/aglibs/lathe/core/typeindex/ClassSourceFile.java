package io.github.aglibs.lathe.core.typeindex;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

// Reads a class file's SourceFile attribute: the simple name of the source it was compiled from.
// This is the only link from a package-private sibling top-level type (Helper.class declared in
// Foo.java) back to its source, which the class file name does not carry.
public final class ClassSourceFile {

  private static final String SOURCE_FILE_ATTRIBUTE = "SourceFile";

  private ClassSourceFile() {}

  // Empty when the class was compiled without the attribute (-g:none) or cannot be read.
  public static Optional<String> of(final Path classFile) {
    try (final var data =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
      return read(data);
    } catch (final IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> read(final DataInputStream data) throws IOException {
    if (data.readInt() != ClassMetadataReader.CLASS_MAGIC) {
      return Optional.empty();
    }

    data.skipNBytes(4);
    final int constantPoolCount = data.readUnsignedShort();
    final var utf8Entries = new String[constantPoolCount];
    for (int i = 1; i < constantPoolCount; i++) {
      final int tag = data.readUnsignedByte();
      if (tag == ClassMetadataReader.CONSTANT_UTF8) {
        utf8Entries[i] = data.readUTF();
        continue;
      }

      final int slots = ClassMetadataReader.skipConstantPoolEntry(data, tag);
      if (slots == 0) {
        return Optional.empty();
      }

      i += slots - 1;
    }

    // access_flags, this_class, super_class
    data.skipNBytes(6);
    data.skipNBytes(2L * data.readUnsignedShort());
    skipMembers(data);
    skipMembers(data);
    final int attributeCount = data.readUnsignedShort();
    for (int i = 0; i < attributeCount; i++) {
      final String name = utf8At(utf8Entries, data.readUnsignedShort());
      final int length = data.readInt();
      if (SOURCE_FILE_ATTRIBUTE.equals(name)) {
        return Optional.ofNullable(utf8At(utf8Entries, data.readUnsignedShort()));
      }

      data.skipNBytes(length);
    }

    return Optional.empty();
  }

  // A fields or methods table: each member is access/name/descriptor followed by its attributes.
  private static void skipMembers(final DataInputStream data) throws IOException {
    final int memberCount = data.readUnsignedShort();
    for (int i = 0; i < memberCount; i++) {
      data.skipNBytes(6);
      final int attributeCount = data.readUnsignedShort();
      for (int j = 0; j < attributeCount; j++) {
        data.skipNBytes(2);
        data.skipNBytes(Integer.toUnsignedLong(data.readInt()));
      }
    }
  }

  private static String utf8At(final String[] utf8Entries, final int index) {
    return index > 0 && index < utf8Entries.length ? utf8Entries[index] : null;
  }
}
