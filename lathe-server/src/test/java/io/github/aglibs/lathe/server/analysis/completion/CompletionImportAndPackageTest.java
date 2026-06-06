package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompletionImportAndPackageTest extends CompletionTestSupport {

  @Test
  void fqnNavigation_topLevelPackage_suggestsSubPackages() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.§
                    }
                }"""));
    assertThat(items).contains("util", "lang");
    assertThat(items).doesNotContain("ArrayList", "String", "if", "for");
  }

  @Test
  void fqnNavigation_nestedPackage_suggestsTypesAndSubPackages() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.util.§
                    }
                }"""));
    assertThat(items).contains("ArrayDeque", "AbstractList", "concurrent");
    assertThat(items).doesNotContain("if", "for", "TimeUnit");
  }

  @Test
  void fqnNavigation_deepPackage_suggestsTypes() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.util.concurrent.§
                    }
                }"""));
    assertThat(items).contains("TimeUnit");
    assertThat(items).doesNotContain("ArrayDeque", "AbstractList");
  }

  // ── import declarations ───────────────────────────────────────────────────────

  @Test
  void importDeclaration_nonStatic_suggestsSegmentsAndTypes() {
    // prefix navigation
    assertThat(labels(fixture.complete("import java.ut§;\n\nclass Test {}"))).contains("util");
    assertThat(labels(fixture.complete("import java.§;\n\nclass Test {}"))).contains("util");
    // nested package: types and sub-packages appear; static members do not
    final var afterUtil = fixture.complete("import java.util.§;\n\nclass Test {}");
    assertThat(labels(afterUtil)).contains("Collections", "concurrent");
    assertThat(afterUtil).noneMatch(i -> i.getLabel().equals("emptyList"));
    // type segment
    assertThat(labels(fixture.complete("import java.util.Col§;\n\nclass Test {}")))
        .contains("Collections");
    // non-static import path must not suggest static members
    assertThat(fixture.complete("import java.util.Collections.empty§;\n\nclass Test {}"))
        .noneMatch(i -> i.getLabel().startsWith("emptyList"));
    // non-matching prefix: unrelated types must not appear
    assertThat(fixture.complete("import java.util.Xyz§;\n\nclass Test {}"))
        .noneMatch(i -> i.getLabel().equals("ArrayList"));
    // deep-nested sub-package must not appear as immediate child of java.util
    assertThat(labels(fixture.complete("import java.util.§;\n\nclass Test {}")))
        .doesNotContain("atomic");
    // text edit includes trailing semicolon (source has no trailing ';' so engine adds one)
    final var mapItem = itemLabeled(fixture.complete("import java.util.§\n\nclass Test {}"), "Map");
    assertThat(mapItem).isPresent();
    assertThat(mapItem.get().getTextEdit().getLeft().getNewText()).isEqualTo("Map;");
  }

  @Test
  void importDeclaration_staticImport_suggestsSegmentsAndBareNames() {
    // prefix navigation
    assertThat(labels(fixture.complete("import static java.§;\n\nclass Test {}"))).contains("util");
    // package level: sub-types and packages, no static members
    final var afterUtil = fixture.complete("import static java.util.§;\n\nclass Test {}");
    assertThat(labels(afterUtil)).contains("Collections", "concurrent");
    assertThat(afterUtil).noneMatch(i -> i.getLabel().equals("emptyList"));
    // type level: static members appear
    assertThat(
            labels(
                fixture.complete("import static java.util.Collections.empty§;\n\nclass Test {}")))
        .anyMatch(l -> l.startsWith("emptyList"));
    // text edit is bare name + semicolon, not a snippet
    final var equalsItem =
        fixture.complete("import static java.util.Objects.§\n\nclass Test {}").stream()
            .filter(i -> i.getLabel().startsWith("equals"))
            .findFirst();
    assertThat(equalsItem).isPresent();
    assertThat(equalsItem.get().getTextEdit().getLeft().getNewText()).isEqualTo("equals;");
    assertThat(
            labels(
                fixture.complete("import static java.util.concurrent.TimeUnit.§\nclass Test {}")))
        .contains("SECONDS");
  }

  // ── class body ───────────────────────────────────────────────────────────────

  @Test
  void staleCacheDotTrigger_importSuggested() {
    // non-static — two levels
    assertThat(
            labels(
                fixture.completeWithCache(
                    "import java;\nclass Test {}", "import java.§;\n\nclass Test {}")))
        .contains("util");
    assertThat(
            labels(
                fixture.completeWithCache(
                    "import java.util;\nclass Test {}", "import java.util.§;\n\nclass Test {}")))
        .contains("Collections");
    // static
    assertThat(
            labels(
                fixture.completeWithCache(
                    "import static java;\nclass Test {}",
                    "import static java.§;\n\nclass Test {}")))
        .contains("util");
  }

  @Test
  void fqnNavigation_isAccessible_filtersNonExportedPackageTypes() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() { com.example.lib.api.§ }
                    }""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .contains("ApiType");

    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() { com.example.lib.internal.§ }
                    }""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");

    // import declaration
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    import com.example.lib.internal.§;
                    class Test {}""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");

    // class body type reference
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        com.example.lib.internal.§ field;
                    }""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");
  }

  @Test
  void fqnNavigation_isAccessible_staticImport_filtersNonExportedPackageTypes() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    import static com.example.lib.internal.§;
                    class Test {}""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");
  }

  @Test
  void fqnNavigation_subPackageStream_filtersNonExportedSubPackages() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                import com.example.lib.§;
                class Test {}""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("api");
    assertThat(segments).doesNotContain("internal");
  }

  @Test
  void fqnNavigation_subPackageStream_methodBody_filtersNonExportedSubPackages()
      throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                class Test {
                    void m() { com.example.lib.§ }
                }""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("api");
    assertThat(segments).doesNotContain("internal");
  }

  @Test
  void fqnNavigation_classBody_packagePrefix_suggestsSubPackages() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                class Test {
                    com.example.lib.§ field;
                }""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("api");
    assertThat(segments).doesNotContain("internal");
  }

  @Test
  void fqnNavigation_subPackageStream_filtersNonTransitiveModuleSubPackages() throws IOException {
    final var lib = buildLibWithHiddenDep();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                import com.example.§;
                class Test {}""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("lib");
    assertThat(segments).doesNotContain("other");
  }

  @Test
  void fqnNavigation_jpmsReadableModule_suggestsTypes() throws IOException {
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() {
                            javax.swing.§
                        }
                    }""",
                    """
                    module com.example.app {
                        requires java.desktop;
                    }""")))
        .anyMatch(l -> l.startsWith("J"));
  }

  @Test
  void fqnNavigation_jpmsUnreadableModule_suggestsNothing() throws IOException {
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);
    assertThat(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                class Test {
                    void m() {
                        javax.swing.§
                    }
                }""",
                """
                module com.example.app {
                }"""))
        .isEmpty();
  }

  // ── type index: JPMS visibility ───────────────────────────────────────────────
}
