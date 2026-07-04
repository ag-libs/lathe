package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnusedDeclarationScannerTest {

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
  void compile_privateMethodDeclaredAfterCaller_noHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void api() { helper(); }
                  private void helper() {}
                }
                """))
        .isEmpty();
  }

  @Test
  void compile_privateMethodCalledViaThis_noHint() {
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void api() { this.helper(); }
                  private void helper() {}
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

  @Test
  void compile_localVariableAssignedNeverRead_reportsHint() {
    // 'count' is declared, assigned, but its value is never read — the declaration and both
    // writes are dead code. The assignment LHS must not count as a use.
    final var source =
        """
        class Test {
          public void method() {
            int count = 0;
            count = compute();
          }
          private int compute() {
            return 1;
          }
        }
        """;

    final List<Diagnostic> hints = unusedHintsFor(source);

    assertThat(hints).hasSize(1);
    assertThat(hints.getFirst().getRange().getStart())
        .isEqualTo(SourceLocator.offsetToPosition(source, source.indexOf("count")));
    assertThat(hints.getFirst().getMessage().getLeft())
        .isEqualTo("local variable 'count' is assigned but never read");
  }

  @Test
  void compile_localVariableAssignedThenRead_noHint() {
    // The value is read after assignment, so the variable is genuinely used.
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public int method() {
                    int count = 0;
                    count = compute();
                    return count;
                  }
                  private int compute() {
                    return 1;
                  }
                }
                """))
        .isEmpty();
  }

  @Test
  void compile_localVariableCompoundAssignment_noHint() {
    // `count += 1` reads before it writes, so the read half keeps the variable used.
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void method() {
                    int count = 0;
                    count += 1;
                  }
                }
                """))
        .isEmpty();
  }

  @Test
  void compile_privateFieldWriteOnly_reportsHint() {
    // 'cached' is only ever written (declaration + this-qualified assignment), never read.
    final var source =
        """
        class Test {
          private int cached = 0;
          public void refresh() {
            this.cached = 1;
          }
        }
        """;

    final List<Diagnostic> hints = unusedHintsFor(source);

    assertThat(hints).hasSize(1);
    assertThat(hints.getFirst().getRange().getStart())
        .isEqualTo(SourceLocator.offsetToPosition(source, source.indexOf("cached")));
    assertThat(hints.getFirst().getMessage().getLeft())
        .isEqualTo("private field 'cached' is assigned but never read");
  }

  @Test
  void compile_localVarWithUnresolvedType_noUnusedHint() {
    // Gap: scanner runs even when compilation fails and emits Unused on a variable that is
    // "unused" only because its type is broken, not because the programmer forgot to use it.
    assertThat(
            unusedHintsFor(
                """
                class Test {
                  public void method() {
                    UnknownType foo = new UnknownType();
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

  // --- Records ---

  @Test
  void compile_recordComponents_noHints() {
    assertThat(
            unusedHintsFor(
                """
                record Point(int x, int y) {}
                """))
        .isEmpty();
  }

  @Test
  void compile_recordUnusedPrivateStaticField_reportsHint() {
    assertThat(
            unusedHintsFor(
                """
                record Point(int x, int y) {
                  private static final int CACHE_SIZE = 10;
                }
                """))
        .hasSize(1);
  }

  @Test
  void compile_recordUnusedPrivateStaticMethod_reportsHint() {
    assertThat(
            unusedHintsFor(
                """
                record Point(int x, int y) {
                  private static int compute() { return 0; }
                }
                """))
        .hasSize(1);
  }

  // --- Diagnostic message and code ---

  @Test
  void unused_localVariable_messageNamesVariableAndKind() {
    final List<Diagnostic> hints =
        unusedHintsFor(
            """
            class Test {
              public void method() {
                int unused = 42;
              }
            }
            """);

    assertThat(hints).hasSize(1);
    assertThat(hints.getFirst().getMessage().getLeft()).isEqualTo("Unused local variable 'unused'");
  }

  @Test
  void unused_diagnostic_setsStableCode() {
    final List<Diagnostic> hints =
        unusedHintsFor(
            """
            class Test {
              private int unused = 0;
            }
            """);

    assertThat(hints).hasSize(1);
    assertThat(hints.getFirst().getCode()).isEqualTo(Either.forLeft("lathe.unused"));
  }

  // --- helpers ---

  private List<Diagnostic> unusedHintsFor(final String source) {
    return session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN).stream()
        .filter(UnusedDeclarationScannerTest::isUnusedHint)
        .toList();
  }

  private static boolean isUnusedHint(final Diagnostic diag) {
    return diag.getSeverity() == DiagnosticSeverity.Hint
        && diag.getTags() != null
        && diag.getTags().contains(DiagnosticTag.Unnecessary);
  }
}
