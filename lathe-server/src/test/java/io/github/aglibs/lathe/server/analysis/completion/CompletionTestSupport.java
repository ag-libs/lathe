package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

abstract class CompletionTestSupport {

  @TempDir static Path sharedTmp;
  @TempDir Path tmp;

  protected static CompletionFixture fixture;
  protected CompletionFixture localFixture;

  @BeforeAll
  static void setup() throws IOException {
    fixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                sharedTmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayDeque", "java.util.ArrayDeque", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "AbstractList", "java.util.AbstractList", TypeKind.CLASS),
                CompletionFixture.typeEntry("Integer", "java.lang.Integer", TypeKind.CLASS),
                CompletionFixture.typeEntry("Runnable", "java.lang.Runnable", TypeKind.INTERFACE),
                CompletionFixture.typeEntry(
                    "StringBuilder", "java.lang.StringBuilder", TypeKind.CLASS),
                CompletionFixture.typeEntry("String", "java.lang.String", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "TimeUnit", "java.util.concurrent.TimeUnit", TypeKind.ENUM)));
  }

  @AfterAll
  static void teardown() {
    fixture.close();
  }

  @AfterEach
  void closeLocalFixture() {
    if (localFixture != null) {
      localFixture.close();
      localFixture = null;
    }
  }

  protected static List<String> labels(final List<CompletionItem> items) {
    return items.stream().map(CompletionItem::getLabel).toList();
  }

  protected static Optional<CompletionItem> itemLabeled(
      final List<CompletionItem> items, final String label) {
    return items.stream().filter(i -> label.equals(i.getLabel())).findFirst();
  }

  protected static Optional<CompletionItem> itemWithFilterText(
      final List<CompletionItem> items, final String text) {
    return items.stream().filter(i -> text.equals(i.getFilterText())).findFirst();
  }

  protected static Optional<CompletionItem> itemWithLabelDetail(
      final List<CompletionItem> items, final String label, final String labelDetail) {
    return items.stream()
        .filter(item -> label.equals(item.getLabel()))
        .filter(item -> item.getLabelDetails() != null)
        .filter(item -> labelDetail.equals(item.getLabelDetails().getDetail()))
        .findFirst();
  }

  protected record ExampleLib(List<String> modulePath, String moduleInfo) {}

  protected ExampleLib buildExampleLib() throws IOException {
    final Path libSrc = tmp.resolve("lib-src");
    Files.createDirectories(libSrc.resolve("com/example/lib/api"));
    Files.createDirectories(libSrc.resolve("com/example/lib/internal"));
    Files.writeString(
        libSrc.resolve("module-info.java"),
        """
        module com.example.lib {
            exports com.example.lib.api;
        }""");
    Files.writeString(
        libSrc.resolve("com/example/lib/api/ApiType.java"),
        "package com.example.lib.api; public class ApiType {}");
    Files.writeString(
        libSrc.resolve("com/example/lib/internal/InternalType.java"),
        "package com.example.lib.internal; public class InternalType {}");
    final Path libOut = tmp.resolve("lib-out");
    TestCompiler.compileToDir(
        libOut,
        List.of(),
        List.of(),
        libSrc.resolve("module-info.java"),
        libSrc.resolve("com/example/lib/api/ApiType.java"),
        libSrc.resolve("com/example/lib/internal/InternalType.java"));
    return new ExampleLib(
        List.of("--module-path", libOut.toString()),
        """
        module com.example.app {
            requires com.example.lib;
        }""");
  }

  protected ExampleLib buildLibWithHiddenDep() throws IOException {
    final Path otherSrc = tmp.resolve("other-src");
    Files.createDirectories(otherSrc.resolve("com/example/other"));
    Files.writeString(
        otherSrc.resolve("module-info.java"),
        "module com.example.other { exports com.example.other; }");
    Files.writeString(
        otherSrc.resolve("com/example/other/OtherType.java"),
        "package com.example.other; public class OtherType {}");
    final Path otherOut = tmp.resolve("other-out");
    TestCompiler.compileToDir(
        otherOut,
        List.of(),
        List.of(),
        otherSrc.resolve("module-info.java"),
        otherSrc.resolve("com/example/other/OtherType.java"));

    final Path libSrc = tmp.resolve("lib-src");
    Files.createDirectories(libSrc.resolve("com/example/lib"));
    Files.writeString(
        libSrc.resolve("module-info.java"),
        """
        module com.example.lib {
            requires com.example.other;
            exports com.example.lib;
        }""");
    Files.writeString(
        libSrc.resolve("com/example/lib/LibType.java"),
        "package com.example.lib; public class LibType {}");
    final Path libOut = tmp.resolve("lib-out");
    TestCompiler.compileToDir(
        libOut,
        List.of(),
        List.of("--module-path", otherOut.toString()),
        libSrc.resolve("module-info.java"),
        libSrc.resolve("com/example/lib/LibType.java"));

    return new ExampleLib(
        List.of("--module-path", libOut + ":" + otherOut),
        """
        module com.example.app {
            requires com.example.lib;
        }""");
  }
}
