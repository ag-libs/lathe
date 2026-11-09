package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
// Sample.java DocHelper positions (0-based lines):
//
// 115:   public static class DocHelper {                     DocHelper=22
// 142:       var instance = new DocHelper();                 DocHelper=25
// 143:       var max = MAX;                                  MAX=16
// 144:       var greeting = greet();                         greet=21
// 145:       var items = repeat("hi", 3);                    repeat=18
// 146:       var upper = repeat("hi", 3).stream()...greet()  greet=52
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Hover;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HoverTest extends SampleFixture {

  // Drives the real SourceAnalysisSession.hover() path (unlike hoverAt, which bypasses the
  // argument param-hint branch). `token` is located as the first occurrence at/after `after`.
  private static String hoverText(final String source, final String after, final String token) {
    try (final var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final int offset = source.indexOf(token, source.indexOf(after));
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              source,
              0,
              Positions.offsetToPosition(source, offset),
              List.of(),
              LibrarySources.none());
      final Hover hover = session.navigation().hover(request);
      return hover == null ? null : hover.getContents().getRight().getValue();
    }
  }

  @Test
  void hover_type_showsClassJavadoc() {
    // "DocHelper" in "var instance = new DocHelper()"
    final var md = hoverAt(142, 25);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("class DocHelper").contains("Utility for hover Javadoc tests");
  }

  @Test
  void hover_field_showsFieldJavadoc() {
    // "MAX" in "var max = MAX"
    final var md = hoverAt(143, 16);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("MAX").contains("maximum number of items allowed");
  }

  @Test
  void hover_method_showsMethodJavadoc() {
    // "greet" in "var greeting = greet()"
    final var md = hoverAt(144, 21);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("greet").contains("Returns a greeting message");
  }

  @Test
  void hover_genericMethod_showsJavadoc() {
    // "repeat" in "var items = repeat("hi", 3)"
    final var md = hoverAt(145, 18);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("repeat").contains("Repeats the given value");
  }

  @Test
  void hover_javadocLinkTags_resolveReferencedTypeAndMember() {
    final var src =
        """
        /** A documented greeter. */
        class Greeter {
          /** Returns a greeting. */
          String greet() { return "hi"; }
        }
        /**
         * Use {@link Greeter} and {@link Greeter#greet()}.
         */
        class User {
          /** Delegates to {@link Greeter#greet()} via {@link Nested}. */
          void run() {}
          /** Wraps {@link Greeter}. */
          static class Nested {}
        }
        """;
    assertThat(hoverText(src, "{@link Greeter}", "Greeter"))
        .contains("class Greeter")
        .contains("A documented greeter");
    assertThat(hoverText(src, "Greeter#greet", "greet"))
        .contains("greet")
        .contains("Returns a greeting");
    // A member's or nested type's Javadoc lies outside its tree; the link, not the enclosing
    // class, must resolve.
    assertThat(hoverText(src, "Delegates to {@link Greeter#greet", "greet"))
        .contains("Returns a greeting");
    assertThat(hoverText(src, "via {@link Nested", "Nested")).contains("class Nested");
    assertThat(hoverText(src, "Wraps {@link Greeter", "Greeter"))
        .contains("class Greeter")
        .doesNotContain("class User");
  }

  @Test
  void hover_varargsMethod_rendersEllipsisNotArray() {
    final var src =
        """
        class A {
          /** logs */
          void log(String fmt, Object... args) {}
          void use() { log("x", 1); }
        }
        """;
    final var md = hoverText(src, "log(\"x\"", "log");
    assertThat(md).contains("Object... args").doesNotContain("Object[]");
  }

  @Test
  void hover_constructor_rendersTypeNameNotInitVoid() {
    final var src =
        """
        class Widget {
          /** makes a widget */
          Widget(int size) {}
        }
        """;
    final var md = hoverText(src, "Widget(int size)", "Widget");
    assertThat(md).contains("Widget(int size)").doesNotContain("<init>");
  }

  @Test
  void hover_newExpression_describesConstructorWithClassContext() {
    final var src =
        """
        /** A widget class. */
        class Widget<T> {
          /**
           * Makes a sized widget.
           *
           * @param size the size
           */
          Widget(int size) {}

          Widget() {}
        }

        class User {
          void use() {
            new Widget<String>(3);
            new Widget<String>();
          }
        }
        """;
    assertThat(hoverText(src, "new Widget<String>(3", "Widget"))
        .contains("class Widget<T>\nWidget(int size)")
        .contains("Makes a sized widget.")
        .contains("**@param** `size` the size")
        .doesNotContain("A widget class.");
    assertThat(hoverText(src, "new Widget<String>()", "Widget"))
        .contains("class Widget<T>\nWidget()")
        .contains("A widget class.");
    assertThat(hoverText(src, "Widget() {}", "Widget"))
        .contains("class Widget<T>\nWidget()")
        .contains("A widget class.");
  }

  @Test
  void hover_anonymousClassConstructor_omitsNamelessClassLine() {
    final var src =
        """
        class User {
          Thread make() {
            return new Thread("worker") {};
          }
        }
        """;
    assertThat(hoverText(src, "new Thread", "new")).startsWith("```java\n(String)\n```");
  }

  @Test
  void hover_undocumentedOverride_namesInheritedParamsAsOverrideDoes() {
    final var src =
        """
        interface Shape {
          /**
           * Resizes the shape.
           *
           * @param scale the factor
           */
          void resize(int scale);
        }

        class Box implements Shape {
          @Override
          public void resize(int factor) {}

          void use() { resize(2); }
        }
        """;
    assertThat(hoverText(src, "resize(2)", "resize"))
        .contains("void resize(int factor)")
        .contains("Resizes the shape.")
        .contains("**@param** `factor` the factor")
        .doesNotContain("`scale`");
  }

  @Test
  void hover_inheritDocTags_resolveFromOverriddenMethod() {
    final var src =
        """
        class Base {
          /**
           * Base text.
           *
           * @param value the base value
           * @return the base result
           */
          int run(int value) { return value; }
        }

        class Child extends Base {
          /**
           * {@inheritDoc} Child extra.
           *
           * @param v {@inheritDoc}
           * @return {@inheritDoc}
           */
          @Override
          int run(int v) { return v; }

          void use() { run(1); }
        }
        """;
    assertThat(hoverText(src, "run(1)", "run"))
        .contains("Base text. Child extra.")
        .contains("**@param** `v` the base value")
        .contains("**@return** the base result");
  }

  @Test
  void hover_undocumentedOverride_inheritsThroughChain_ownDocWins() {
    final var src =
        """
        interface Shape {
          /** Root doc. */
          double area();
        }

        class Square implements Shape {
          /** {@inheritDoc} Square part. */
          @Override
          public double area() { return 1; }
        }

        class Tile extends Square {
          @Override
          public double area() { return 2; }
        }

        class Circle implements Shape {
          /** Own doc. */
          @Override
          public double area() { return 3; }

          void use(Tile tile) {
            tile.area();
            area();
          }
        }
        """;
    assertThat(hoverText(src, "tile.area()", "area")).contains("Root doc. Square part.");
    assertThat(hoverText(src, "    area();", "area"))
        .contains("Own doc.")
        .doesNotContain("Root doc.");
  }

  @Test
  void hover_overridingMethod_namesNearestOverriddenMethod_plainMethodDoesNot() {
    final var src =
        """
        interface Lifecycle {
          void beforeStart();
        }

        abstract class Base implements Lifecycle {
          @Override
          public void beforeStart() {}
        }

        class Route extends Base {
          @Override
          public void beforeStart() {}

          void plain() {}

          void use() {
            beforeStart();
            plain();
          }
        }
        """;
    assertThat(hoverText(src, "    beforeStart();", "beforeStart"))
        .contains("**Overrides** `Base.beforeStart`")
        .doesNotContain("Lifecycle.beforeStart");
    assertThat(hoverText(src, "    plain();", "plain")).doesNotContain("Overrides");
  }

  @Test
  void hover_deprecatedMethod_showsDeprecationAndItsText_currentMethodDoesNot() {
    final var src =
        """
        class Api {
          /**
           * Sends the request.
           *
           * @deprecated use {@link #sendAsync()} instead
           */
          @Deprecated
          void send() {}

          void sendAsync() {}

          void use() {
            send();
            sendAsync();
          }
        }
        """;
    final String md = hoverText(src, "    send();", "send");
    assertThat(md).contains("**Deprecated.** use `sendAsync()` instead\n\nSends the request.");
    assertThat(md.split("Deprecated", -1)).hasSize(2);
    assertThat(hoverText(src, "    sendAsync();", "sendAsync")).doesNotContain("Deprecated");
  }

  @Test
  void hover_overrideOfDistantSuperclass_beatsDirectInterface_implementationIsSpecifiedBy() {
    final var src =
        """
        interface Named {
          String name();
        }

        abstract class Root {
          public abstract String name();
        }

        abstract class Middle extends Root {}

        class Leaf extends Middle implements Named {
          @Override
          public String name() { return "leaf"; }

          void use() { name(); }
        }

        class Task implements Runnable {
          @Override
          public void run() {}

          void use() { run(); }
        }
        """;
    assertThat(hoverText(src, "void use() { name", "name"))
        .contains("**Overrides** `Root.name`")
        .doesNotContain("Named");
    assertThat(hoverText(src, "void use() { run", "run"))
        .contains("**Specified by** `Runnable.run`")
        .doesNotContain("Overrides");
  }

  @Test
  void hover_deprecationWithoutTag_constructorOfDeprecatedClass_andInheritedTag() {
    final var src =
        """
        class Api {
          @Deprecated
          void old() {}

          /**
           * Sends.
           *
           * @deprecated use the async one
           */
          @Deprecated
          void send() {}
        }

        @Deprecated
        class OldClient {}

        class Child extends Api {
          /**
           * Child send.
           *
           * @deprecated {@inheritDoc}
           */
          @Deprecated
          @Override
          void send() {}

          void use() {
            old();
            new OldClient();
            send();
          }
        }
        """;
    assertThat(hoverText(src, "    old();", "old")).contains("**Deprecated.**");
    assertThat(hoverText(src, "new OldClient", "OldClient"))
        .contains("OldClient()")
        .contains("**Deprecated.**");
    assertThat(hoverText(src, "    send();", "send"))
        .contains("**Deprecated.** use the async one")
        .contains("Child send.")
        .doesNotContain("Sends.");
  }

  private static final String VAR_SOURCE =
      """
      import java.util.function.Supplier;

      /** A box. */
      class Box {
        /** Makes an empty box. */
        Box() {}
      }

      class User {
        void use() {
          var box = new Box();
          var count = 1;
          final Box var = new Box();
          var broken = undefinedCall();
          Supplier<Box> make = Box::new;
        }
      }
      """;

  @Test
  void hover_varKeyword_describesInferredType() {
    assertThat(hoverText(VAR_SOURCE, "var box", "var")).contains("class Box").contains("A box.");
    assertThat(hoverText(VAR_SOURCE, "var box", "box =")).contains("Box box");
  }

  @Test
  void hover_varWithoutDeclaredType_describesVariable() {
    assertThat(hoverText(VAR_SOURCE, "var count", "var")).doesNotContain("class");
    assertThat(hoverText(VAR_SOURCE, "final Box var", "var")).contains("Box var");
    // An unresolved initializer: the variable, as before, not a made-up class.
    assertThat(hoverText(VAR_SOURCE, "var broken", "var"))
        .contains(" broken")
        .doesNotContain("class ");
  }

  @Test
  void hover_constructorReferenceNew_describesConstructor() {
    assertThat(hoverText(VAR_SOURCE, "Box::new", "new"))
        .contains("class Box\nBox()")
        .contains("Makes an empty box.");
  }

  @Test
  void hover_genericMethod_rendersLeadingTypeParameter() {
    final var src =
        """
        class Box {
          /** firsts */
          <T> T first(java.util.List<T> items) { return items.get(0); }
          void use() { first(java.util.List.of("a")); }
        }
        """;
    final var md = hoverText(src, "first(java.util.List.of", "first");
    assertThat(md).contains("<T>").contains("first");
  }

  @Test
  void hover_methodInLambda_showsJavadoc() {
    // "greet" inside ".map(s -> greet())"
    final var md = hoverAt(146, 52);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("greet").contains("Returns a greeting message");
  }

  @Test
  void hover_parameter_showsTypeAndName() {
    // "value" in "Objects.requireNonNull(value)" on line 51
    final var md = hoverAt(50, 27);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("String").contains("value");
  }

  @Test
  void hover_localVariable_showsTypeAndName() {
    // "trimmed" in "var trimmed = input.strip()" on line 90
    final var md = hoverAt(89, 8);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("String").contains("trimmed");
  }

  @Test
  void hover_lambdaParameter_showsTypeAndName() {
    // "s" parameter declaration in ".map(s -> s.toUpperCase())" on line 60
    final var md = hoverAt(59, 30);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("String").contains("s");
  }

  @Test
  void hover_annotationType_showsKindAndName() {
    // "Logged" in "@Logged("summarize")" on line 81
    final var md = hoverAt(80, 3);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("@interface").contains("Logged");
  }

  @Test
  void hover_enumConstant_showsTypeAndName() {
    // "ACTIVE" in "return Status.ACTIVE" on line 103
    final var md = hoverAt(102, 18);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("Status").contains("ACTIVE");
  }

  // --- import declaration ---

  @Test
  void hover_importDeclaration_resolvesImportedType() {
    // "List" in "import java.util.List;" on line 4, col 17
    final var md = hoverAt(4, 17);
    assertThat(md).isPresent();
    assertThat(md.get()).contains("List");
  }

  // --- argument param-hint ---

  @Test
  void hover_recordComponentInCompactConstructor_resolvesComponentNotCalleeParameter() {
    // Hovering the bare component reference inside the compact constructor must show the
    // component itself, not the callee's parameter (`Object value`). Mirrors the FIELD-read case.
    final var source =
        """
        record Config(String bucket) {
            Config { check(bucket, "bucket"); }
            static void check(Object value, String name) {}
        }
        """;
    final var md = hoverText(source, "check(", "bucket");
    assertThat(md).contains("bucket").doesNotContain("value");
  }

  @Test
  void hover_insideMethodRefArgument_showsOwnSymbol_plainArgumentShowsParameter() {
    final var source =
        """
        import java.util.Optional;
        class T {
            void setPattern(String p) {}
            void use(Optional<String> path, T jersey, String plain) {
                path.ifPresent(jersey::setPattern);
                setPattern(plain);
            }
        }
        """;
    assertThat(hoverText(source, "ifPresent(jersey", "jersey"))
        .contains("T jersey")
        .doesNotContain("action");
    assertThat(hoverText(source, "jersey::setPattern", "setPattern"))
        .contains("setPattern(String p)")
        .doesNotContain("action");
    // A plain argument still gets the callee's parameter hint.
    assertThat(hoverText(source, "setPattern(plain", "plain")).contains("String p");
  }

  @Test
  void hover_classFileDependencyArgument_showsSourceParameterName(@TempDir final Path tmpDir)
      throws Exception {
    // Hovering an argument whose callee is a class-file dependency compiled without
    // -parameters must show the resolved source name, not the synthetic `arg0`.
    try (final var fixture = new ClassFileFixture(tmpDir)) {
      final var source =
          """
          class Test {
              void caller() { new Greeter("x").greet("hello", 1); }
          }
          """;
      fixture.session().compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var pos = Positions.offsetToPosition(source, source.indexOf("\"hello\""));
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              source,
              0,
              pos,
              List.of(fixture.srcDir()),
              LibrarySources.none());
      final var hover = fixture.session().navigation().hover(request);

      assertThat(hover).isNotNull();
      final var md = hover.getContents().getRight().getValue();
      assertThat(md).contains("name").doesNotContain("arg0");
    }
  }

  // --- class-file dependency ---

  @Test
  void hover_classFileDependency_showsSourceParameterNames(@TempDir final Path tmpDir)
      throws Exception {
    try (final var fixture = new ClassFileFixture(tmpDir)) {
      final var source =
          """
          class Test {
              void caller() { new Greeter("x").greet("x", 1); }
          }
          """;
      fixture.session().compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var pos = Positions.offsetToPosition(source, source.indexOf("greet"));
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              source,
              0,
              pos,
              List.of(fixture.srcDir()),
              LibrarySources.none());
      final var hover = fixture.session().navigation().hover(request);

      assertThat(hover).isNotNull();
      assertThat(hover.getContents().getRight().getValue()).contains("String name", "int count");
    }
  }

  private Optional<String> hoverAt(final int line, final int character) {
    final var path = pathAt(line, character);
    final var element = CursorElements.elementAt(compiled.trees(), path);
    final var type = path != null ? compiled.trees().getTypeMirror(path) : null;
    final var javadoc =
        new JavadocLocator(compiled.parser())
            .locate(element, compiled.trees(), List.of())
            .map(JavadocMarkdownPrinter::format)
            .orElse(null);
    final var fmt = new TypeDisplayFormatter(compiled.task().getTypes());
    return HoverFormatter.format(element, type, javadoc, null, fmt, null);
  }
}
