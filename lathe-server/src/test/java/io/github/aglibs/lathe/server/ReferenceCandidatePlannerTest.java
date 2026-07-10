package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceCandidatePlannerTest {

  @TempDir Path root;
  private Path src;

  @BeforeEach
  void createSourceRoot() throws IOException {
    src = Files.createDirectories(root.resolve("src"));
  }

  private Path write(final String name, final String content) throws IOException {
    final Path file = src.resolve(name);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  private String uri(final Path file) {
    return file.toUri().toString();
  }

  private ModuleSourceConfig config() {
    return TestCompiler.moduleConfig(root, src);
  }

  private Set<String> plan(final ReferenceTarget target) {
    return plan(WorkspaceTypeIndex.empty(), target);
  }

  private Set<String> plan(final WorkspaceTypeIndex typeIndex, final ReferenceTarget target) {
    final var index = ReferenceCandidateIndex.build(List.of(config()));
    return new ReferenceCandidatePlanner(index, typeIndex).planCandidates(config(), target);
  }

  private static WorkspaceTypeIndex reactorIndex(final TypeIndexEntry... entries) {
    return WorkspaceTypeIndex.empty().withReactorEntries(List.of(List.of(entries)));
  }

  private static TypeIndexEntry entry(final String binaryName, final String... directSupertypes) {
    final int dot = binaryName.lastIndexOf('.');
    final var packageName = dot < 0 ? "" : binaryName.substring(0, dot);
    final var simpleName = dot < 0 ? binaryName : binaryName.substring(dot + 1);
    return new TypeIndexEntry(
        simpleName, binaryName, packageName, TypeKind.CLASS, true, List.of(directSupertypes));
  }

  private static ReferenceTarget target(
      final ElementKind kind,
      final String qualifiedName,
      final String simpleName,
      final String descriptor,
      final List<String> overriddenDeclarers) {
    return new ReferenceTarget(
        kind,
        qualifiedName,
        simpleName,
        descriptor,
        ReferenceTarget.SearchScope.REACTOR_MODULES,
        overriddenDeclarers,
        kind == ElementKind.METHOD);
  }

  private static ReferenceTarget type(final String qualifiedName, final String simpleName) {
    return target(ElementKind.INTERFACE, qualifiedName, simpleName, null, List.of());
  }

  private static ReferenceTarget method(
      final String ownerBinaryName,
      final String simpleName,
      final String descriptor,
      final List<String> overriddenDeclarers) {
    return target(ElementKind.METHOD, ownerBinaryName, simpleName, descriptor, overriddenDeclarers);
  }

  private static ReferenceTarget field(final String ownerBinaryName, final String simpleName) {
    return target(ElementKind.FIELD, ownerBinaryName, simpleName, null, List.of());
  }

  private static ReferenceTarget constructor(
      final String ownerBinaryName, final String descriptor) {
    // javac reports a constructor's simple name as "<init>"; ReferenceTarget.from carries that
    // through verbatim — exactly what the planner must not key its candidate lookup on (FR-013).
    return target(ElementKind.CONSTRUCTOR, ownerBinaryName, "<init>", descriptor, List.of());
  }

  private Path gen() throws IOException {
    return Files.createDirectories(root.resolve("gen"));
  }

  private Path writeGen(final Path genRoot, final String name, final String content)
      throws IOException {
    final Path file = genRoot.resolve(name);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  private ModuleSourceConfig configWithGen(final Path genRoot) {
    return TestCompiler.moduleConfig(
        root.resolve(".lathe/module"), root.resolve("target/classes"), src, genRoot);
  }

  private Set<String> planWith(final ModuleSourceConfig cfg, final ReferenceTarget target) {
    final var index = ReferenceCandidateIndex.build(List.of(cfg));
    return new ReferenceCandidatePlanner(index, WorkspaceTypeIndex.empty())
        .planCandidates(cfg, target);
  }

  @Test
  void planCandidates_explicitImport_returnsFile() throws IOException {
    final Path fileA =
        write(
            "A.java",
            """
            package com.example;
            import java.util.List;
            class A { List list; }
            """);

    assertThat(plan(type("java.util.List", "List"))).containsExactly(uri(fileA));
  }

  @Test
  void planCandidates_wildcardImport_returnsFile() throws IOException {
    final Path fileB =
        write(
            "B.java",
            """
            package com.example;
            import java.util.*;
            class B { List list; }
            """);

    assertThat(plan(type("java.util.List", "List"))).containsExactly(uri(fileB));
  }

  @Test
  void planCandidates_noImport_excludesFile() throws IOException {
    write(
        "C.java",
        """
        package com.example;
        class C {
          int List = 1;
        }
        """);

    assertThat(plan(type("java.util.List", "List"))).isEmpty();
  }

  @Test
  void planCandidates_samePackage_returnsFile() throws IOException {
    final Path fileD =
        write(
            "java/util/D.java",
            """
            package java.util;
            class D { List list; }
            """);

    assertThat(plan(type("java.util.List", "List"))).containsExactly(uri(fileD));
  }

  @Test
  void planCandidates_staticImportMember_returnsFile() throws IOException {
    final Path fileE =
        write(
            "E.java",
            """
            package com.example;
            import static java.util.Collections.emptyList;
            class E { void run() { emptyList(); } }
            """);

    assertThat(plan(method("java.util.Collections", "emptyList", "()", List.of())))
        .containsExactly(uri(fileE));
  }

  @Test
  void planCandidates_enclosingClassReferenced_returnsFile() throws IOException {
    final Path fileF =
        write(
            "F.java",
            """
            package com.example;
            import java.util.Collections;
            class F { void run() { Collections.emptyList(); } }
            """);

    assertThat(plan(method("java.util.Collections", "emptyList", "()", List.of())))
        .containsExactly(uri(fileF));
  }

  @Test
  void planCandidates_methodInvokedOnUnspelledReceiver_includesCallSite() throws IOException {
    // FR-011: a method is invoked on a receiver whose type is inferred and never spelled in the
    // calling file (a var bound from a getter chain). Method candidates are the broad simple-name
    // set with no override-family narrowing, so the call site is reached even though the file never
    // writes the declaring type's name.
    final Path rec =
        write(
            "Config.java",
            """
            package com.example;
            record Config(int amount) {}
            """);
    final Path caller =
        write(
            "Caller.java",
            """
            package com.example;
            class Caller {
              int read(Holder h) {
                var c = h.config();
                return c.amount();
              }
            }
            """);

    assertThat(plan(method("com.example.Config", "amount", "()", List.of())))
        .contains(uri(caller), uri(rec));
  }

  @Test
  void planCandidates_methodNameNeverSpelled_excludesFile() throws IOException {
    // The only bound on a method search is spelling its simple name; a file that never mentions it
    // is not a candidate.
    write(
        "Unrelated.java",
        """
        package com.example;
        class Unrelated {
          int total() {
            return 1;
          }
        }
        """);

    assertThat(plan(method("com.example.Config", "amount", "()", List.of()))).isEmpty();
  }

  @Test
  void planCandidates_protectedFieldInheritedInSubclass_includesSubclassFiles() throws IOException {
    final Path base =
        write(
            "Base.java",
            """
            package com.example;
            class Base { protected int count; }
            """);
    write(
        "Sub.java",
        """
        package com.example;
        class Sub extends Base {}
        """);
    // Accesses the inherited field through the subtype only — never spells the declaring type.
    final Path client =
        write(
            "Client.java",
            """
            package com.example;
            class Client { int read(Sub s) { return s.count; } }
            """);

    final var typeIndex =
        reactorIndex(entry("com.example.Base"), entry("com.example.Sub", "com.example.Base"));

    assertThat(plan(typeIndex, field("com.example.Base", "count")))
        .containsExactlyInAnyOrder(uri(base), uri(client));
  }

  @Test
  void planCandidates_fieldNoSubtypes_limitsToDeclaringTypeFiles() throws IOException {
    final Path base =
        write(
            "Base.java",
            """
            package com.example;
            class Base { protected int count; }
            """);
    // Same field name, unrelated owner — excluded when the field search is bounded.
    write(
        "Unrelated.java",
        """
        package com.example;
        class Unrelated { int count; void bump() { count++; } }
        """);

    assertThat(plan(field("com.example.Base", "count"))).containsExactly(uri(base));
  }

  @Test
  void planCandidates_javaLangType_returnsAllMentions() throws IOException {
    final Path fileG =
        write(
            "G.java",
            """
            package com.example;
            class G { String name; }
            """);

    assertThat(plan(target(ElementKind.CLASS, "java.lang.String", "String", null, List.of())))
        .containsExactly(uri(fileG));
  }

  @Test
  void planCandidates_typeUsedInSamePackageGeneratedSource_includesGeneratedFile()
      throws IOException {
    // FR-012: the generated builder is in the record's package but under the generated-sources
    // root,
    // and names the record without importing it. It is indexed, so the same-package filter must
    // consult the generated root (not just sourceRoots) to keep it.
    write(
        "com/example/Config.java",
        """
        package com.example;
        public record Config(int amount) {}
        """);
    final Path genRoot = gen();
    final Path builder =
        writeGen(
            genRoot,
            "com/example/ConfigBuilder.java",
            """
            package com.example;
            public final class ConfigBuilder {
              public Config build() {
                return new Config(0);
              }
            }
            """);

    assertThat(planWith(configWithGen(genRoot), type("com.example.Config", "Config")))
        .contains(uri(builder));
  }

  @Test
  void planCandidates_typeInGeneratedSourceDifferentPackage_excludesFile() throws IOException {
    // A generated file in a DIFFERENT package that merely spells the simple name must stay excluded
    // even once the same-package filter is generated-root aware.
    write(
        "com/example/Config.java",
        """
        package com.example;
        public record Config(int amount) {}
        """);
    final Path genRoot = gen();
    final Path other =
        writeGen(
            genRoot,
            "com/other/Unrelated.java",
            """
            package com.other;
            class Unrelated {
              int Config = 1;
            }
            """);

    assertThat(planWith(configWithGen(genRoot), type("com.example.Config", "Config")))
        .doesNotContain(uri(other));
  }

  @Test
  void planCandidates_constructorInvokedViaNew_includesCallSite() throws IOException {
    // FR-013: the constructor target is keyed on javac's "<init>" name, which no source spells, so
    // planning returns nothing. The caller constructs Config but is dropped before the type-name
    // narrowing even runs.
    write(
        "Config.java",
        """
        package com.example;
        public record Config(int amount) {}
        """);
    final Path caller =
        write(
            "Factory.java",
            """
            package com.example;
            class Factory {
              Config make() {
                return new Config(0);
              }
            }
            """);

    assertThat(plan(constructor("com.example.Config", "(int)"))).contains(uri(caller));
  }

  @Test
  void planCandidates_constructorOwnerNameNotSpelled_excludesFile() throws IOException {
    // A file that never spells the type is not a candidate for its constructor.
    write(
        "Elsewhere.java",
        """
        package com.example;
        class Elsewhere {
          int run() {
            return 42;
          }
        }
        """);

    assertThat(plan(constructor("com.example.Config", "(int)"))).isEmpty();
  }
}
