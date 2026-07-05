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
  void planCandidates_overriddenMethod_includesOverrideFamilyFiles() throws IOException {
    // The self-call lives in a reactor supertype that declares the overridden method; the file
    // never spells the declaring subtype, so it is reachable only through the override family.
    final Path file =
        write(
            "AbstractValueProvider.java",
            """
            package org.example;
            abstract class AbstractValueProvider {
              abstract Object createValueProvider(String parameter);
              Object getValueProvider(String parameter) {
                return createValueProvider(parameter);
              }
            }
            """);

    final var typeIndex = reactorIndex(entry("org.example.AbstractValueProvider"));

    assertThat(
            plan(
                typeIndex,
                method(
                    "com.example.app.SessionFactoryProvider",
                    "createValueProvider",
                    "(java.lang.String)",
                    List.of("org.example.AbstractValueProvider"))))
        .containsExactly(uri(file));
  }

  @Test
  void planCandidates_overridesObjectMethod_fallsBackToBroad() throws IOException {
    // Declares its own toString; never spells the declaring type or "Object".
    final Path fileA =
        write(
            "A.java",
            """
            package com.example;
            class A { public String toString() { return "a"; } }
            """);
    final Path fileB =
        write(
            "B.java",
            """
            package com.example;
            class B { String show(Object o) { return o.toString(); } }
            """);

    // Overriding java.lang.Object must stay broad: family narrowing would drop A (no receiver
    // type spelled), keeping only the file that happens to spell "Object".
    assertThat(plan(method("com.example.MyType", "toString", "()", List.of("java.lang.Object"))))
        .containsExactlyInAnyOrder(uri(fileA), uri(fileB));
  }

  @Test
  void planCandidates_overridesExternalInterface_narrowsByDeclarerName() throws IOException {
    // Polymorphic call through the (dependency) interface — spells the declarer, so it is kept.
    final Path caller =
        write(
            "Caller.java",
            """
            package com.example;
            class Caller { String go(BaseConfig c) { return c.name(); } }
            """);
    // Same simple name, unrelated owner, spells neither declaring type nor declarer — excluded.
    write(
        "Other.java",
        """
        package com.example;
        class Other { String name() { return "x"; } String m() { return name(); } }
        """);

    // Overrides an external interface method (BaseConfig.name); the declarer's simple name bounds
    // the search instead of widening to every file that spells the simple name.
    assertThat(
            plan(method("com.example.MyConfig", "name", "()", List.of("com.telenor.BaseConfig"))))
        .containsExactly(uri(caller));
  }

  @Test
  void planCandidates_recordAccessorNoInterface_limitsToDeclaringTypeFiles() throws IOException {
    final Path rec =
        write(
            "Rec.java",
            """
            package com.example;
            record Rec(int amount) {}
            """);
    final Path caller =
        write(
            "Caller.java",
            """
            package com.example;
            class Caller { int read(Rec r) { return r.amount(); } }
            """);
    // Same simple name, unrelated owner — must be excluded once the search is bounded.
    write(
        "Other.java",
        """
        package com.example;
        class Other { int amount() { return 1; } int m() { return amount(); } }
        """);

    assertThat(plan(method("com.example.Rec", "amount", "()", List.of())))
        .containsExactlyInAnyOrder(uri(rec), uri(caller));
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
}
