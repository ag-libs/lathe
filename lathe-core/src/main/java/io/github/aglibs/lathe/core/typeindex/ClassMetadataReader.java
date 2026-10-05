package io.github.aglibs.lathe.core.typeindex;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class ClassMetadataReader {

  private static final int CLASS_MAGIC = 0xCAFEBABE;
  private static final int CONSTANT_UTF8 = 1;
  private static final int CONSTANT_CLASS = 7;
  private static final int CONSTANT_NAME_AND_TYPE = 12;
  private static final int CONSTANT_INVOKE_DYNAMIC = 18;

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
    final var nameAndTypeDescriptors = new int[constantPoolCount];
    final var invokeDynamicNameAndTypes = new ArrayList<Integer>();
    for (int i = 1; i < constantPoolCount; i++) {
      final int tag = data.readUnsignedByte();
      final int slots;
      if (tag == CONSTANT_UTF8) {
        utf8Entries[i] = data.readUTF();
        slots = 1;
      } else if (tag == CONSTANT_CLASS) {
        classNameIndexes[i] = data.readUnsignedShort();
        slots = 1;
      } else if (tag == CONSTANT_NAME_AND_TYPE) {
        data.readUnsignedShort(); // name_index
        nameAndTypeDescriptors[i] = data.readUnsignedShort(); // descriptor_index
        slots = 1;
      } else if (tag == CONSTANT_INVOKE_DYNAMIC) {
        data.readUnsignedShort(); // bootstrap_method_attr_index
        invokeDynamicNameAndTypes.add(data.readUnsignedShort()); // name_and_type_index
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

    final Set<String> referencedTypes =
        referencedTypes(binaryName, constantPoolCount, utf8Entries, classNameIndexes);
    final Set<String> lambdaTargets =
        lambdaTargets(invokeDynamicNameAndTypes, nameAndTypeDescriptors, utf8Entries);
    return Optional.of(
        new ClassMetadata(access, binaryName, directSupertypes, referencedTypes, lambdaTargets));
  }

  // The functional interface each invokedynamic converts to: the return type of its descriptor.
  // Non-lambda invokedynamic (e.g. string concatenation → String) is harmless — no query ever asks
  // for a non-functional-interface target.
  private static Set<String> lambdaTargets(
      final List<Integer> invokeDynamicNameAndTypes,
      final int[] nameAndTypeDescriptors,
      final String[] utf8Entries) {
    return invokeDynamicNameAndTypes.stream()
        .map(nameAndType -> descriptorReturnType(nameAndType, nameAndTypeDescriptors, utf8Entries))
        .flatMap(Optional::stream)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Optional<String> descriptorReturnType(
      final int nameAndTypeIndex, final int[] nameAndTypeDescriptors, final String[] utf8Entries) {
    if (nameAndTypeIndex <= 0 || nameAndTypeIndex >= nameAndTypeDescriptors.length) {
      return Optional.empty();
    }

    final int descriptorIndex = nameAndTypeDescriptors[nameAndTypeIndex];
    if (descriptorIndex <= 0 || descriptorIndex >= utf8Entries.length) {
      return Optional.empty();
    }

    return classReturnType(utf8Entries[descriptorIndex]);
  }

  // "(args)Lpkg/Iface;" → "pkg.Iface"; empty when the return type is not a single class/interface.
  private static Optional<String> classReturnType(final String descriptor) {
    if (descriptor == null) {
      return Optional.empty();
    }

    final int paramsEnd = descriptor.lastIndexOf(')');
    final String returnType = paramsEnd < 0 ? "" : descriptor.substring(paramsEnd + 1);
    if (returnType.length() < 3
        || returnType.charAt(0) != 'L'
        || returnType.charAt(returnType.length() - 1) != ';') {
      return Optional.empty();
    }

    return Optional.of(returnType.substring(1, returnType.length() - 1).replace('/', '.'));
  }

  // Excludes self and array descriptors (whose element type is referenced directly elsewhere).
  private static Set<String> referencedTypes(
      final String binaryName,
      final int constantPoolCount,
      final String[] utf8Entries,
      final int[] classNameIndexes) {
    final var referenced = new LinkedHashSet<String>();
    for (int i = 1; i < constantPoolCount; i++) {
      if (classNameIndexes[i] == 0) {
        continue;
      }

      final String name = resolveClassName(i, utf8Entries, classNameIndexes);
      if (name != null && name.indexOf('[') < 0 && !name.equals(binaryName)) {
        referenced.add(name);
      }
    }

    return referenced;
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
      // CONSTANT_Integer, Float, Fieldref, Methodref, InterfaceMethodref, Dynamic: fixed u4
      // payload.
      // (NameAndType and InvokeDynamic are captured before reaching here.)
      case 3, 4, 9, 10, 11, 17 -> skip(data, 4);
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
