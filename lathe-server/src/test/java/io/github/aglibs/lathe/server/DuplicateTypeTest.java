package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DuplicateTypeTest extends LatheEngineTestBase {

  private Map<String, Path> duplicateTypes() throws Exception {
    return duplicateTypes("Contract");
  }

  private Map<String, Path> duplicateTypes(final String overlayContract) throws Exception {
    final var base = new LinkedHashMap<String, String>();
    base.put("com/example/Shared.java", shared("base"));
    base.put(
        "com/example/Contract.java",
        "package com.example; public interface Contract { void run(); }");
    base.put(
        "com/example/BaseUse.java",
        "package com.example; class BaseUse { Shared field; String use() { return field.value(); } }");
    base.put(
        "com/example/OtherContract.java",
        "package com.example; public interface OtherContract { void run(); }");
    final List<Path> baseFiles =
        TestCompiler.writeMirroredModule(tmp, "base", base, List.of(), 1_000L);
    final var overlay = new LinkedHashMap<String, String>();
    overlay.put(
        "com/example/Shared.java",
        shared("overlay").replace("implements Contract", "implements " + overlayContract));
    overlay.put(
        "com/example/OverlayUse.java",
        "package com.example; class OverlayUse { Shared field; String use() { return field.value(); } }");
    final List<Path> overlayFiles =
        TestCompiler.writeMirroredModule(tmp, "overlay", overlay, List.of("base"), 1_000L);
    final var consumer = new LinkedHashMap<String, String>();
    consumer.put(
        "app/Consumer.java",
        "package app; import com.example.Shared; class Consumer { Shared field; String use() { return field.value(); } }");
    consumer.put("app/Child.java", "package app; public class Child extends com.example.Shared {}");
    final List<Path> consumerFiles =
        TestCompiler.writeMirroredModule(
            tmp, "consumer", consumer, List.of("overlay", "base"), 1_000L);
    engine = new LatheEngine(tmp);
    return Map.of(
        "base",
        baseFiles.get(0),
        "contract",
        baseFiles.get(1),
        "baseUse",
        baseFiles.get(2),
        "overlay",
        overlayFiles.get(0),
        "overlayUse",
        overlayFiles.get(1),
        "consumer",
        consumerFiles.get(0),
        "child",
        consumerFiles.get(1),
        "otherContract",
        baseFiles.get(3));
  }

  private static String shared(final String value) {
    return "package com.example; public class Shared implements Contract { public void run() {} public String value() { return \"%s\"; } }"
        .formatted(value);
  }

  @ParameterizedTest
  @CsvSource({
    "base, Shared implements",
    "overlay, Shared implements",
    "base, value()",
    "overlay, value()"
  })
  void references_sameBinaryName_keepsDeclaringTreeSeparate(final String owner, final String token)
      throws Exception {
    final Map<String, Path> files = duplicateTypes();
    final Path declaration = files.get(owner);
    final var pos = positionOf(Files.readString(declaration), token);
    final List<Path> paths =
        engine
            .references(declaration, pos.getLine(), pos.getCharacter(), 50)
            .value()
            .references()
            .stream()
            .map(location -> LatheUri.toPath(location.uri()))
            .distinct()
            .toList();
    assertThat(paths).contains(declaration, files.get(owner + "Use"));
    assertThat(paths).doesNotContain(files.get(owner.equals("base") ? "overlay" : "base"));
    if (owner.equals("overlay")) {
      assertThat(paths).contains(files.get("consumer"));
    } else {
      assertThat(paths).doesNotContain(files.get("consumer"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"base", "overlay"})
  void definition_sameBinaryName_prefersTheAskingTree(final String owner) throws Exception {
    final Map<String, Path> files = duplicateTypes();
    final Path caller = files.get(owner + "Use");
    final var pos = positionOf(Files.readString(caller), "Shared field");
    assertThat(engine.definition(caller, pos.getLine(), pos.getCharacter()).value())
        .singleElement()
        .satisfies(
            location -> assertThat(LatheUri.toPath(location.uri())).isEqualTo(files.get(owner)));
    final Path consumer = files.get("consumer");
    final var use = positionOf(Files.readString(consumer), "Shared field");
    assertThat(engine.definition(consumer, use.getLine(), use.getCharacter()).value())
        .singleElement()
        .satisfies(
            location ->
                assertThat(LatheUri.toPath(location.uri())).isEqualTo(files.get("overlay")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"base", "overlay"})
  void rename_sameBinaryName_editsOnlyTheSelectedDeclarationAndItsCallers(final String owner)
      throws Exception {
    final Map<String, Path> files = duplicateTypes();
    final Path declaration = files.get(owner);
    final String other = owner.equals("base") ? "overlay" : "base";
    final String untouched = Files.readString(files.get(other));
    renameAt(declaration, Files.readString(declaration), "Shared implements", "Renamed");
    assertThat(declaration).doesNotExist();
    assertThat(declaration.resolveSibling("Renamed.java")).exists();
    assertThat(Files.readString(files.get(other))).isEqualTo(untouched);
    assertThat(Files.readString(files.get(owner + "Use"))).contains("Renamed field");
    assertThat(Files.readString(files.get(other + "Use"))).contains("Shared field");
    assertThat(Files.readString(files.get("consumer")))
        .contains(owner.equals("overlay") ? "Renamed field" : "Shared field");
  }

  @Test
  void searchSymbols_sameBinaryName_returnsBothDeclarations() throws Exception {
    final Map<String, Path> files = duplicateTypes();
    assertThat(engine.searchSymbols("Shared", SymbolScope.REACTOR, 50).value())
        .extracting(symbol -> LatheUri.toPath(symbol.location().uri()))
        .containsExactlyInAnyOrder(files.get("base"), files.get("overlay"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Contract", "run"})
  void implementations_sameBinaryName_returnsBothImplementations(final String token)
      throws Exception {
    final Map<String, Path> files = duplicateTypes();
    final Path contract = files.get("contract");
    final var pos = positionOf(Files.readString(contract), token);
    final List<Path> implementations =
        engine
            .findImplementations(contract, pos.getLine(), pos.getCharacter(), 50)
            .value()
            .implementations()
            .stream()
            .map(location -> LatheUri.toPath(location.uri()))
            .toList();
    if (token.equals("Contract")) {
      assertThat(implementations)
          .containsExactlyInAnyOrder(files.get("base"), files.get("overlay"), files.get("child"));
    } else {
      assertThat(implementations)
          .containsExactlyInAnyOrder(files.get("base"), files.get("overlay"));
    }
  }

  @Test
  void implementations_differentHierarchyUnderSameBinaryName_checksEachOwnersCompile()
      throws Exception {
    final Map<String, Path> files = duplicateTypes("OtherContract");
    for (final String key : List.of("contract", "otherContract")) {
      final Path contract = files.get(key);
      final String token = key.equals("contract") ? "Contract" : "OtherContract";
      final var pos = positionOf(Files.readString(contract), token);
      final List<Path> implementations =
          engine
              .findImplementations(contract, pos.getLine(), pos.getCharacter(), 50)
              .value()
              .implementations()
              .stream()
              .map(location -> LatheUri.toPath(location.uri()))
              .toList();
      if (key.equals("contract")) {
        assertThat(implementations).containsExactly(files.get("base"));
      } else {
        assertThat(implementations)
            .containsExactlyInAnyOrder(files.get("overlay"), files.get("child"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"base", "overlay"})
  void renameTwice_duplicateTypeIndexRefresh_keepsTheUntouchedCopyAndRejectsCollision(
      final String owner) throws Exception {
    final Map<String, Path> files = duplicateTypes();
    final Path declaration = files.get(owner);
    final String other = owner.equals("base") ? "overlay" : "base";
    renameAt(declaration, Files.readString(declaration), "Shared implements", "Renamed");
    assertThat(engine.searchSymbols("Shared", SymbolScope.REACTOR, 50).value())
        .extracting(symbol -> LatheUri.toPath(symbol.location().uri()))
        .containsExactly(files.get(other));
    final Path renamed = declaration.resolveSibling("Renamed.java");
    final String renamedSource = Files.readString(renamed);
    assertThatThrownBy(() -> renameAt(renamed, renamedSource, "Renamed implements", "Shared"))
        .hasMessageContaining("cannot be renamed");
    assertThat(Files.readString(renamed)).isEqualTo(renamedSource);
    renameAt(renamed, renamedSource, "Renamed implements", "ChangedAgain");
    assertThat(engine.searchSymbols("Renamed", SymbolScope.REACTOR, 50).value()).isEmpty();
    assertThat(engine.searchSymbols("ChangedAgain", SymbolScope.REACTOR, 50).value())
        .extracting(symbol -> LatheUri.toPath(symbol.location().uri()))
        .containsExactly(declaration.resolveSibling("ChangedAgain.java"));
    assertThat(engine.searchSymbols("Shared", SymbolScope.REACTOR, 50).value())
        .extracting(symbol -> LatheUri.toPath(symbol.location().uri()))
        .containsExactly(files.get(other));
    assertThat(Files.readString(files.get(owner + "Use"))).contains("ChangedAgain field");
    assertThat(Files.readString(files.get(other + "Use"))).contains("Shared field");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void referencesAndRename_packagePrivateDuplicateInOneModule_keepsSourceTreesSeparate(
      final boolean testTree) throws Exception {
    final String shared = "package com.example; class Shared {}";
    final var main = new LinkedHashMap<String, String>();
    main.put("com/example/Shared.java", shared);
    main.put("com/example/MainUse.java", "package com.example; class MainUse { Shared field; }");
    final List<Path> mainFiles =
        TestCompiler.writeMirroredModule(tmp, "module", main, List.of(), 1_000L);
    final Path testRoot = tmp.resolve("module/src/test/java");
    final Path testCopy =
        TestCompiler.writeAt(testRoot.resolve("com/example/Shared.java"), shared, 1_000L);
    final Path testUse =
        TestCompiler.writeAt(
            testRoot.resolve("com/example/TestUse.java"),
            "package com.example; class TestUse { Shared field; }",
            1_000L);
    final Path module = tmp.resolve(LatheLayout.LATHE_DIR).resolve("module");
    final Path testOutput = module.resolve(LatheLayout.TEST_CLASSES_DIR);
    TestJavac.compileToDir(
        testOutput, List.of(module.resolve(LatheLayout.CLASSES_DIR)), testCopy, testUse);
    Json.write(
        new ModuleConfigData(
            LatheLayout.TEST_CLASSES_DIR,
            testOutput.toString(),
            null,
            List.of(testRoot.toString()),
            List.of(module.resolve(LatheLayout.CLASSES_DIR).toString()),
            List.of(),
            List.of(),
            TestCompiler.RELEASE,
            "UTF-8",
            false,
            false,
            "none",
            List.of(),
            null,
            false,
            true),
        module.resolve(LatheLayout.paramsFileName(LatheLayout.TEST_CLASSES_DIR)));
    engine = new LatheEngine(tmp);
    final Path selected = testTree ? testCopy : mainFiles.get(0);
    final Path selectedUse = testTree ? testUse : mainFiles.get(1);
    final Path other = testTree ? mainFiles.get(0) : testCopy;
    final var pos = positionOf(shared, "Shared");
    assertThat(
            engine.references(selected, pos.getLine(), pos.getCharacter(), 50).value().references())
        .extracting(location -> LatheUri.toPath(location.uri()))
        .containsExactlyInAnyOrder(selected, selectedUse);
    renameAt(selected, shared, "Shared", "Renamed");
    assertThat(Files.readString(selected.resolveSibling("Renamed.java"))).contains("class Renamed");
    assertThat(Files.readString(selectedUse)).contains("Renamed field");
    assertThat(Files.readString(other)).isEqualTo(shared);
  }
}
