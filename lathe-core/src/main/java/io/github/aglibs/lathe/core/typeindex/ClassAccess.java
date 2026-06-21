package io.github.aglibs.lathe.core.typeindex;

record ClassAccess(int flags) {

  private static final int ACC_PUBLIC = 0x0001;
  private static final int ACC_INTERFACE = 0x0200;
  private static final int ACC_ANNOTATION = 0x2000;
  private static final int ACC_ENUM = 0x4000;
  private static final int ACC_MODULE = 0x8000;

  boolean isPublicType() {
    return hasFlag(ACC_PUBLIC) && !hasFlag(ACC_MODULE);
  }

  TypeKind kind() {
    if (hasFlag(ACC_ANNOTATION)) {
      return TypeKind.ANNOTATION;
    }

    if (hasFlag(ACC_ENUM)) {
      return TypeKind.ENUM;
    }

    if (hasFlag(ACC_INTERFACE)) {
      return TypeKind.INTERFACE;
    }

    return TypeKind.CLASS;
  }

  private boolean hasFlag(final int flag) {
    return (flags & flag) != 0;
  }
}
