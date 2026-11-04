package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewTypeTemplatesTest {

  @TempDir private Path tmp;

  private Path sourceRoot;

  @BeforeEach
  void setUp() {
    sourceRoot = tmp.resolve("module/src/main/java");
  }

  @Test
  void renderNewType_class_skeletonPathAndBodyCaret() {
    final var result = render(TypeKind.CLASS, "Foo", "com.example");

    assertThat(result.path()).isEqualTo(sourceRoot.resolve("com/example/Foo.java").toString());
    assertThat(result.content()).isEqualTo("package com.example;\n\npublic class Foo {\n\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(3); // the empty body line
    assertThat(result.caret().getCharacter()).isZero();
  }

  @Test
  void renderNewType_record_caretInComponentList() {
    final var result = render(TypeKind.RECORD, "Point", "com.example");

    assertThat(result.content()).isEqualTo("package com.example;\n\npublic record Point() {\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(2);
    assertThat(result.caret().getCharacter()).isEqualTo("public record Point(".length());
  }

  @Test
  void renderNewType_interfaceEnumAndDefaultPackage_useKeywordAndOmitPackageLine() {
    assertThat(render(TypeKind.INTERFACE, "Bar", "com.example").content())
        .isEqualTo("package com.example;\n\npublic interface Bar {\n\n}\n");
    assertThat(render(TypeKind.ENUM, "Color", "com.example").content())
        .isEqualTo("package com.example;\n\npublic enum Color {\n\n}\n");

    final var defaultPkg = render(TypeKind.CLASS, "Foo", "");
    assertThat(defaultPkg.content()).isEqualTo("public class Foo {\n\n}\n");
    assertThat(defaultPkg.path()).isEqualTo(sourceRoot.resolve("Foo.java").toString());
    assertThat(defaultPkg.caret().getLine())
        .isEqualTo(1); // no package header → body line shifts up
  }

  @Test
  void renderNewType_test_junitSkeletonWithMethodBodyCaret() {
    final var result = render(TypeKind.TEST, "FooTest", "com.example");

    assertThat(result.path()).isEqualTo(sourceRoot.resolve("com/example/FooTest.java").toString());
    assertThat(result.content())
        .isEqualTo(
            "package com.example;\n\nimport org.junit.jupiter.api.Test;\n\nclass FooTest {\n\n"
                + "  @Test\n  void name() {\n\n  }\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(8); // the empty @Test method body line
    assertThat(result.caret().getCharacter()).isZero();

    // No package header shifts every line — and the caret — up by the two header lines.
    assertThat(render(TypeKind.TEST, "FooTest", "").caret().getLine()).isEqualTo(6);
  }

  @Test
  void renderNewType_invalidNamesAndPlacement_rejected() {
    assertThatThrownBy(() -> render(TypeKind.CLASS, "9Foo", "com.example"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> render(TypeKind.CLASS, "class", "com.example"))
        .isInstanceOf(IllegalArgumentException.class);
    // A restricted identifier cannot name a type, though a name containing one can.
    assertThatThrownBy(() -> render(TypeKind.CLASS, "var", "com.example"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("'var' is not a valid Java type name");
    assertThatThrownBy(() -> render(TypeKind.RECORD, "record", "com.example"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(render(TypeKind.RECORD, "Record", "com.example").path()).endsWith("Record.java");
    // module-info: a qualified module name — malformed or a keyword segment is rejected.
    assertThatThrownBy(() -> render(TypeKind.MODULE_INFO, "com.9bad", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> render(TypeKind.MODULE_INFO, "com.class", ""))
        .isInstanceOf(IllegalArgumentException.class);
    // package-info: the default package cannot carry one.
    assertThatThrownBy(() -> render(TypeKind.PACKAGE_INFO, "ignored", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renderNewType_packageInfo_javadocStubInPackageDir() {
    final var result = render(TypeKind.PACKAGE_INFO, "ignored", "com.example");

    assertThat(result.path())
        .isEqualTo(sourceRoot.resolve("com/example/package-info.java").toString());
    assertThat(result.content()).isEqualTo("/**\n * \n */\npackage com.example;\n");
    assertThat(result.caret().getLine()).isEqualTo(1); // the javadoc body line
    assertThat(result.caret().getCharacter()).isEqualTo(3);
  }

  @Test
  void renderNewType_moduleInfo_qualifiedNameAtSourceRootIgnoringPackage() {
    final var result = render(TypeKind.MODULE_INFO, "com.example.app", "any.pkg");

    // module-info.java lands at the source root, never a package dir, whatever the package.
    assertThat(result.path()).isEqualTo(sourceRoot.resolve("module-info.java").toString());
    assertThat(result.content()).isEqualTo("module com.example.app {\n\n}\n");
    assertThat(result.caret().getLine()).isEqualTo(1); // the empty body line
    assertThat(result.caret().getCharacter()).isZero();
  }

  private CreateTypeResult render(final TypeKind type, final String name, final String pkg) {
    return NewTypeTemplates.render(sourceRoot, pkg, type, name);
  }

  @Test
  void enums_fromWireAndSourceTree_mapKnownTokensRejectUnknownWireAndTakeExtraTreesAsMain() {
    assertThat(TypeKind.fromWire("record")).isEqualTo(TypeKind.RECORD);
    assertThat(TypeKind.fromWire("test")).isEqualTo(TypeKind.TEST);
    assertThat(TypeKind.fromWire("package-info")).isEqualTo(TypeKind.PACKAGE_INFO);
    assertThat(TypeKind.fromWire("module-info")).isEqualTo(TypeKind.MODULE_INFO);
    assertThat(SourceScope.fromWire("test")).isEqualTo(SourceScope.TEST);
    assertThat(SourceScope.ofSourceTree(LatheLayout.TEST_CLASSES_DIR)).isEqualTo(SourceScope.TEST);
    assertThat(SourceScope.ofSourceTree(LatheLayout.CLASSES_DIR)).isEqualTo(SourceScope.MAIN);
    assertThatThrownBy(() -> TypeKind.fromWire("annotation"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SourceScope.fromWire("prod"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SourceScope.ofSourceTree("21")).isEqualTo(SourceScope.MAIN);
  }
}
