package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.Positions.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The read-only tools: diagnostics, definition, references, call hierarchy, implementations,
// describe, symbol search and outline.
class LatheEngineNavigationTest extends LatheEngineTestBase {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void definition_constructorInAnotherFile_returnsSelectedOverloadAtWorkspacePath(
      final boolean implicit) throws Exception {
    final String declaration =
        implicit
            ? "package com.example; class Widget {}"
            : """
            package com.example;
            class Widget {
              Widget(int value) {}
              Widget(String value) {}
            }
            """;
    final String source =
        "package com.example; class Caller { Object value = new Widget(%s); }"
            .formatted(implicit ? "" : "\"text\"");
    final Path widget = TestCompiler.writeModuleSource(tmp, "com/example/Widget.java", declaration);
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", source);
    compileIntoMirror(widget, caller);
    engine = new LatheEngine(tmp);
    final var pos = positionOf(source, "Widget(");

    assertThat(engine.definition(caller, pos.getLine(), pos.getCharacter()).value())
        .singleElement()
        .satisfies(
            target -> {
              assertThat(target.uri()).isEqualTo(widget.toUri().toString());
              assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
              assertThat(target.snippet())
                  .isEqualTo(implicit ? declaration : "Widget(String value) {}");
            });
  }

  @Test
  void diagnostics_cleanSource_returnsNoDiagnostics() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample { int n = 1; }");
    engine = new LatheEngine(tmp);

    final List<LatheDiagnostic> diagnostics = engine.diagnostics(source).value();

