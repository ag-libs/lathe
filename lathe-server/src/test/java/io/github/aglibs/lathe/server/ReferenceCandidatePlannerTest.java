package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceCandidatePlannerTest {

  @TempDir Path root;

  private String uri(final Path file) {
    return file.toUri().toString();
  }

  @Test
  void planCandidates_explicitImport_returnsFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path fileA =
        Files.writeString(
            src.resolve("A.java"),
            """
            package com.example;
            import java.util.List;
            class A { List list; }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.INTERFACE,
            "java.util.List",
            "List",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(fileA));
  }

  @Test
  void planCandidates_wildcardImport_returnsFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path fileB =
        Files.writeString(
            src.resolve("B.java"),
            """
            package com.example;
            import java.util.*;
            class B { List list; }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.INTERFACE,
            "java.util.List",
            "List",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(fileB));
  }

  @Test
  void planCandidates_noImport_excludesFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    Files.writeString(
        src.resolve("C.java"),
        """
        package com.example;
        class C {
          int List = 1;
        }
        """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.INTERFACE,
            "java.util.List",
            "List",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).isEmpty();
  }

  @Test
  void planCandidates_samePackage_returnsFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path pkgDir = Files.createDirectories(src.resolve("java/util"));
    final Path fileD =
        Files.writeString(
            pkgDir.resolve("D.java"),
            """
            package java.util;
            class D { List list; }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.INTERFACE,
            "java.util.List",
            "List",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(fileD));
  }

  @Test
  void planCandidates_staticImportMember_returnsFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path fileE =
        Files.writeString(
            src.resolve("E.java"),
            """
            package com.example;
            import static java.util.Collections.emptyList;
            class E { void run() { emptyList(); } }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.METHOD,
            "java.util.Collections",
            "emptyList",
            "()",
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(fileE));
  }

  @Test
  void planCandidates_enclosingClassReferenced_returnsFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path fileF =
        Files.writeString(
            src.resolve("F.java"),
            """
            package com.example;
            import java.util.Collections;
            class F { void run() { Collections.emptyList(); } }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.METHOD,
            "java.util.Collections",
            "emptyList",
            "()",
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(fileF));
  }

  @Test
  @Disabled("FR-007: override targets must search inherited hook calls in supertype sources")
  void planCandidates_overrideMethodTarget_returnsSuperclassSelfCallFile() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path file =
        Files.writeString(
            src.resolve("AbstractValueParamProvider.java"),
            """
            package org.example;
            abstract class AbstractValueParamProvider {
              abstract Object createValueProvider(String parameter);
              Object getValueProvider(String parameter) {
                return createValueProvider(parameter);
              }
            }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.METHOD,
            "com.example.app.SessionFactoryProvider",
            "createValueProvider",
            "(java.lang.String)",
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(file));
  }

  @Test
  void planCandidates_javaLangType_returnsAllMentions() throws IOException {
    final Path src = Files.createDirectories(root.resolve("src"));
    final Path fileG =
        Files.writeString(
            src.resolve("G.java"),
            """
            package com.example;
            class G { String name; }
            """);

    final var index = ReferenceCandidateIndex.build(List.of(TestCompiler.moduleConfig(root, src)));
    final var planner = new ReferenceCandidatePlanner(index);

    final var target =
        new ReferenceTarget(
            ElementKind.CLASS,
            "java.lang.String",
            "String",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES);

    final Set<String> candidates =
        planner.planCandidates(TestCompiler.moduleConfig(root, src), target);
    assertThat(candidates).containsExactly(uri(fileG));
  }
}
