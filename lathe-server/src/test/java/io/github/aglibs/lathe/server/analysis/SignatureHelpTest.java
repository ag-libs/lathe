package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.SignatureHelp;
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("String name");
    assertThat(help.getSignatures().getFirst().getLabel()).contains("int count");
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
    assertThat(help.getSignatures().getFirst().getParameters()).isEmpty();
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("target");
    assertThat(help.getSignatures().getFirst().getLabel()).contains("String name");
    assertThat(help.getSignatures().getFirst().getLabel()).contains("int count");
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("target");
    assertThat(help.getSignatures().getFirst().getParameters()).isEmpty();
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("inner");
    assertThat(help.getSignatures().getFirst().getLabel()).contains("int x");
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("inner");
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("Point");
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
    final var sig = help.getSignatures().getFirst();
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
      final var methodSig = methodHelp.getSignatures().getFirst();
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
    final var sig = help.getSignatures().getFirst();
    assertThat(sig.getDocumentation()).isNotNull();
    assertThat(sig.getDocumentation().getRight().getValue())
        .contains("Says hello")
        .doesNotContain("@param");
    assertThat(sig.getParameters().getFirst().getDocumentation().getRight().getValue())
        .contains("the message");
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("target");
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("String name");
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
    assertThat(help.getSignatures().getFirst().getLabel()).contains("put");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  // --- helpers ---

  private SignatureHelp signatureHelpAt(final String rawSource) {
    final var source = rawSource.replace(MARKER, "");
    final int markerOffset = rawSource.indexOf(MARKER);
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var pos = SourceLocator.offsetToPosition(source, markerOffset);
    final var request =
        new SourceFeatureRequest(
            TempSourceCompiler.TEST_URI, source, pos, List.of(), WorkspaceManifest.empty());
    return session.signatureHelp(request);
  }
}
