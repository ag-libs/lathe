package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.Positions.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.LatheEngine.CallDirection;
import io.github.aglibs.lathe.server.LatheEngine.SymbolScope;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheDefinition;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheImplementations;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheOutlineItem;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import io.github.aglibs.lathe.server.engine.LatheSymbol;
import io.github.aglibs.lathe.server.engine.Noted;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    assertThat(engine.definition(caller, pos.getLine(), pos.getCharacter()).value().targets())
        .singleElement()
        .satisfies(
            target -> {
              assertThat(target.uri()).isEqualTo(widget.toUri().toString());
              assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
              assertThat(target.snippet())
                  .isEqualTo(implicit ? declaration : "Widget(String value) {}");
            });
  }

  // After a refactoring changed resize's parameter, the call site not yet updated still leads to
  // the method; an untouched call to another method still resolves to it alone.
  @Test
  void definition_callWithMismatchedArguments_returnsTheMethodInAnotherFile() throws Exception {
    final String declaration =
        """
        package com.example;
        class Widget {
          void resize(String size) {}
          void show() {}
        }
        """;
    final String source =
        "package com.example; class Caller { void run(Widget w) { w.resize(42); w.show(); } }";
    final Path widget = TestCompiler.writeModuleSource(tmp, "com/example/Widget.java", declaration);
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", source);
    compileIntoMirror(widget);
    engine = new LatheEngine(tmp);

    for (final String call : List.of("resize", "show")) {
      final var pos = positionOf(source, call);
      assertThat(engine.definition(caller, pos.getLine(), pos.getCharacter()).value().targets())
          .singleElement()
          .satisfies(
              target -> {
                assertThat(target.uri()).isEqualTo(widget.toUri().toString());
                assertThat(target.snippet()).contains("void %s(".formatted(call));
              });
    }
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

  // javac stops at 100 errors a task unless told otherwise; an analysis reports every one.
  @Test
  void diagnostics_moreThanAHundredErrors_reportsEveryOne() throws Exception {
    final String fields =
        IntStream.range(0, 120)
            .mapToObj("  int n%d = \"nope\";"::formatted)
            .collect(Collectors.joining("\n"));
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sample.java",
            "package com.example;\nclass Sample {\n%s\n}\n".formatted(fields));
    engine = new LatheEngine(tmp);

    assertThat(engine.diagnostics(source).value()).hasSize(120);
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
        engine.definition(fx.caller(), pos.getLine(), pos.getCharacter()).value().targets();

    assertThat(targets).hasSize(1);
    final LatheLocation target = targets.get(0);
    assertThat(target.uri()).endsWith("Callee.java");
    assertThat(target.origin()).isEqualTo(LatheLocation.Origin.REACTOR);
    assertThat(target.path()).isEqualTo(FileUtil.slashed(tmp.relativize(fx.callee())));
    assertThat(target.snippet()).as("the target line alone").isEqualTo("void greet() {}");
  }

  // MC-102: a qualified name stands at its last segment that is a name: a module name in a
  // requires, a wildcard import, a class literal.
  @Test
  void nameColumns_qualifiedName_columnOfItsLastNamedSegment() throws Exception {
    final Path moduleInfo =
        Files.writeString(
            tmp.resolve("module-info.java"),
            "module app {\n  requires io.helidon.builder.api;\n}\n");
    engine = new LatheEngine(tmp);

    assertThat(engine.nameColumns(moduleInfo, 1, "io.helidon.builder.api")).containsExactly(30);
    assertThat(engine.nameColumns(moduleInfo, 1, "builder.api")).containsExactly(30);
    assertThat(engine.nameColumns(moduleInfo, 1, "io.helidon.common.api")).isEmpty();
    assertThat(engine.nameColumns(moduleInfo, 1, "helidon.builder.ap")).isEmpty();
    final Path imports =
        Files.writeString(
            tmp.resolve("Imports.java"),
            "import java.util.*;\nclass Imports { Class<?> type = String.class; }\n");
    assertThat(engine.nameColumns(imports, 0, "java.util.*")).containsExactly(12);
    assertThat(engine.nameColumns(imports, 1, "String.class")).containsExactly(32);
  }

  @Test
  void references_methodUsedInAnotherFile_findsUsageWithSnippet() throws Exception {
    final GreetFixture fx = greetFixture();
    engine = new LatheEngine(tmp);

    final var pos = positionOf(fx.calleeSource(), "greet");
    final LatheReferences references =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .anyMatch(
            r ->
                r.location().uri().endsWith("Caller.java")
                    && r.location().snippet().contains("greet"));
  }

  // FR-038: references to an override are the calls that can reach it, through its class, a
  // subclass or a supertype it overrides; not every call of the method it overrides, as the
  // calls on an unrelated class that overrides it too can never reach it.
  @Test
  void references_overrideOfObjectMethod_omitsCallsOnUnrelatedReceivers() throws Exception {
    final String namedSource =
        """
        package com.example;
        class Named {
          @Override
          public String toString() { return "n"; }
          @Override
          public boolean equals(Object other) { return other == this; }
        }
        """;
    final Path named = TestCompiler.writeModuleSource(tmp, "com/example/Named.java", namedSource);
    final Path pet =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Pet.java", "package com.example; class Pet extends Named {}");
    final Path creator =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Creator.java",
            "package com.example; class Creator { @Override public String toString() { return \"c\"; } }");
    final String userSource =
        """
        package com.example;
        class User {
          void use(Named n, Pet p, Object o, java.time.LocalDate d, java.nio.file.Path path) {
            n.toString();
            p.toString();
            o.toString();
            d.toString();
            path.toString();
            new Creator().toString();
            n.equals(o);
            o.equals(n);
            path.equals(o);
            "s".equals(o);
          }
        }
        """;
    final Path user = TestCompiler.writeModuleSource(tmp, "com/example/User.java", userSource);
    compileIntoMirror(named, pet, creator, user);
    engine = new LatheEngine(tmp);
    final var toString = positionOf(namedSource, "toString");

    final LatheReferences references =
        engine
            .references(named, toString.getLine(), toString.getCharacter(), 50, ANY_ACCESS)
            .value();

    assertThat(references.references())
        .extracting(
            reference -> new Position(reference.location().line(), reference.location().column()))
        .containsExactlyInAnyOrder(
            SampleFixture.posOf(userSource, "n.toString", "toString"),
            SampleFixture.posOf(userSource, "p.toString", "toString"),
            SampleFixture.posOf(userSource, "o.toString", "toString"));
    final var equals = positionOf(namedSource, "equals");
    assertThat(
            engine
                .references(named, equals.getLine(), equals.getCharacter(), 50, ANY_ACCESS)
                .value()
                .references())
        .extracting(
            reference -> new Position(reference.location().line(), reference.location().column()))
        .containsExactlyInAnyOrder(
            SampleFixture.posOf(userSource, "n.equals", "equals"),
            SampleFixture.posOf(userSource, "o.equals", "equals"));
  }

  // The same for an interface the project declares: a call on a sibling implementation never
  // reaches this one, while a call through the interface may.
  @Test
  void references_implementationOfAnInterface_omitsCallsOnASiblingImplementation()
      throws Exception {
    final Path service =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Service.java",
            "package com.example; interface Service { void handle(); }");
    final String implSource =
        """
        package com.example;
        class Impl implements Service {
          public void handle() {}
        }
        """;
    final Path impl = TestCompiler.writeModuleSource(tmp, "com/example/Impl.java", implSource);
    final Path other =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Other.java",
            "package com.example; class Other implements Service { public void handle() {} }");
    final String userSource =
        """
        package com.example;
        class User {
          void use(Service s, Impl i, Other o) {
            s.handle();
            i.handle();
            o.handle();
          }
        }
        """;
    final Path user = TestCompiler.writeModuleSource(tmp, "com/example/User.java", userSource);
    compileIntoMirror(service, impl, other, user);
    engine = new LatheEngine(tmp);
    final var handle = positionOf(implSource, "handle");

    final LatheReferences references =
        engine.references(impl, handle.getLine(), handle.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .extracting(
            reference -> new Position(reference.location().line(), reference.location().column()))
        .containsExactlyInAnyOrder(
            SampleFixture.posOf(userSource, "s.handle", "handle"),
            SampleFixture.posOf(userSource, "i.handle", "handle"));
  }

  // A call through the interface in a module upstream of the implementation reaches it at run
  // time: the modules searched are the interface's, the calls kept the implementation's.
  @Test
  void references_implementationInADownstreamModule_listsTheCallThroughTheInterfaceUpstream()
      throws Exception {
    TestCompiler.writeMirroredModule(
        tmp,
        "api",
        Map.of(
            "com/example/api/Service.java",
            "package com.example.api; public interface Service { void handle(); }",
            "com/example/api/Runner.java",
            "package com.example.api; class Runner { void run(Service s) { s.handle(); } }"),
        List.of(),
        1_000L);
    final String implSource =
        """
        package com.example.impl;
        class Impl implements com.example.api.Service {
          public void handle() {}
        }
        """;
    final Path impl =
        TestCompiler.writeMirroredModule(
                tmp,
                "impl",
                Map.of("com/example/impl/Impl.java", implSource),
                List.of("api"),
                1_000L)
            .get(0);
    engine = new LatheEngine(tmp);
    final var handle = positionOf(implSource, "handle");

    final LatheReferences references =
        engine.references(impl, handle.getLine(), handle.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .extracting(reference -> Path.of(reference.location().path()).getFileName().toString())
        .containsExactly("Runner.java");
  }

  // A method of a class with no name in source, here an enum constant's body, is called only
  // through a supertype, and those calls are its references.
  @Test
  void references_methodOfAnEnumConstantBody_listsTheCallsThroughTheEnum() throws Exception {
    final String opSource =
        """
        package com.example;
        enum Op {
          PLUS {
            int apply(int a, int b) { return a + b; }
          };
          abstract int apply(int a, int b);
        }
        """;
    final Path op = TestCompiler.writeModuleSource(tmp, "com/example/Op.java", opSource);
    final String userSource =
        "package com.example; class User { int use(Op op) { return op.apply(1, 2); } }";
    final Path user = TestCompiler.writeModuleSource(tmp, "com/example/User.java", userSource);
    compileIntoMirror(op, user);
    engine = new LatheEngine(tmp);
    final var apply = positionOf(opSource, "apply");

    final LatheReferences references =
        engine.references(op, apply.getLine(), apply.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .extracting(
            reference -> new Position(reference.location().line(), reference.location().column()))
        .containsExactly(SampleFixture.posOf(userSource, "op.apply", "apply"));
  }

  // An override written since the last compile is not in the class files other files are checked
  // against; its class is, so the calls that can reach it are still found.
  @Test
  void references_overrideNotCompiledYet_listsTheCallsThroughItsClassAndObject() throws Exception {
    final Path named =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Named.java", "package com.example; class Named {}");
    final String userSource =
        """
        package com.example;
        class User {
          void use(Named n, Object o, java.time.LocalDate d) {
            n.toString();
            o.toString();
            d.toString();
          }
        }
        """;
    final Path user = TestCompiler.writeModuleSource(tmp, "com/example/User.java", userSource);
    compileIntoMirror(named, user);
    final String namedSource =
        """
        package com.example;
        class Named {
          @Override
          public String toString() { return "n"; }
        }
        """;
    Files.writeString(named, namedSource);
    engine = new LatheEngine(tmp);
    final var toString = positionOf(namedSource, "toString");

    final LatheReferences references =
        engine
            .references(named, toString.getLine(), toString.getCharacter(), 50, ANY_ACCESS)
            .value();

    assertThat(references.references())
        .extracting(
            reference -> new Position(reference.location().line(), reference.location().column()))
        .containsExactlyInAnyOrder(
            SampleFixture.posOf(userSource, "n.toString", "toString"),
            SampleFixture.posOf(userSource, "o.toString", "toString"));
  }

  // MC-101: a declaration is no use, neither the method's own nor an override's, so an unused
  // method has none; a call through the override is a use (FR-006).
  @Test
  void references_overriddenMethod_listsTheCallNotTheDeclarations() throws Exception {
    final String baseSource =
        """
        package com.example;
        class Base {
          void hook() {}
          void idle() {}
        }
        """;
    final Path base = TestCompiler.writeModuleSource(tmp, "com/example/Base.java", baseSource);
    final Path sub =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sub.java",
            "package com.example; class Sub extends Base { @Override void hook() {} }");
    final Path user =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/User.java",
            "package com.example; class User { void use(Base b, Sub s) { b.hook(); s.hook(); } }");
    compileIntoMirror(base, sub, user);
    engine = new LatheEngine(tmp);
    final var hook = positionOf(baseSource, "hook");
    final var idle = positionOf(baseSource, "idle");

    final LatheReferences references =
        engine.references(base, hook.getLine(), hook.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.total()).isEqualTo(2);
    assertThat(references.references())
        .extracting(reference -> reference.location().uri())
        .containsOnly(user.toUri().toString());
    final LatheReferences unused =
        engine.references(base, idle.getLine(), idle.getCharacter(), 50, ANY_ACCESS).value();
    assertThat(unused.total()).isZero();
    assertThat(unused.resolved()).as("an unused method is still a symbol").isTrue();
    final String looseSource = "package com.example; class Loose { void idle() {} }";
    final Path loose = TestCompiler.writeAt(tmp.resolve("scratch/Loose.java"), looseSource, 1_000L);
    final var outside = positionOf(looseSource, "idle");
    assertThat(
            engine
                .references(loose, outside.getLine(), outside.getCharacter(), 50, ANY_ACCESS)
                .value()
                .resolved())
        .as("a file no module compiles names no symbol Lathe can search")
        .isFalse();
  }

  // An override's callers include calls through the method it overrides from upstream, where its
  // class is not on the classpath: in the base's own module, and in a module depending only on that
  // one; a call on a receiver that cannot hold the override (a sibling subclass) is left out.
  @Test
  void callHierarchy_overrideOfAnUpstreamMethod_findsCallersThroughItAcrossModules()
      throws Exception {
    TestCompiler.writeMirroredModule(
        tmp,
        "api",
        Map.of(
            "com/example/api/Plugin.java",
            "package com.example.api;\npublic abstract class Plugin {\n"
                + "  public void init() {}\n  public final void boot() { init(); }\n}\n",
            "com/example/api/Other.java",
            "package com.example.api;\npublic abstract class Other extends Plugin {}\n",
            "com/example/api/Host.java",
            "package com.example.api;\npublic class Host {\n"
                + "  public void run(Plugin p) { p.init(); }\n"
                + "  public void start(Other o) { o.init(); }\n}\n"),
        List.of(),
        1_000L);
    TestCompiler.writeMirroredModule(
        tmp,
        "tool",
        Map.of(
            "com/example/tool/Runner.java",
            "package com.example.tool;\nimport com.example.api.Plugin;\n"
                + "class Runner { void go(Plugin p) { p.init(); } }\n"),
        List.of("api"),
        1_000L);
    final String overrideSource =
        "package com.example.app;\nclass MyPlugin extends com.example.api.Plugin {\n"
            + "  @Override public void init() {}\n}\n";
    final Path override =
        TestCompiler.writeMirroredModule(
                tmp,
                "app",
                Map.of("com/example/app/MyPlugin.java", overrideSource),
                List.of("api"),
                1_000L)
            .get(0);
    engine = new LatheEngine(tmp);
    final var pos = positionOf(overrideSource, "init");

    final LatheCallHierarchy incoming =
        engine
            .callHierarchy(
                override,
                pos.getLine(),
                pos.getCharacter(),
                CallDirection.INCOMING,
                SymbolScope.ALL,
                50)
            .value();

    assertThat(incoming.calls())
        .extracting(call -> call.name())
        .containsExactlyInAnyOrder("boot", "run", "go");
  }

  // A capped answer keeps what the agent edits: the project's own sources come before generated
  // ones, though a generated file's path sorts first and its type's name matches exactly, in
  // references, calls and types.
  @Test
  void referencesAndCallers_handWrittenBeforeGenerated_cappedToTheHandWritten() throws Exception {
    final GreetFixture fx = greetFixture();
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/Call.java"),
            "package com.example; class Call { void help(Callee c) { c.greet(); } }",
            1_000L);
    compileIntoMirror(fx.callee(), fx.caller(), generated);
    engine = new LatheEngine(tmp);
    final var pos = positionOf(fx.calleeSource(), "greet");

    final LatheReferences all =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS).value();
    final LatheReferences capped =
        engine.references(fx.callee(), pos.getLine(), pos.getCharacter(), 1, ANY_ACCESS).value();
    final LatheCallHierarchy callers =
        engine
            .callHierarchy(
                fx.callee(),
                pos.getLine(),
                pos.getCharacter(),
                CallDirection.INCOMING,
                SymbolScope.ALL,
                50)
            .value();

    assertThat(all.references())
        .extracting(reference -> reference.location().uri())
        .containsExactly(fx.caller().toUri().toString(), generated.toUri().toString());
    assertThat(capped.total()).isEqualTo(2);
    assertThat(capped.references())
        .extracting(reference -> reference.location().origin())
        .containsOnly(LatheLocation.Origin.REACTOR);
    assertThat(callers.calls()).extracting(call -> call.name()).containsExactly("run", "help");
    assertThat(engine.searchSymbols("Cal", SymbolScope.REACTOR, 50).value())
        .extracting(LatheSymbol::name)
        .containsExactly("Callee", "Caller", "Call");
    assertThat(engine.searchSymbols("Call", SymbolScope.REACTOR, 50).value())
        .as("the type the query names comes first, generated or not (MC-110)")
        .extracting(LatheSymbol::name)
        .containsExactly("Call", "Callee", "Caller");
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

  // Types declared in a file named after none of them: found in that file by symbol search,
  // definition from another file and implementations, as the file's own type is.
  @Test
  void secondaryTopLevelTypes_searchDefinitionAndImplementations_findTheirFile() throws Exception {
    final String hostSource =
        """
        package com.example;
        class Host {}
        interface Delayed {}
        class FirstDelayed implements Delayed {}
        class SecondDelayed implements Delayed {}
        """;
    final String callerSource = "package com.example; class Caller { Delayed delayed; }";
    final Path host = TestCompiler.writeModuleSource(tmp, "com/example/Host.java", hostSource);
    final Path caller =
        TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", callerSource);
    compileIntoMirror(host, caller);
    engine = new LatheEngine(tmp);
    final String hostUri = host.toUri().toString();

    assertThat(engine.searchSymbols("Delayed", SymbolScope.REACTOR, 50).value())
        .extracting(symbol -> symbol.name(), symbol -> symbol.location().uri())
        .containsExactlyInAnyOrder(
            tuple("Delayed", hostUri),
            tuple("FirstDelayed", hostUri),
            tuple("SecondDelayed", hostUri));
    assertThat(engine.searchSymbols("Host", SymbolScope.REACTOR, 50).value())
        .extracting(symbol -> symbol.location().uri())
        .containsExactly(hostUri);

    final var use = positionOf(callerSource, "Delayed");
    assertThat(engine.definition(caller, use.getLine(), use.getCharacter()).value().targets())
        .singleElement()
        .satisfies(
            target -> {
              assertThat(target.uri()).isEqualTo(hostUri);
              assertThat(target.snippet()).isEqualTo("interface Delayed {}");
            });

    final var declaration = positionOf(hostSource, "Delayed {}");
    assertThat(
            engine
                .findImplementations(host, declaration.getLine(), declaration.getCharacter(), 50)
                .value()
                .implementations())
        .extracting(location -> location.snippet())
        .containsExactlyInAnyOrder(
            "class FirstDelayed implements Delayed {}",
            "class SecondDelayed implements Delayed {}");
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
      assertThat(engine.definition(file, pos.getLine(), pos.getCharacter()).value().targets())
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

  // A type from a dependency without sources has no file, but it exists: the search lists it by
  // its dependency, so an agent does not conclude it does not.
  @Test
  void searchSymbols_typeFromADependencyWithoutSources_listedWithItsDependency() throws Exception {
    TestCompiler.writeModuleSource(
        tmp, "com/example/Caller.java", "package com.example; class Caller { com.lib.Lib lib; }");
    TestCompiler.recordSourcelessDependency(
        tmp, "com.lib:lib:1", Map.of("com/lib/Lib.java", "package com.lib; public class Lib {}"));
    engine = new LatheEngine(tmp);

    assertThat(engine.searchSymbols("Lib", SymbolScope.ALL, 50).value())
        .filteredOn(symbol -> symbol.name().equals("Lib"))
        .singleElement()
        .satisfies(
            symbol -> {
              assertThat(symbol.container()).isEqualTo("com.lib");
              assertThat(symbol.sourcelessDependency()).hasValue("com.lib:lib:1");
              assertThat(symbol.location()).isNull();
            });
    assertThat(engine.searchSymbols("Lib", SymbolScope.REACTOR, 50).value())
        .as("not the reactor's")
        .extracting(LatheSymbol::name)
        .doesNotContain("Lib");
  }

  // MC-110: a generated type the query names comes before hand-written ones that only start with
  // it, as Helidon's generated ListenerConfig before its blueprint; the origin order ranks the
  // rest.
  @Test
  void searchSymbols_generatedExactMatch_listedBeforeHandWrittenPartialMatches() throws Exception {
    final Path blueprint =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/ConfigBlueprint.java",
            "package com.example; interface ConfigBlueprint {}");
    final Path test =
        TestCompiler.writeModuleSource(
            tmp, "com/example/ConfigTest.java", "package com.example; class ConfigTest {}");
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/Config.java"),
            """
            package com.example;
            public interface Config extends ConfigBlueprint {
              class Builder {}
            }
            """,
            1_000L);
    compileIntoMirror(blueprint, test, generated);
    engine = new LatheEngine(tmp);

    assertThat(engine.searchSymbols("Config", SymbolScope.REACTOR, 50).value())
        .extracting(LatheSymbol::name)
        .startsWith("Config")
        .contains("ConfigBlueprint", "ConfigTest");
    assertThat(engine.searchSymbols("com.example.Config", SymbolScope.REACTOR, 50).value())
        .as("a qualified name")
        .extracting(LatheSymbol::name)
        .startsWith("Config");
    assertThat(engine.searchSymbols("Config.Builder", SymbolScope.REACTOR, 50).value())
        .as("a nested type by its enclosing type")
        .extracting(LatheSymbol::name)
        .startsWith("Builder");
    assertThat(engine.searchSymbols("Conf", SymbolScope.REACTOR, 50).value())
        .as("no exact match: hand-written first")
        .extracting(LatheSymbol::name)
        .containsExactlyInAnyOrder("ConfigBlueprint", "ConfigTest", "Config")
        .endsWith("Config");
  }

  // MC-113: a name equal to the query but for case is the type it names, generated or not.
  @Test
  void searchSymbols_exactNameInLowerCase_listsTheGeneratedTypeFirst() throws Exception {
    engine = listenerConfigEngine();

    assertThat(engine.searchSymbols("listenerconfig", SymbolScope.REACTOR, 50).value())
        .extracting(LatheSymbol::name)
        .startsWith("ListenerConfig");
    assertThat(engine.searchSymbols("com.example.listenerconfig", SymbolScope.REACTOR, 50).value())
        .as("a qualified name")
        .extracting(LatheSymbol::name)
        .startsWith("ListenerConfig");
  }

  @Test
  void searchSymbols_exactNameInLowerCase_handWrittenExactMatchStillFirst() throws Exception {
    engine = listenerConfigEngine();

    assertThat(engine.searchSymbols("listenerconfigblueprint", SymbolScope.REACTOR, 50).value())
        .extracting(LatheSymbol::name)
        .startsWith("ListenerConfigBlueprint");
  }

  // MC-113: a generated type ranks by how well its name matches, not after every hand-written one.
  @Test
  void searchSymbols_generatedTypeMatchesBetter_rankedBeforeWeakerHandWrittenMatch()
      throws Exception {
    engine = listenerConfigEngine();

    final List<String> names =
        engine.searchSymbols("LiCo", SymbolScope.REACTOR, 50).value().stream()
            .map(LatheSymbol::name)
            .toList();

    assertThat(names).contains("ListenerConfig", "LinkedListConverter");
    assertThat(names.indexOf("ListenerConfig"))
        .as("initials on consecutive words match better than across a skipped one")
        .isLessThan(names.indexOf("LinkedListConverter"));
  }

  // MC-113: ignoring case finds both, but the type named in the query's own case comes first.
  @Test
  void searchSymbols_namesDifferOnlyInCase_exactCaseFirst() throws Exception {
    final Path upper =
        TestCompiler.writeModuleSource(tmp, "com/a/URL.java", "package com.a; class URL {}");
    final Path lower =
        TestCompiler.writeModuleSource(tmp, "com/b/Url.java", "package com.b; class Url {}");
    compileIntoMirror(upper, lower);
    engine = new LatheEngine(tmp);

    assertThat(engine.searchSymbols("URL", SymbolScope.REACTOR, 50).value())
        .extracting(LatheSymbol::name)
        .containsExactly("URL", "Url");
    assertThat(engine.searchSymbols("Url", SymbolScope.REACTOR, 50).value())
        .extracting(LatheSymbol::name)
        .containsExactly("Url", "URL");
  }

  // A hand-written blueprint and a weaker match beside the generated ListenerConfig, as Helidon
  // declares its builders.
  private LatheEngine listenerConfigEngine() throws Exception {
    final Path blueprint =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/ListenerConfigBlueprint.java",
            "package com.example; interface ListenerConfigBlueprint {}");
    final Path weaker =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/LinkedListConverter.java",
            "package com.example; class LinkedListConverter {}");
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/ListenerConfig.java"),
            "package com.example; public interface ListenerConfig extends ListenerConfigBlueprint {}",
            1_000L);
    compileIntoMirror(blueprint, weaker, generated);
    return new LatheEngine(tmp);
  }

  // MC-100: a capped list lists main sources before tests, the declaring module first in each,
  // where a sibling module's name sorts before it (`app-26` before `app-core`).
  @Test
  void findImplementations_siblingModuleSortsFirst_listsMainBeforeTestsDeclaringModuleFirst()
      throws Exception {
    final String apiSource = "package com.example; public interface Api { void run(); }";
    final List<Path> core =
        TestCompiler.writeMirroredModule(
            tmp,
            "app-core",
            Map.of(
                "com/example/Api.java",
                apiSource,
                "com/example/CoreImpl.java",
                "package com.example; public class CoreImpl implements Api { public void run() {} }"),
            List.of(),
            1_000L);
    TestCompiler.writeMirroredModule(
        tmp,
        "app-26",
        Map.of(
            "com/example/other/Impl26.java",
            "package com.example.other; public class Impl26 implements com.example.Api {"
                + " public void run() {} }"),
        List.of("app-core"),
        1_000L);
    final Path testSource =
        TestCompiler.writeAt(
            tmp.resolve("app-core/src/test/java/com/example/CoreTestImpl.java"),
            "package com.example; class CoreTestImpl implements Api { public void run() {} }",
            1_000L);
    TestCompiler.writeTestTreeParams(tmp, "app-core", tmp.resolve("app-core/src/test/java"), null);
    TestJavac.compileToDir(
        tmp.resolve(LatheLayout.LATHE_DIR)
            .resolve("app-core")
            .resolve(LatheLayout.TEST_CLASSES_DIR),
        List.of(tmp.resolve(LatheLayout.LATHE_DIR).resolve("app-core/classes")),
        testSource);
    engine = new LatheEngine(tmp);
    final Path api =
        core.stream().filter(path -> path.endsWith("Api.java")).findFirst().orElseThrow();
    final var pos = positionOf(apiSource, "Api {");

    assertThat(
            engine
                .findImplementations(api, pos.getLine(), pos.getCharacter(), 50)
                .value()
                .implementations())
        .extracting(
            location -> Path.of(java.net.URI.create(location.uri())).getFileName().toString())
        .containsExactly("CoreImpl.java", "Impl26.java", "CoreTestImpl.java");
  }

  // Asked from a use in the sibling module, a capped list still starts with the declaring module:
  // references and incoming calls of `Api.run`, called from both modules.
  @Test
  void referencesAndCallers_askedFromASiblingModulesUse_listTheDeclaringModuleFirst()
      throws Exception {
    final List<Path> core =
        TestCompiler.writeMirroredModule(
            tmp,
            "app-core",
            Map.of(
                "com/example/Api.java",
                "package com.example; public interface Api { void run(); }",
                "com/example/CoreUse.java",
                "package com.example; public class CoreUse { void go(Api api) { api.run(); } }"),
            List.of(),
            1_000L);
    final String siblingSource =
        "package com.example.other; public class Use26 { void go(com.example.Api api) {"
            + " api.run(); } }";
    final Path sibling =
        TestCompiler.writeMirroredModule(
                tmp,
                "app-26",
                Map.of("com/example/other/Use26.java", siblingSource),
                List.of("app-core"),
                1_000L)
            .get(0);
    engine = new LatheEngine(tmp);
    final var pos = positionOf(siblingSource, "run()");

    assertThat(
            engine
                .references(sibling, pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS)
                .value()
                .references())
        .extracting(reference -> fileName(reference.location()))
        .containsExactly("CoreUse.java", "Use26.java");
    assertThat(
            engine
                .callHierarchy(
                    sibling,
                    pos.getLine(),
                    pos.getCharacter(),
                    CallDirection.INCOMING,
                    SymbolScope.ALL,
                    50)
                .value()
                .calls())
        .extracting(call -> fileName(call.location()))
        .containsExactly("CoreUse.java", "Use26.java");
    assertThat(core).hasSize(2);
  }

  private static String fileName(final LatheLocation location) {
    return Path.of(java.net.URI.create(location.uri())).getFileName().toString();
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
        engine
            .references(fx.callee(), pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS)
            .value()
            .total();

    Files.writeString(
        fx.caller(), fx.callerSource().replace("c.greet();", "c.greet(); c.greet();"));

    assertThat(
            engine
                .references(fx.callee(), pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS)
                .value()
                .total())
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
        engine.references(fx.outer(), pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .extracting(ref -> ref.location().uri())
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
  void outline_typeWithNestedTypes_listsEveryDeclarationAtItsDepthWithItsJavaKind()
      throws Exception {
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

              enum Size { SMALL }
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
            tuple("record", "Item", 1, 12, "record Item(String name) {}"),
            tuple("field", "name", 2, 12, "record Item(String name) {}"),
            tuple("enum", "Size", 1, 14, "enum Size { SMALL }"),
            tuple("enum constant", "SMALL", 2, 14, "enum Size { SMALL }"));
    assertThat(outline)
        .allSatisfy(
            item ->
                assertThat(item.location().path())
                    .isEqualTo(FileUtil.slashed(tmp.relativize(source))));
  }

  @Test
  void outline_moduleInfo_listsDirectivesAsModuleChildrenAtTheirTargets() throws Exception {
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/Task.java",
        "package com.example; public class Task implements Runnable { public void run() {} }");
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "module-info.java",
            """
            module example {
              requires java.logging;
              exports com.example;
              opens com.example;
              uses java.lang.Runnable;
              provides java.lang.Runnable with com.example.Task;
            }
            """);
    engine = new LatheEngine(tmp);

    final List<LatheOutlineItem> outline = engine.outline(source).value();

    assertThat(outline)
        .extracting(
            LatheOutlineItem::kind,
            LatheOutlineItem::name,
            LatheOutlineItem::depth,
            item -> item.location().line(),
            item -> item.location().column())
        .containsExactly(
            tuple("module", "example", 0, 0, 7),
            tuple("module", "requires java.logging", 1, 1, 11),
            tuple("package", "exports com.example", 1, 2, 10),
            tuple("package", "opens com.example", 1, 3, 8),
            tuple("object", "uses java.lang.Runnable", 1, 4, 7),
            tuple("object", "provides java.lang.Runnable", 1, 5, 11));
  }

  // A file's structure needs no compile: a file no module compiles is outlined from a parse, and
  // still noted as not checked.
  @Test
  void outline_fileNoModuleCompiles_listsItsDeclarationsAndSaysNotChecked() throws Exception {
    TestCompiler.writeModuleSource(
        tmp, "com/example/Shop.java", "package com.example; class Shop {}");
    final Path outside =
        TestCompiler.writeAt(
            tmp.resolve("examples/src/main/java/com/example/Book.java"),
            "package com.example;\nclass Book {\n  String title() { return \"\"; }\n}\n",
            1_000L);
    engine = new LatheEngine(tmp);

    final Noted<List<LatheOutlineItem>> outline = engine.outline(outside);

    assertThat(outline.value())
        .extracting(LatheOutlineItem::kind, LatheOutlineItem::name, LatheOutlineItem::depth)
        .containsExactly(tuple("class", "Book", 0), tuple("method", "title", 1));
    assertThat(outline.notes().notChecked()).isNotEmpty();
  }

  // A source outside the workspace is named by its absolute path, which the agent can open and
  // hand back to the tools as it is, not by a file URI.
  @Test
  void definition_jdkTypeWithSources_answersItsAbsolutePath(@TempDir final Path jdkSources)
      throws Exception {
    final String source = "package com.example; class Caller { String text = \"x\"; }";
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", source);
    compileIntoMirror(caller);
    final Path string =
        TestCompiler.writeAt(
            jdkSources.resolve("java.base/java/lang/String.java"),
            "package java.lang;\npublic final class String {}\n",
            1_000L);
    TestCompiler.recordJdk(
        tmp,
        new JdkSourceData(
            "Vendor", "21.0.1", SourceStatus.PRESENT, tmp.resolve("jdk"), null, jdkSources, null));
    engine = new LatheEngine(tmp);
    final var pos = positionOf(source, "String text");

    final LatheDefinition answer =
        engine.definition(caller, pos.getLine(), pos.getCharacter()).value();

    assertThat(answer.targets())
        .singleElement()
        .satisfies(
            target -> {
              assertThat(target.path()).isEqualTo(string.toString());
              assertThat(target.origin()).isEqualTo(LatheLocation.Origin.EXTERNAL);
            });
  }

  // A dependency without sources still declares its types: the answer says where, so an agent does
  // not take the type for one that does not exist.
  @Test
  void definition_typeFromADependencyWithoutSources_namesItsDependency() throws Exception {
    final String source = "package com.example; class Caller { com.lib.Lib lib; }";
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", source);
    TestCompiler.recordSourcelessDependency(
        tmp, "com.lib:lib:1", Map.of("com/lib/Lib.java", "package com.lib; public class Lib {}"));
    engine = new LatheEngine(tmp);
    final var pos = positionOf(source, "Lib lib");

    final LatheDefinition answer =
        engine.definition(caller, pos.getLine(), pos.getCharacter()).value();

    assertThat(answer.targets()).isEmpty();
    assertThat(answer.explanation())
        .hasValue(
            "No source for com.lib.Lib: com.lib:lib:1 has no sources; describe_symbol shows its declaration from the class file.");
    final var none = positionOf(source, "lib;");
    assertThat(engine.definition(caller, none.getLine(), none.getCharacter()).value().targets())
        .as("a field of the project's own goes to its declaration")
        .isNotEmpty();
  }

  @Test
  void definition_jdkTypeWithSourcesMissing_explainsWithEveryAnswer() throws Exception {
    final String source = "package com.example; class Caller { String text = \"x\"; }";
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", source);
    compileIntoMirror(caller);
    final Path home = tmp.resolve("jdk");
    TestCompiler.recordJdk(
        tmp, new JdkSourceData("Vendor", "21.0.1", SourceStatus.MISSING, home, null, null, null));
    engine = new LatheEngine(tmp);
    final var pos = positionOf(source, "String text");

    for (int ask = 0; ask < 2; ask++) {
      final LatheDefinition answer =
          engine.definition(caller, pos.getLine(), pos.getCharacter()).value();
      assertThat(answer.targets()).isEmpty();
      assertThat(answer.explanation())
          .hasValueSatisfying(
              note ->
                  assertThat(note)
                      .startsWith("JDK sources are missing: ")
                      .contains(home.resolve(LatheLayout.JDK_SOURCE_ZIP).toString()));
    }
  }
}
