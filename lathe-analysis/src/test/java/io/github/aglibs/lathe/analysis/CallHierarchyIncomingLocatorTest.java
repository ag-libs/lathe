package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallHierarchyIncomingLocatorTest {

  @TempDir private Path sourceRoot;

  @Test
  void searchIncomingCalls_memberSelectCall_returnsCallerAndRange() {
    final String content =
        """
        class Caller { void invoke() { new Target().run(); } }
        class Target { void run() {} }
        """;
    // cursor on "run" declaration in Target at (1, 20)
    final var targetRequest = request(content, new Position(1, 20));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(targetRequest);
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getName()).isEqualTo("run");

      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();

      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).getFrom().getName()).isEqualTo("invoke");
      assertThat(calls.get(0).getFromRanges()).hasSize(1);
      assertThat(calls.get(0).getFromRanges().get(0).getStart().getLine()).isEqualTo(0);
    }
  }

  @Test
  void searchIncomingCalls_constructorCallAndReference_returnsCallersOfThatOverloadOnly() {
    final String content =
        """
        import java.util.function.Function;
        import java.util.function.Supplier;
        class Caller {
            void build() { new Box(); }
            Supplier<Box> supply() { return Box::new; }
            Function<String, Box> convert() { return Box::new; }
        }
        class Box { Box() {} Box(String s) {} }
        """;
    final var targetRequest = request(content, SampleFixture.posOf(content, "Box() {}", "Box"));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(targetRequest);
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getName()).isEqualTo("Box");

      final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(items.get(0).getData());
      assertThat(data).isNotNull();
      assertThat(data.kind()).isEqualTo(ElementKind.CONSTRUCTOR);

      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();

      assertThat(calls)
          .extracting(c -> c.getFrom().getName(), c -> c.getFromRanges().get(0).getStart())
          .containsExactlyInAnyOrder(
              tuple("build", SampleFixture.posOf(content, "new Box()", "Box")),
              tuple("supply", SampleFixture.posOf(content, "return Box::new", "new")));
    }
  }

  @Test
  void
      searchIncomingCalls_delegationAndAnonymousGenericCreation_keepsConstructorOverloadsSeparate() {
    final String content =
        """
        class Box<T> {
            Box(int count) {}
            Box(String value) {}
            Box() { this(1); }
        }
        class Child extends Box<String> {
            Child() { super(2); }
            Child(String value) { super(value); }
        }
        class Caller {
            void build() { new Box<String>(3) {}; }
            void other() { new Box<String>("other") {}; }
        }
        """;
    final var targetRequest =
        request(content, SampleFixture.posOf(content, "Box(int count)", "Box"));
    try (final var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(targetRequest);
      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();
      assertThat(calls)
          .extracting(call -> call.getFromRanges().get(0).getStart())
          .containsExactlyInAnyOrder(
              SampleFixture.posOf(content, "this(1)", "this"),
              SampleFixture.posOf(content, "super(2)", "super"),
              SampleFixture.posOf(content, "new Box<String>(3)", "Box"));
      assertThat(calls).allSatisfy(call -> assertThat(call.getFromRanges()).hasSize(1));
    }
  }

  @Test
  void searchIncomingCalls_noCallers_returnsEmpty() {
    final String content =
        """
        class Isolated { void helper() {} }
        class Other { void doWork() {} }
        """;
    // cursor on "helper" at (0, 22)
    final var targetRequest = request(content, new Position(0, 22));

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(targetRequest.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(targetRequest);
      assertThat(items).hasSize(1);

      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();

      assertThat(calls).isEmpty();
    }
  }

  @Test
  void searchIncomingCalls_polymorphicCallThroughInterface_findsCallerFromOverride() {
    final String content =
        """
        interface Feature { void setup(); }
        class FeatureImpl implements Feature { public void setup() {} }
        class Runner { void run(Feature f) { f.setup(); } }
        """;
    // caret on the override 'setup' declaration in FeatureImpl
    final var caret = Positions.offsetToPosition(content, content.indexOf("void setup() {}") + 5);
    final var request = request(content, caret);

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      assertThat(items).hasSize(1);
      assertThat(items.get(0).getName()).isEqualTo("setup");

      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();

      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).getFrom().getName()).isEqualTo("run");
    }
  }

  @Test
  void searchIncomingCalls_unrelatedSameNamedMethod_notReportedFromOverride() {
    final String content =
        """
        interface Feature { void setup(); }
        class FeatureImpl implements Feature { public void setup() {} }
        class Unrelated { void setup() {} }
        class Runner { void run(Unrelated u) { u.setup(); } }
        """;
    // caret on the override 'setup' declaration in FeatureImpl
    final var caret = Positions.offsetToPosition(content, content.indexOf("void setup() {}") + 5);
    final var request = request(content, caret);

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);

      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);
      assertThat(items).hasSize(1);

      // u.setup() targets Unrelated.setup(), which neither overrides nor is overridden by
      // FeatureImpl.setup(); it must not be reported.
      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();

      assertThat(calls).isEmpty();
    }
  }

  // A call through a method the override overrides counts only on a receiver that can hold the
  // override's class (a supertype or subtype of it); an unqualified call's receiver is the
  // enclosing class inheriting the method, and a super call, plain or qualified, never dispatches
  // to an override.
  @Test
  void searchIncomingCalls_callThroughOverriddenMethod_keptOnlyOnAReceiverThatCanHoldTheOverride() {
    final String content =
        """
        interface Lifecycle { default void start() {} }
        interface Filter extends Lifecycle {}
        class Base implements Lifecycle {
          public void start() {}
          void boot() { start(); }
          void viaDefault() { Lifecycle.super.start(); }
        }
        class Impl extends Base {
          @Override public void start() { super.start(); }
        }
        class ImplUser extends Impl { void go() { start(); } }
        class BaseUser extends Base { void go() { start(); super.start(); } }
        abstract class Sibling implements Lifecycle { void tick() { start(); } }
        class Callers {
          void viaLifecycle(Lifecycle l) { l.start(); }
          void viaBase(Base b) { b.start(); }
          void viaImpl(Impl i) { i.start(); }
          void viaFilter(Filter f) { f.start(); }
          java.util.function.Consumer<Lifecycle> lifecycleRef() { return Lifecycle::start; }
          java.util.function.Consumer<Filter> filterRef() { return Filter::start; }
        }
        """;
    final var caret =
        Positions.offsetToPosition(content, content.indexOf("void start() { super") + 5);
    final var request = request(content, caret);

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(request.uri(), content, 1, CompileMode.OPEN);
      final List<CallHierarchyItem> items = session.hierarchy().prepareCallHierarchy(request);

      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, content, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  itemData(items.get(0)),
                  Set.of())
              .hits();

      assertThat(calls)
          .extracting(
              call -> "%s.%s".formatted(call.getFrom().getDetail(), call.getFrom().getName()))
          .containsExactlyInAnyOrder(
              "Base.boot",
              "ImplUser.go",
              "Callers.viaLifecycle",
              "Callers.viaBase",
              "Callers.viaImpl",
              "Callers.lifecycleRef");
    }
  }

  // Upstream of the override its class is not on the classpath: a call through the method it
  // overrides is matched by that method's own descriptor (Object, not the override's String), and
  // kept only on a receiver that is the override's class or one of its supertypes.
  @Test
  void searchIncomingCalls_overrideNotOnClasspath_findsCallsThroughItsBaseMethodByReceiver() {
    final String upstream =
        """
        interface Lifecycle<T> { void start(T config); }
        abstract class Base<T> implements Lifecycle<T> {
          public void start(T config) {}
          void boot(T config) { start(config); }
        }
        abstract class Other<T> implements Lifecycle<T> { void tick(T config) { start(config); } }
        class Callers {
          void viaBase(Base<String> b) { b.start("x"); }
          void viaLifecycle(Lifecycle<String> l) { l.start("x"); }
          void viaOther(Other<String> o) { o.start("x"); }
        }
        """;
    final String withOverride =
        upstream + "class Impl extends Base<String> { @Override public void start(String c) {} }\n";
    final var caret =
        Positions.offsetToPosition(withOverride, withOverride.indexOf("void start(String") + 5);

    final CallHierarchyItemData item;
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, withOverride, 1, CompileMode.OPEN);
      item =
          itemData(session.hierarchy().prepareCallHierarchy(request(withOverride, caret)).get(0));
    }

    assertThat(item.overriddenDeclarers()).containsExactly("Base", "Lifecycle");
    assertThat(item.receiverTypes())
        .as("the class and the supertypes having the method, not Object")
        .containsExactlyInAnyOrder("Impl", "Base", "Lifecycle");
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, upstream, 1, CompileMode.OPEN);
      final List<CallHierarchyIncomingCall> calls =
          session
              .references()
              .incomingCalls(
                  session.analysis(TempSourceCompiler.TEST_URI, upstream, 1, () -> {}),
                  TempSourceCompiler.TEST_URI,
                  item,
                  Set.of())
              .hits();

      assertThat(calls)
          .extracting(
              call -> "%s.%s".formatted(call.getFrom().getDetail(), call.getFrom().getName()))
          .containsExactlyInAnyOrder("Base.boot", "Callers.viaBase", "Callers.viaLifecycle");
    }
  }

  private static CallHierarchyItemData itemData(final CallHierarchyItem item) {
    return CallHierarchyItemDataCodec.decode(item.getData());
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
