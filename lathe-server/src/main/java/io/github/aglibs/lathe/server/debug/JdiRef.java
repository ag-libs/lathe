package io.github.aglibs.lathe.server.debug;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * How the interpreter resolves a javac-resolved symbol against the suspended JDI frame (the Stage-2
 * bridge). v1 covers reads: a {@link Local} (a local or parameter, matched by name in the frame), a
 * {@link Field} (instance or static, by declaring type + name), and a {@link Type} (by binary name,
 * for casts / {@code instanceof} / a static receiver). Method-descriptor bridging arrives with
 * method invocation in v2.
 */
sealed interface JdiRef permits JdiRef.Local, JdiRef.Field, JdiRef.Type {

  record Local(String name) implements JdiRef {
    public Local {
      ValidCheck.check().notNull(name, "name").validate();
    }
  }

  record Field(String declaringBinaryName, String name, boolean isStatic) implements JdiRef {
    public Field {
      ValidCheck.check()
          .notNull(declaringBinaryName, "declaringBinaryName")
          .notNull(name, "name")
          .validate();
    }
  }

  record Type(String binaryName) implements JdiRef {
    public Type {
      ValidCheck.check().notNull(binaryName, "binaryName").validate();
    }
  }
}
