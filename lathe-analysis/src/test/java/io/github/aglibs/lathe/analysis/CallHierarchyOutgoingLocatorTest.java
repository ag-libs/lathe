package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallHierarchyOutgoingLocatorTest {

  @TempDir private Path sourceRoot;

  @Test
  void outgoingCalls_anonymousGenericConstruction_resolvesSuperclassOverloads() throws IOException {
    final String constructors = "class Box<T> { Box(int count) {} Box(String value) {} }";
    Files.writeString(sourceRoot.resolve("Box.java"), constructors);
    final String content =
        """
        class Caller {
            void build() {
                new Box<String>(1) {};
                new Box<String>("other") {};
            }
        }
        """
            + constructors;
    final var request = request(content, SampleFixture.posOf(content, "void build()", "build"));
    try (final var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));
      assertThat(calls)
          .extracting(
              call -> call.getTo().getSelectionRange().getStart(),
              call -> call.getFromRanges().get(0).getStart())
          .containsExactlyInAnyOrder(
              tuple(
                  SampleFixture.posOf(constructors, "Box(int count)", "Box"),
                  SampleFixture.posOf(content, "new Box<String>(1)", "Box")),
              tuple(
                  SampleFixture.posOf(constructors, "Box(String value)", "Box"),
                  SampleFixture.posOf(content, "new Box<String>(\"other\")", "Box")));
      assertThat(calls).allSatisfy(call -> assertThat(call.getFromRanges()).hasSize(1));
    }
  }

  @Test
  void outgoingCalls_methodWithCallee_returnsCallWithRange() throws IOException {
    Files.writeString(
        sourceRoot.resolve("Callee.java"),
        """
        class Callee { void doStuff(String s) {} }
        """);
    final String content =
        """
        class Target {
            void work() {
                Callee c = make();
                c.doStuff("x");
            }
            private Callee make() { return null; }
        }
        class Callee { void doStuff(String s) {} }
        """;
    final var request = request(content, new Position(1, 9));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).getTo().getName()).isEqualTo("doStuff");
      assertThat(calls.get(0).getFromRanges()).hasSize(1);
    }
  }

  @Test
  void outgoingCalls_constructorCall_returnsConstructorCallee() throws IOException {
    Files.writeString(
        sourceRoot.resolve("Box.java"),
        """
        class Box { Box(int n) {} }
        """);
    final String content =
        """
        class Factory {
            Object create() {
                return new Box(42);
            }
        }
        class Box { Box(int n) {} }
        """;
    final var request = request(content, new Position(1, 11));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).getTo().getName()).isEqualTo("Box");
      assertThat(calls.get(0).getFromRanges()).hasSize(1);
    }
  }

  @Test
  void outgoingCalls_constructorReference_listedAtNew() throws IOException {
    final List<CallHierarchyOutgoingCall> calls =
        outgoingCallsOfCreate(
            """
                Supplier<Box> box = Box::new;
                return box;
            """);

    assertThat(calls)
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.getTo().getName()).isEqualTo("Box");
              assertThat(call.getFromRanges())
                  .singleElement()
                  .satisfies(
                      range ->
                          assertThat(
                                  range.getEnd().getCharacter() - range.getStart().getCharacter())
                              .isEqualTo(3));
            });
  }

  @Test
  void outgoingCalls_arrayConstructorReference_skipped() throws IOException {
    assertThat(
            outgoingCallsOfCreate(
                """
                    IntFunction<int[]> array = int[]::new;
                    return array;
                """))
        .isEmpty();
  }

  private List<CallHierarchyOutgoingCall> outgoingCallsOfCreate(final String body)
      throws IOException {
    Files.writeString(sourceRoot.resolve("Box.java"), "class Box { Box() {} }\n");
    final String content =
        """
        import java.util.function.IntFunction;
        import java.util.function.Supplier;
        class Factory {
            Object create() {
        %s    }
        }
        class Box { Box() {} }
        """
            .formatted(body);
    final var request = request(content, SampleFixture.posOf(content, "Object create()", "create"));
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      return session
          .hierarchy()
          .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));
    }
  }

  // Only the method's own calls, a lambda's included: not a field initializer's, nor the implicit
  // constructor call of each enum constant, which elsewhere in the class are not the method's.
  @Test
  void outgoingCalls_classWithFieldInitializerAndEnum_returnsOnlyTheMethodsCalls()
      throws IOException {
    final String content =
        """
        class Target {
            static final Callee EMPTY = make();
            enum Kind { A, B }
            void work(Callee c) {
                c.doStuff("x");
                Runnable r = () -> c.doStuff("y");
            }
            private static Callee make() { return null; }
        }
        class Callee { void doStuff(String s) {} }
        """;
    Files.writeString(sourceRoot.resolve("Target.java"), content);
    Files.writeString(
        sourceRoot.resolve("Callee.java"), "class Callee { void doStuff(String s) {} }\n");
    final var request = request(content, new Position(3, 9));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);

      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls)
          .singleElement()
          .satisfies(
              call -> {
                assertThat(call.getTo().getName()).isEqualTo("doStuff");
                assertThat(call.getFromRanges())
                    .extracting(r -> r.getStart().getLine())
                    .containsExactly(4, 5);
              });
    }
  }

  // An implicit accessor has no tree of its own; it points at its component, not the file's top.
  @Test
  void outgoingCalls_recordAccessor_pointsAtItsComponent() throws IOException {
    Files.writeString(
        sourceRoot.resolve("Point.java"),
        """
        class Unused {}
        record Point(int x, int y) {}
        """);
    final String content =
        """
        class Reader {
            int read(Point p) {
                return p.y();
            }
        }
        record Point(int x, int y) {}
        """;
    final var request = request(content, new Position(1, 9));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);

      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls)
          .singleElement()
          .satisfies(
              call ->
                  assertThat(call.getTo().getSelectionRange().getStart())
                      .isEqualTo(new Position(1, 24)));
    }
  }

  @Test
  void outgoingCalls_anonymousClassInstantiation_excludedFromResults() throws IOException {
    final String content =
        """
        class Task {
            void run() {
                execute(new Runnable() {
                    public void run() {}
                });
            }
            private void execute(Runnable r) {}
        }
        """;
    Files.writeString(sourceRoot.resolve("Task.java"), content);
    final var request = request(content, new Position(1, 9));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).noneMatch(c -> c.getTo().getName().isEmpty());
    }
  }

  @Test
  void outgoingCalls_methodOfALocalOrAnonymousClass_listsItsCalls() throws IOException {
    final String content =
        """
        class Task {
            void outer() {
                Runnable r = new Runnable() {
                    public void run() { step(); }
                };
                class Helper {
                    void help() { step(); }
                }
            }
            void step() {}
        }
        """;
    Files.writeString(sourceRoot.resolve("Task.java"), content);

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      for (final String method : List.of("run", "help")) {
        final var request =
            request(content, SampleFixture.posOf(content, "void " + method, method));
        session.compile(request.uri(), content, 1, CompileMode.OPEN);
        final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
        final List<CallHierarchyOutgoingCall> calls =
            session
                .hierarchy()
                .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

        assertThat(calls)
            .as(method)
            .extracting(call -> call.getTo().getName())
            .containsExactly("step");
      }
    }
  }

  @Test
  void outgoingCalls_receiverSpellsTheName_rangesOnTheCalledName() throws IOException {
    final String content =
        """
        import java.util.function.IntFunction;
        class Chain {
            Chain add(int i) { return this; }
            void use(Chain add) {
                add.add(1).add(2);
                IntFunction<Chain> f = add::add;
            }
        }
        """;
    Files.writeString(sourceRoot.resolve("Chain.java"), content);
    final var request = request(content, SampleFixture.posOf(content, "void use", "use"));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      final List<CallHierarchyOutgoingCall> calls =
          session
              .hierarchy()
              .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot));

      assertThat(calls).extracting(call -> call.getTo().getName()).containsExactly("add");
      assertThat(calls.get(0).getFromRanges())
          .extracting(Range::getStart)
          .containsExactlyInAnyOrder(
              SampleFixture.posOf(content, ".add(1)", "add"),
              SampleFixture.posOf(content, ".add(2)", "add"),
              SampleFixture.posOf(content, "::add", "add"));
    }
  }

  @Test
  void outgoingCalls_emptyMethodBody_returnsEmpty() {
    final String content = "class Thing { void noop() {} }\n";
    final var request = request(content, new Position(0, 19));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      assertThat(
              session
                  .hierarchy()
                  .outgoingCalls(items.get(0), request.uri(), content, 1, List.of(sourceRoot)))
          .isEmpty();
    }
  }

  // EG-122: a field or initializer block credited with a call (as incoming calls credit one outside
  // any method) lists the calls in its own initializer, the lambdas it creates among them, and no
  // other member's.
  @Test
  void outgoingCalls_fieldOrInitializerCaller_listsTheCallsInItsInitializerOnly()
      throws IOException {
    final String content =
        """
        class Lib {
            static int target(String s) { return 1; }
            static final int CONSTANT = target("constant");
            static { target("static"); }
            final Runnable lambda = () -> target("lambda");
            int method() { return target("method"); }
        }
        """;
    Files.writeString(sourceRoot.resolve("Lib.java"), content);
    final var request = request(content, SampleFixture.posOf(content, "target(String", "target"));
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      final List<CallHierarchyItem> callers =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  CallHierarchyItemDataCodec.decode(items.get(0).getData()),
                  Set.of())
              .hits()
              .stream()
              .map(CallHierarchyIncomingCall::getFrom)
              .toList();

      for (final String caller : List.of("CONSTANT", "static {...}", "lambda")) {
        final CallHierarchyItem item =
            callers.stream()
                .filter(from -> from.getName().equals(caller))
                .findFirst()
                .orElseThrow();
        final String argument =
            caller.equals("CONSTANT") ? "constant" : caller.equals("lambda") ? "lambda" : "static";
        assertThat(
                session
                    .hierarchy()
                    .outgoingCalls(item, request.uri(), content, 1, List.of(sourceRoot)))
            .as(caller)
            .extracting(call -> call.getTo().getName(), call -> call.getFromRanges())
            .containsExactly(
                tuple(
                    "target",
                    List.of(
                        new Range(
                            SampleFixture.posOf(
                                content, "target(\"%s".formatted(argument), "target"),
                            SampleFixture.posOf(content, "(\"%s".formatted(argument), "(")))));
      }
    }
  }

  // Each initializer block is its own caller: a class's second static block does not lend the first
  // its calls. A named class inside an anonymous class makes its own calls (credited to its own
  // field by incoming calls), not the method creating the anonymous class.
  @Test
  void outgoingCalls_blockOrMethodAroundOtherDeclarations_listsOnlyItsOwnCalls()
      throws IOException {
    final String content =
        """
        class Lib {
            static int a() { return 1; }
            static int b() { return 2; }
            static { a(); }
            static { b(); }
            void m() { new Object() { class Inner { int x = b(); } }; a(); }
        }
        """;
    Files.writeString(sourceRoot.resolve("Lib.java"), content);
    final var request = request(content, SampleFixture.posOf(content, "int a()", "a"));
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      final List<CallHierarchyItem> callers =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  CallHierarchyItemDataCodec.decode(items.get(0).getData()),
                  Set.of())
              .hits()
              .stream()
              .map(CallHierarchyIncomingCall::getFrom)
              .toList();

      assertThat(callers)
          .extracting(CallHierarchyItem::getName)
          .containsExactlyInAnyOrder("static {...}", "m");
      for (final CallHierarchyItem caller : callers) {
        assertThat(
                session
                    .hierarchy()
                    .outgoingCalls(caller, request.uri(), content, 1, List.of(sourceRoot)))
            .as(caller.getName())
            .extracting(call -> call.getTo().getName())
            .containsExactly("a");
      }
    }
  }

  private SourceFeatureRequest request(final String content, final Position position) {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI,
        content,
        0,
        position,
        List.of(sourceRoot),
        LibrarySources.none());
  }
}
