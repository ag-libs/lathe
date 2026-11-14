package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignatureHelpTest {

  private static final String MARKER = "§";

  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() {
    session = new SourceAnalysisSession(new TempSourceCompiler());
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  // --- Basic method invocations ---

  @Test
  void signatureHelp_cursorInFirstArg_showsActiveParam0() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target("§", 1); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(1);
    assertThat(help.getActiveParameter()).isEqualTo(0);
    assertThat(help.getSignatures().get(0).getLabel()).contains("String name");
    assertThat(help.getSignatures().get(0).getLabel()).contains("int count");
  }

  @Test
  void signatureHelp_cursorInSecondArg_showsActiveParam1() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target("x", §1); }
        }
        """;
    assertThat(signatureHelpAt(source).getActiveParameter()).isEqualTo(1);
  }

  @Test
  void signatureHelp_cursorInThirdArg_showsActiveParam2() {
    final var source =
        """
        class Test {
          void target(String a, String b, String c) {}
          void caller() { target("x", "y", "§"); }
        }
        """;
    assertThat(signatureHelpAt(source).getActiveParameter()).isEqualTo(2);
  }

  @Test
  void signatureHelp_cursorAtTheEndOfAnArgumentOrPastVarargs_staysOnItsParameter() {
    final var endOfFirst =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target(name()§, 1); }
          String name() { return ""; }
        }
        """;
    final var pastVarargs =
        """
        class Test {
          void target(String format, Object... args) {}
          void caller() { target("x", 1, 2, §3); }
        }
        """;

    assertThat(signatureHelpAt(endOfFirst).getActiveParameter()).isEqualTo(0);
    assertThat(signatureHelpAt(pastVarargs).getActiveParameter()).isEqualTo(1);
  }

  @Test
  void signatureHelp_zeroParamMethod_returnsEmptyParamList() {
    final var source =
        """
        class Test {
          void target() {}
          void caller() { target(§); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().get(0).getParameters()).isEmpty();
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_qualifiedEmptyParens_paramMethod_returnsSignature() {
    final var source =
        """
        class Other {
          void target(String name, int count) {}
        }
        class Test {
          void caller() { new Other().target(§); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(1);
    assertThat(help.getSignatures().get(0).getLabel()).contains("target");
    assertThat(help.getSignatures().get(0).getLabel()).contains("String name");
    assertThat(help.getSignatures().get(0).getLabel()).contains("int count");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_qualifiedEmptyParens_zeroParamMethod_stillWorks() {
    final var source =
        """
        class Other {
          void target() {}
        }
        class Test {
          void caller() { new Other().target(§); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(1);
    assertThat(help.getSignatures().get(0).getLabel()).contains("target");
    assertThat(help.getSignatures().get(0).getParameters()).isEmpty();
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_nestedCall_resolvesInnermostInvocation() {
    final var source =
        """
        class Test {
          String inner(int x) { return ""; }
          void outer(String s) {}
          void caller() { outer(inner(§42)); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().get(0).getLabel()).contains("inner");
    assertThat(help.getSignatures().get(0).getLabel()).contains("int x");
  }

  @Test
  void signatureHelp_nestedCallCommaInOuter_countsCorrectly() {
    final var source =
        """
        class Test {
          String inner(int x) { return ""; }
          void outer(String a, String b) {}
          void caller() { outer("x", inner(§42)); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().get(0).getLabel()).contains("inner");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  // --- Constructor calls ---

  @Test
  void signatureHelp_constructorCall_showsParams() {
    final var source =
        """
        class Point {
          int x, y;
          Point(int x, int y) { this.x = x; this.y = y; }
        }
        class Test {
          void caller() { new Point(1, §2); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().get(0).getLabel()).contains("Point");
    assertThat(help.getActiveParameter()).isEqualTo(1);
  }

  // --- Overloaded methods ---

  @Test
  void signatureHelp_overloadedMethod_listsAllOverloadsAndSelectsResolved() {
    final var source =
        """
        class Test {
          void target(int x) {}
          void target(String s) {}
          void target(int x, int y) {}
          void caller() { target("§hello"); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(3);
    assertThat(help.getSignatures()).anyMatch(sig -> sig.getLabel().contains("int x)"));
    assertThat(help.getSignatures()).anyMatch(sig -> sig.getLabel().contains("String s"));
    assertThat(help.getSignatures()).anyMatch(sig -> sig.getLabel().contains("int x, int y"));
    assertThat(help.getSignatures().get(help.getActiveSignature()).getLabel()).contains("String s");
  }

  // --- Outside any call ---

  @Test
  void signatureHelp_cursorOutsideCall_returnsNull() {
    final var source =
        """
        class Test {
          void caller() { §int x = 1; }
        }
        """;
    assertThat(signatureHelpAt(source)).isNull();
  }

  // --- Parameter label offsets ---

  @Test
  void signatureHelp_paramLabelOffsets_pointIntoSignatureLabel() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target(§"x", 1); }
        }
        """;
    final var help = signatureHelpAt(source);
    final var sig = help.getSignatures().get(0);
    final var label = sig.getLabel();
    final var params = sig.getParameters();

    assertThat(params).hasSize(2);
    final var p0 = params.get(0).getLabel().getRight();
    assertThat(label.substring(p0.getFirst(), p0.getSecond())).isEqualTo("String name");
    final var p1 = params.get(1).getLabel().getRight();
    assertThat(label.substring(p1.getFirst(), p1.getSecond())).isEqualTo("int count");
  }

  // --- class-file dependency ---

  @Test
  void signatureHelp_classFileDependency_showsParamNamesAndJavadoc(@TempDir final Path tmpDir)
      throws Exception {
    try (final var fixture = new ClassFileFixture(tmpDir)) {
      final var methodHelp =
          fixture.signatureHelpAt(
              """
              class Test {
                  void caller() { new Greeter("x").greet(§"x", 1); }
              }
              """);
      assertThat(methodHelp).isNotNull();
      final var methodSig = methodHelp.getSignatures().get(0);
      assertThat(methodSig.getLabel()).contains("String name", "int count");
      assertThat(methodSig.getDocumentation().getRight().getValue())
          .contains("Greets the recipient")
          .doesNotContain("@param");
      assertThat(methodSig.getParameters().get(0).getDocumentation().getRight().getValue())
          .contains("the recipient");
      assertThat(methodSig.getParameters().get(1).getDocumentation().getRight().getValue())
          .contains("repetitions");

      final var ctorHelp =
          fixture.signatureHelpAt(
              """
              class Test {
                  void caller() { new Greeter(§"hi"); }
              }
              """);
      assertThat(ctorHelp).isNotNull();
      assertThat(ctorHelp.getSignatures()).hasSize(2);
      final var activeSig = ctorHelp.getSignatures().get(ctorHelp.getActiveSignature());
      assertThat(activeSig.getLabel()).contains("String label");
      assertThat(activeSig.getDocumentation().getRight().getValue()).contains("Creates a greeter");
    }
  }

  // Access is decided through the class a member is reached from, not the class declaring it.
  @Test
  void signatureHelp_protectedMembersFromASubclassInAnotherPackage_areListed(
      @TempDir final Path tmpDir) throws Exception {
    try (final var fixture = new ClassFileFixture(tmpDir)) {
      final String impl =
          """
          class Impl extends lib.Base {
              Impl() { %s }
              void foo(int x) {}
              void m(Impl other) { %s }
          }
          """;
      final Map<String, List<String>> expected =
          Map.of(
              impl.formatted("super(§);", ""),
              List.of("Base(", "Base("),
              impl.formatted("super(\"\");", "helper(§);"),
              List.of("void helper("),
              impl.formatted("super(\"\");", "super.helper(§);"),
              List.of("void helper("),
              impl.formatted("super(\"\");", "other.foo(\"x\"§);"),
              List.of("void foo(", "void foo("),
              "class Test { Object m() { return new lib.Base(1, 2§) {}; } }",
              List.of("Base(", "Base("),
              "class Test { char m() { return new StringBuilder().charAt(§); } }",
              List.of("char charAt("));

      final Map<String, List<String>> actual =
          expected.keySet().stream()
              .collect(
                  Collectors.toMap(
                      Function.identity(), source -> labelPrefixes(fixture, source, expected)));

      assertThat(actual).isEqualTo(expected);
      final var anonymous =
          fixture.signatureHelpAt("class Test { Object m() { return new lib.Base(1, 2§) {}; } }");
      assertThat(anonymous.getActiveSignature()).isEqualTo(1);
    }
  }

  // --- javadoc in documentation field ---

  @Test
  void signatureHelp_withJavadoc_populatesDocumentation() {
    final var source =
        """
        class Test {
            /**
             * Says hello.
             * @param msg the message
             */
            void greet(String msg) {}
            void caller() { greet(§"hi"); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    final var sig = help.getSignatures().get(0);
    assertThat(sig.getDocumentation()).isNotNull();
    assertThat(sig.getDocumentation().getRight().getValue())
        .contains("Says hello")
        .doesNotContain("@param");
    assertThat(sig.getParameters().get(0).getDocumentation().getRight().getValue())
        .contains("the message");
  }

  @Test
  void signatureHelp_inheritDoc_resolvesDescriptionAndParamFromOverriddenMethod() {
    final var source =
        """
        class Base {
            /**
             * Sends a message.
             * @param msg the message
             * @param retries how often to retry
             */
            void send(String msg, int retries) {}
        }
        class Child extends Base {
            /**
             * {@inheritDoc} Child part.
             * @param text {@inheritDoc}
             */
            @Override
            void send(String text, int count) {}
            void caller() { send(§"hi", 1); }
        }
        """;
    final var sig = signatureHelpAt(source).getSignatures().get(0);

    assertThat(sig.getDocumentation().getRight().getValue())
        .isEqualTo("Sends a message. Child part.");
    assertThat(sig.getParameters().get(0).getDocumentation().getRight().getValue())
        .isEqualTo("the message");
    // Left out of the override's doc, so inherited as the javadoc tool does.
    assertThat(sig.getParameters().get(1).getDocumentation().getRight().getValue())
        .isEqualTo("how often to retry");
  }

  @Test
  void signatureHelp_partlyDocumentedOverride_inheritsWhatItLeavesOut() {
    final var source =
        """
        interface Sender {
            /**
             * Sends a message.
             * @param msg the message
             * @param retries how often to retry
             */
            void send(String msg, int retries);
        }
        class Impl implements Sender {
            /**
             * @param text
             * @param count retry budget
             */
            @Override
            public void send(String text, int count) {}
            void caller() { send(§"hi", 1); }
        }
        """;
    final var sig = signatureHelpAt(source).getSignatures().get(0);

    assertThat(sig.getDocumentation().getRight().getValue()).isEqualTo("Sends a message.");
    // An own @param with no text counts as left out, as in hover.
    assertThat(sig.getParameters().get(0).getDocumentation().getRight().getValue())
        .isEqualTo("the message");
    assertThat(sig.getParameters().get(1).getDocumentation().getRight().getValue())
        .isEqualTo("retry budget");
  }

  @Test
  void signatureHelp_undocumentedOverride_takesParentDocsByPosition() {
    final var source =
        """
        interface Sender {
            /**
             * Sends a message.
             * @param msg the message
             * @param retries how often to retry
             */
            void send(String msg, int retries);
        }
        class Impl implements Sender {
            @Override
            public void send(String text, int count) {}
            void caller() { send(§"hi", 1); }
        }
        """;
    final var sig = signatureHelpAt(source).getSignatures().get(0);

    assertThat(sig.getLabel()).isEqualTo("void send(String text, int count)");
    assertThat(sig.getDocumentation().getRight().getValue()).isEqualTo("Sends a message.");
    assertThat(sig.getParameters().get(0).getDocumentation().getRight().getValue())
        .isEqualTo("the message");
    assertThat(sig.getParameters().get(1).getDocumentation().getRight().getValue())
        .isEqualTo("how often to retry");
  }

  // --- super() and this() constructor invocations ---

  @Test
  void signatureHelp_superConstructorCall_showsParentOverloads() {
    final var source =
        """
        class Base {
            Base(int x) {}
            Base(String s) {}
        }
        class Child extends Base {
            Child() { super(§42); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(2);
    assertThat(help.getSignatures()).anyMatch(sig -> sig.getLabel().contains("int x"));
    assertThat(help.getSignatures()).anyMatch(sig -> sig.getLabel().contains("String s"));
    final var activeSig = help.getSignatures().get(help.getActiveSignature());
    assertThat(activeSig.getLabel()).startsWith("Base(").doesNotContain("<init>");
    assertThat(activeSig.getLabel()).contains("int x");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_thisConstructorCall_showsCurrentClassOverloads() {
    final var source =
        """
        class Test {
            Test(int x) {}
            Test(String s) {}
            Test() { this(§42); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).anyMatch(sig -> sig.getLabel().contains("int x"));
    assertThat(help.getSignatures().get(help.getActiveSignature()).getLabel()).contains("int x");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_constructorCallWithoutNoArgConstructor_listsEveryConstructor() {
    final var declarations =
        """
        class P {
            P(int x) {}
            P(String s) {}
            P(int x, int y) {}
        }
        """;
    final List<String> calls =
        List.of(
            "class Test { Object m() { return new P(§); } }",
            "class Test { Object m() { return new P(§) {}; } }",
            "class Child extends P { Child() { super(§); } }");

    calls.forEach(
        call -> {
          final var help = signatureHelpAt(declarations + call);

          assertThat(help).as(call).isNotNull();
          assertThat(help.getSignatures())
              .as(call)
              .extracting(SignatureInformation::getLabel)
              .containsExactly("P(int x)", "P(String s)", "P(int x, int y)");
          assertThat(help.getActiveParameter()).as(call).isEqualTo(0);
        });
  }

  @Test
  void signatureHelp_inaccessibleOverload_isNotListed() {
    final var source =
        """
        import java.util.ArrayList;
        class Hidden {
            private Hidden(int x) {}
            Hidden(String s) {}
            private void m(int x) {}
            void m(String s) {}
        }
        class Test {
            void caller() {
                new ArrayList<String>().add(§);
                new Hidden("x").m(%s);
                new Hidden(%s);
            }
        }
        """;

    assertThat(signatureHelpAt(source.formatted("", "")).getSignatures())
        .extracting(SignatureInformation::getLabel)
        .hasSize(2)
        .noneMatch(label -> label.contains("Object[]"));
    assertThat(signatureHelpAt(source.replace("§", "").formatted("§", "")).getSignatures())
        .extracting(SignatureInformation::getLabel)
        .containsExactly("void m(String s)");
    assertThat(signatureHelpAt(source.replace("§", "").formatted("", "§")).getSignatures())
        .extracting(SignatureInformation::getLabel)
        .containsExactly("Hidden(String s)");
  }

  @Test
  void signatureHelp_unattributedCall_activeOverloadFitsArgumentsTyped() {
    final var source =
        """
        class Test {
            Test(int x) {}
            Test(int x, int y) {}
            void m(int x) {}
            void m(int x, int y) {}
            void caller() { %s }
        }
        """;

    final var method = signatureHelpAt(source.formatted("m(\"a\", §"));
    final var constructor = signatureHelpAt(source.formatted("new Test(\"a\", §"));
    final var single = signatureHelpAt(source.formatted("m(§"));

    assertThat(method.getSignatures().get(method.getActiveSignature()).getLabel())
        .isEqualTo("void m(int x, int y)");
    assertThat(method.getActiveParameter()).isEqualTo(1);
    assertThat(constructor.getSignatures().get(constructor.getActiveSignature()).getLabel())
        .isEqualTo("Test(int x, int y)");
    assertThat(single.getSignatures().get(single.getActiveSignature()).getLabel())
        .isEqualTo("void m(int x)");
  }

  // --- activeParam at closing paren ---

  @Test
  void signatureHelp_cursorAfterLastArg_returnsLastParamIndex() {
    final var source =
        """
        class Test {
            void target(String name) {}
            void caller() { target("hello"§); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_cursorAfterLastArgOfTwo_returnsLastParamIndex() {
    final var source =
        """
        class Test {
            void target(String a, int b) {}
            void caller() { target("x", 1§); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getActiveParameter()).isEqualTo(1);
  }

  // --- incomplete source ---

  @Test
  void signatureHelp_incompleteSource_noClosingParen_returnsSignature() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target(§ }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(1);
    assertThat(help.getSignatures().get(0).getLabel()).contains("target");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_incompleteSource_partialArgument_returnsSignature() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target("hello", § }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(1);
    assertThat(help.getActiveParameter()).isEqualTo(1);
  }

  // --- inner call as first argument ---

  @Test
  void signatureHelp_outerCall_firstArgIsMethodCall_returnsOuterSignature() {
    final var source =
        """
        class Test {
          String transform(String s) { return s; }
          void target(String name, int count) {}
          void caller() { target(§transform("x"), 1); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().get(0).getLabel()).contains("String name");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_mapPut_firstArgIsMethodCall_returnsMapPutSignature() {
    final var source =
        """
        import java.util.HashMap;
        import java.util.Map;
        class Test {
          String id() { return "x"; }
          void caller() {
            Map<String, Integer> map = new HashMap<>();
            map.put(§id(), 1);
          }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().get(0).getLabel()).contains("put");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  // --- helpers ---

  // Each label cut to the prefix it is expected to start with, so labels compare without the
  // parameter names a class file may lack.
  private static List<String> labelPrefixes(
      final ClassFileFixture fixture,
      final String source,
      final Map<String, List<String>> expected) {
    final var help = fixture.signatureHelpAt(source);
    if (help == null) {
      return List.of();
    }

    return help.getSignatures().stream()
        .map(SignatureInformation::getLabel)
        .map(
            label ->
                expected.get(source).stream().filter(label::startsWith).findFirst().orElse(label))
        .toList();
  }

  private SignatureHelp signatureHelpAt(final String rawSource) {
    final var source = rawSource.replace(MARKER, "");
    final int markerOffset = rawSource.indexOf(MARKER);
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var pos = Positions.offsetToPosition(source, markerOffset);
    final var request =
        new SourceFeatureRequest(
            TempSourceCompiler.TEST_URI, source, 0, pos, List.of(), LibrarySources.none());
    return session.navigation().signatureHelp(request);
  }
}