    assertThat(diagnostics).isEmpty();
  }

  @Test
  void diagnostics_typeError_returnsCompilerDiagnostic() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sample.java",
            "package com.example; class Sample { int n = \"nope\"; }");
    engine = new LatheEngine(tmp);

    final List<LatheDiagnostic> diagnostics = engine.diagnostics(source).value();

    assertThat(diagnostics).isNotEmpty();
    assertThat(diagnostics).anyMatch(d -> d.message().toLowerCase().contains("incompatible types"));
  }

  @Test
  void definition_crossFileInModule_resolvesTargetWithItsWorkspacePathAndLine() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = positionOf(fx.callerSource(), "greet()");
    final List<LatheLocation> targets =
        engine.definition(fx.caller(), pos.getLine(), pos.getCharacter()).value();

    assertThat(targets).hasSize(1);
    final LatheLocation target = targets.get(0);
    assertThat(target.uri()).endsWith("Callee.java");
    assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
    assertThat(target.path()).isEqualTo(FileUtil.slashed(tmp.relativize(fx.callee())));
    assertThat(target.snippet()).as("the target line alone").isEqualTo("void greet() {}");
  }

  @Test
  void references_methodUsedInAnotherFile_findsUsageWithSnippet() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = positionOf(fx.calleeSource(), "greet");
    final LatheReferences references =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50).value();

    assertThat(references.references())
        .anyMatch(r -> r.uri().endsWith("Caller.java") && r.snippet().contains("greet"));
  }

  @Test
  void callHierarchy_findsIncomingCallerAndOutgoingCallee() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var calleePos = positionOf(fx.calleeSource(), "greet");
    final LatheCallHierarchy incoming =
        engine
            .callHierarchy(
                fx.callee(),
                calleePos.getLine(),
                calleePos.getCharacter(),
                CallDirection.INCOMING,
                SymbolScope.ALL,
                50)
            .value();
    assertThat(incoming.incoming()).isTrue();
    assertThat(incoming.calls())
        .anySatisfy(
            call -> {
              assertThat(call.location().uri()).endsWith("Caller.java");
              assertThat(call.location().snippet()).contains("greet");
            });

    final var callerPos = positionOf(fx.callerSource(), "run");
    final LatheCallHierarchy outgoing =
        engine
            .callHierarchy(
                fx.caller(),
                callerPos.getLine(),
                callerPos.getCharacter(),
                CallDirection.OUTGOING,
                SymbolScope.REACTOR,
                50)
            .value();
    assertThat(outgoing.incoming()).isFalse();
    assertThat(outgoing.calls())
        .as("the call site in the caller, not the callee's declaration")
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.name()).contains("greet");
              assertThat(call.location().uri()).endsWith("Caller.java");
              assertThat(call.location().column()).isEqualTo(fx.callerSource().indexOf("greet()"));
            });
  }

  @Test
  void findImplementations_interface_findsImplementingClass() throws Exception {
    final GreeterFixture greeter = greeterFixture();
    final String ifaceSource = greeter.ifaceSource();
    final Path iface = greeter.iface();
    compileIntoMirror(iface, greeter.impl());
    engine = new LatheEngine(tmp);

    final var pos = positionOf(ifaceSource, "Greeter");
    final LatheImplementations impls =
        engine.findImplementations(iface, pos.getLine(), pos.getCharacter(), 50).value();

    assertThat(impls.implementations())
        .anySatisfy(loc -> assertThat(loc.uri()).endsWith("EnGreeter.java"));
  }

  // Go to implementation finds anonymous classes and enum constant bodies, which the type index
  // knows only as Outer$1, and on a functional interface its lambdas beside its classes.
  @Test
  void findImplementations_anonymousEnumBodyAndLambdas_foundForMethodAndType() throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Managed",
                "package com.example;\ninterface Managed {\n  void start();\n  void stop();\n}\n",
            "Impl",
                "package com.example;\nclass Impl implements Managed {\n  public void start() {}\n  public void stop() {}\n}\n",
            "Holder",
                "package com.example;\nclass Holder {\n  Managed managed = new Managed() {\n    public void start() {}\n    public void stop() {}\n  };\n}\n",
            "Color",
                "package com.example;\nenum Color {\n  RED {\n    String label() { return \"r\"; }\n  };\n  abstract String label();\n}\n",
            "Handler",
                "package com.example;\ninterface Handler {\n  void handle(String request);\n}\n",
            "Echo",
                "package com.example;\nclass Echo implements Handler {\n  public void handle(String request) {}\n}\n",
            "Routes",
                "package com.example;\nclass Routes {\n  Handler handler = request -> {};\n  Runnable other = () -> {};\n}\n");
    final Map<String, Path> files = new HashMap<>();
    for (final var source : sources.entrySet()) {
      files.put(
          source.getKey(),
          TestCompiler.writeModuleSource(
              tmp, "com/example/%s.java".formatted(source.getKey()), source.getValue()));
    }

    TestJavac.compileToDir(
        tmp.resolve(".lathe/module/classes"), files.values().toArray(Path[]::new));
    engine = new LatheEngine(tmp);
    final BiFunction<String, String, List<String>> found =
        (type, token) -> {
          final var pos = positionOf(sources.get(type), token);
          return engine
              .findImplementations(files.get(type), pos.getLine(), pos.getCharacter(), 50)
              .value()
              .implementations()
              .stream()
              .map(
                  location ->
                      "%s:%d"
                          .formatted(Path.of(location.path()).getFileName(), location.line() + 1))
              .sorted()
              .toList();
        };

    assertThat(found.apply("Managed", "stop")).containsExactly("Holder.java:5", "Impl.java:4");
    assertThat(found.apply("Managed", "Managed")).containsExactly("Holder.java:3", "Impl.java:2");
    assertThat(found.apply("Color", "label();")).containsExactly("Color.java:4");
    assertThat(found.apply("Color", "Color {")).containsExactly("Color.java:3");
    assertThat(found.apply("Handler", "Handler")).containsExactly("Echo.java:2", "Routes.java:3");
  }

  // At the name of a constant with a body, hover describes the constant and definition goes to it,
  // as at a constant without one.
  @Test
  void describeAndDefinition_enumConstantWithBody_resolveTheConstant() throws Exception {
    final String source =
        "package com.example;\nenum Color {\n  RED { String label() { return \"r\"; } },\n  GREEN;\n  String label() { return \"g\"; }\n}\n";
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Color.java", source);
    compileIntoMirror(file);
    engine = new LatheEngine(tmp);

    for (final String name : List.of("RED", "GREEN")) {
      final var pos = offsetToPosition(source, source.indexOf(name) + 1);

      assertThat(engine.describe(file, pos.getLine(), pos.getCharacter()).value())
          .as(name)
          .contains(name);
      assertThat(engine.definition(file, pos.getLine(), pos.getCharacter()).value())
          .as(name)
          .singleElement()
          .satisfies(location -> assertThat(location.line()).isEqualTo(pos.getLine()));
    }
  }

  @Test
  void describe_method_returnsSignatureMarkdown() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = positionOf(fx.calleeSource(), "greet");
    final String markup = engine.describe(fx.callee(), pos.getLine(), pos.getCharacter()).value();

    assertThat(markup).contains("greet");
  }

  // Line 2 is "  void greet() {}".
  @Test
  void onName_positionsAroundAMethod_trueOnlyOnANameNotAKeywordBlankOrPastTheEnd()
      throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    assertThat(onName(fx.callee(), 2, 7)).as("the first letter of greet").isTrue();
    assertThat(onName(fx.callee(), 2, 11)).as("its last letter").isTrue();
    assertThat(onName(fx.callee(), 1, 6)).as("Callee").isTrue();
    assertThat(onName(fx.callee(), 2, 2)).as("the keyword void").isFalse();
    assertThat(onName(fx.callee(), 2, 0)).as("indentation").isFalse();
    assertThat(onName(fx.callee(), 2, 12)).as("the parenthesis").isFalse();
    assertThat(onName(fx.callee(), 2, 40)).as("past the line's end").isFalse();
    assertThat(onName(fx.callee(), 9, 0)).as("past the file's end").isFalse();
  }

  // A word in a comment or a string looks like a name, but javac would answer there for the
  // enclosing class, method or variable.
  @Test
  void onName_wordsInCommentsAndStrings_falseButTrueOnANameAndAJavadocLink() throws Exception {
    final String javadoc = "/** Greets, see {@link Object}. */";
    final String method = "  String greet() { return \"greet the world\"; }";
    final Path file =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Words.java",
            String.join(
                "\n",
                "package com.example;",
                "",
                javadoc,
                "class Words {",
                "  // greet the world",
                method,
                "}",
                ""));
    engine = new LatheEngine(tmp);

    assertThat(onName(file, 2, javadoc.indexOf("Greets"))).as("Javadoc text").isFalse();
    assertThat(onName(file, 2, javadoc.indexOf("Object"))).as("a {@link}").isTrue();
    assertThat(onName(file, 4, 5)).as("a line comment").isFalse();
    assertThat(onName(file, 5, method.indexOf("greet"))).as("the method's name").isTrue();
    assertThat(onName(file, 5, method.indexOf("\"greet") + 1)).as("a string").isFalse();
  }

  @Test
  void describe_methodInADependencySource_returnsSignatureAndJavadoc() throws Exception {
    final Path sourceDir = tmp.resolve("sources/lib");
    final String source =
        """
        package com.lib;

        public class Lib {
          /** Doubles the given amount. */
          public int twice(int amount) {
            return amount * 2;
          }
        }
        """;
    final Path lib = sourceDir.resolve("com/lib/Lib.java");
    Files.createDirectories(lib.getParent());
    Files.writeString(lib, source);
    Files.createDirectories(tmp.resolve(LatheLayout.LATHE_DIR));
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            tmp.toString(),
            null,
            List.of(),
            null,
            List.of(
                new DependencyData(
                    "com.lib:lib:1",
                    tmp.resolve("repo/lib.jar").toString(),
                    SourceStatus.PRESENT,
                    sourceDir.toString(),
                    List.of(),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            null),
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON));
    engine = new LatheEngine(tmp);

    final var pos = positionOf(source, "twice");

    assertThat(engine.describe(lib, pos.getLine(), pos.getCharacter()).value())
        .contains("twice(int amount)")
        .contains("Doubles the given amount.");
    assertThat(engine.diagnostics(lib).value())
        .as("a dependency's diagnostics are not the agent's")
        .isEmpty();
  }

  @Test
  void searchSymbolsAndFindImplementations_typeAddedOnDiskMidSession_foundByTheNextQuery()
      throws Exception {
    final GreeterFixture greeter = greeterFixture();
    final String ifaceSource = greeter.ifaceSource();
    final Path iface = greeter.iface();
    final Path impl = greeter.impl();
    compileIntoMirror(iface, impl);
    stampModule(iface, impl);
    engine = new LatheEngine(tmp);
    assertThat(engine.searchSymbols("FrGreeter", SymbolScope.ALL, 50).value()).isEmpty();

    // An agent writes a new implementation with its own file tool, then queries at once.
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/FrGreeter.java",
        "package com.example; class FrGreeter implements Greeter { public void greet() {} }");

    assertThat(engine.searchSymbols("FrGreeter", SymbolScope.ALL, 50).value())
        .anySatisfy(symbol -> assertThat(symbol.name()).isEqualTo("FrGreeter"));
    final var pos = positionOf(ifaceSource, "Greeter");
    assertThat(
            engine
                .findImplementations(iface, pos.getLine(), pos.getCharacter(), 50)
                .value()
                .implementations())
        .anySatisfy(loc -> assertThat(loc.uri()).endsWith("FrGreeter.java"));
  }

  @Test
  void searchSymbols_byName_findsReactorType() throws Exception {
    greetFixture();
    engine = new LatheEngine(tmp);

    final List<LatheSymbol> symbols = engine.searchSymbols("Callee", SymbolScope.ALL, 50).value();

    assertThat(symbols).anySatisfy(symbol -> assertThat(symbol.name()).contains("Callee"));
    assertThat(engine.searchSymbols("*Callee*", SymbolScope.REACTOR, 50).value())
        .as("a glob, as agents write by habit")
        .extracting(LatheSymbol::name)
        .contains("Callee");
    assertThat(engine.searchSymbols("*", SymbolScope.ALL, 50).value()).isEmpty();
  }

  // No project code can use a type in a JDK package its module does not export to everyone.
  @Test
  void jdkInternal_containers_onlyTheJdksUnexportedPackages() {
    assertThat(LatheEngine.jdkInternal("com.sun.tools.javac.comp")).isTrue();
    assertThat(LatheEngine.jdkInternal("com.sun.tools.javac.util.JCDiagnostic"))
        .as("an enclosing type in an internal package")
        .isTrue();
    assertThat(LatheEngine.jdkInternal("com.sun.source.tree")).as("exported").isFalse();
    assertThat(LatheEngine.jdkInternal("java.util.Map")).isFalse();
    assertThat(LatheEngine.jdkInternal("com.example.app")).as("not the JDK's").isFalse();
    assertThat(LatheEngine.jdkInternal(null)).isFalse();
  }

  @Test
  void references_queriedFileThenEditedOnDisk_findsTheNewUse() throws Exception {
    final GreetFixture fx = crossPackageGreetFixture();
    engine = new LatheEngine(tmp);
    final var pos = positionOf(fx.calleeSource(), "greet");
    engine.diagnostics(fx.caller()).value();
    final int before =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50).value().total();

    Files.writeString(
        fx.caller(), fx.callerSource().replace("c.greet();", "c.greet(); c.greet();"));

    assertThat(
            engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50).value().total())
        .isEqualTo(before + 1);
  }

  // Whether a position tool answers at a position, rather than refusing it as not on a name.
  private boolean onName(final Path file, final int line, final int column) {
    try {
      engine.describe(file, line, column).value();
      return true;
    } catch (final LatheEngine.NotOnName e) {
      return false;
    }
  }

  // A nested type is found by its binary name (Outer$Inner): from the same package, and from
  // another package that imports only the outer type and spells it Outer.Inner.
  @Test
  void references_nestedType_findsUsesInItsPackageAndInAPackageImportingTheOuterType()
      throws Exception {
    final NestedFixture fx = nestedFixture();
    engine = new LatheEngine(tmp);

    final var pos = positionOf(fx.outerSource(), "Inner");
    final LatheReferences references =
        engine.references(fx.outer(), pos.getLine(), pos.getCharacter(), 50).value();

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("User.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("Other.java"));
  }

  @Test
  void findImplementations_nestedInterface_findsTheImplementingClass() throws Exception {
    final NestedFixture fx = nestedFixture();
    engine = new LatheEngine(tmp);

    final var pos = positionOf(fx.outerSource(), "Listener");
    final LatheImplementations implementations =
        engine.findImplementations(fx.outer(), pos.getLine(), pos.getCharacter(), 50).value();

    assertThat(implementations.implementations())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Other.java"));
  }

  @Test
  void outline_typeWithNestedType_listsEveryDeclarationAtItsDepthWithItsLine() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Shop.java",
            """
            package com.example;

            public class Shop {
              private int stock = 3;

              public Shop() {}

              int sell(int count) {
                return stock -= count;
              }

              record Item(String name) {}
            }
            """);
    engine = new LatheEngine(tmp);

    final List<LatheOutlineItem> outline = engine.outline(source).value();

    assertThat(outline)
        .extracting(
            LatheOutlineItem::kind,
            LatheOutlineItem::name,
            LatheOutlineItem::depth,
            item -> item.location().line() + 1,
            item -> item.location().snippet())
        .containsExactly(
            tuple("class", "Shop", 0, 3, "public class Shop {"),
            tuple("field", "stock", 1, 4, "private int stock = 3;"),
            tuple("constructor", "Shop", 1, 6, "public Shop() {}"),
            tuple("method", "sell", 1, 8, "int sell(int count) {"),
            tuple("struct", "Item", 1, 12, "record Item(String name) {}"),
            tuple("field", "name", 2, 12, "record Item(String name) {}"));
    assertThat(outline)
        .allSatisfy(
            item ->
                assertThat(item.location().path())
                    .isEqualTo(FileUtil.slashed(tmp.relativize(source))));
  }
}
