package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnusedDeclarationScannerTest {

  private static final String URI = "file:///Test.java";

  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() {
    session = new SourceAnalysisSession(new TempSourceCompiler());
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  // --- Unused private methods ---

  @Test
  void compile_unusedPrivateMethod_reportsHintOnName() {
    final var source =
        """
        class Test {
          private void unused() {}
          public void api() {}
        }
        """;

    final List<Diagnostic> hints = unusedHintsFor(source);

    assertThat(hints).hasSize(1);
    assertThat(hints.getFirst().getRange().getStart())
        .isEqualTo(SourceLocator.offsetToPosition(source, source.indexOf("unused")));
  }

  @Test
  void compile_privateMethodCalledFromPublic_noHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  private void helper() {}
                  public void api() { helper(); }
                }
                """))
        .isEmpty();
  }

  @Test
  void compile_selfRecursivePrivateMethod_reportsHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  private void loop() { loop(); }
                }
                """))
        .hasSize(1);
  }

  @Test
  void compile_mutuallyRecursiveUnusedMethods_reportsBothHints() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  private void a() { b(); }
                  private void b() { a(); }
                }
                """))
        .hasSize(2);
  }

  @Test
  void compile_privateNoArgConstructor_noHint() {
    assertThat(
            unusedHintsFor(
                """
                class Utility {
                  private Utility() {}
                  public static void doThing() {}
                }
                """))
        .isEmpty();
  }

  // --- Visibility: only private is flagged ---

  @Test
  void compile_publicProtectedPackagePrivateMethods_noHints() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void pub() {}
                  protected void prot() {}
                  void pkg() {}
                }
                """))
        .isEmpty();
  }

  // --- Unused private fields ---

  @Test
  void compile_unusedPrivateField_reportsHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  private int unused = 0;
                }
                """))
        .hasSize(1);
  }

  @Test
  void compile_privateFieldReadInMethod_noHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  private int value = 0;
                  public int get() { return value; }
                }
                """))
        .isEmpty();
  }

  @Test
  void compile_serialVersionUid_noHint() {
    assertThat(
            unusedHintsFor(
                """
                import java.io.Serializable;
                class Test implements Serializable {
                  private static final long serialVersionUID = 1L;
                }
                """))
        .isEmpty();
  }

  // --- Unused local variables ---

  @Test
  void compile_unusedLocalVariable_reportsHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void method() {
                    int unused = 42;
                  }
                }
                """))
        .hasSize(1);
  }

  @Test
  void compile_usedLocalVariable_noHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void method() {
                    int value = 42;
                    System.out.println(value);
                  }
                }
                """))
        .isEmpty();
  }

  // --- Inner classes ---

  @Test
  void compile_unusedPrivateMethodInInnerClass_reportsHint() {
    assertThat(
            unusedHintsFor(
                """
                class Outer {
                  class Inner {
                    private void unused() {}
                  }
                }
                """))
        .hasSize(1);
  }

  // --- helpers ---

  private List<Diagnostic> unusedHintsFor(final String source) {
    return session.compile(URI, source, 1, CompileMode.OPEN).stream()
        .filter(UnusedDeclarationScannerTest::isUnusedHint)
        .toList();
  }

  private static boolean isUnusedHint(final Diagnostic diag) {
    return diag.getSeverity() == DiagnosticSeverity.Hint
        && diag.getTags() != null
        && diag.getTags().contains(DiagnosticTag.Unnecessary);
  }
}
