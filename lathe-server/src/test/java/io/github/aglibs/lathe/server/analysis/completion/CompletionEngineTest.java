package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.CachedAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class CompletionEngineTest {

  private static SourceParser sourceParser;
  private static TempSourceCompiler compiler;
  private static CompletionEngine engine;

  @BeforeAll
  static void setup() {
    sourceParser = new SourceParser();
    compiler = new TempSourceCompiler();
    engine = new CompletionEngine(sourceParser);
  }

  @AfterAll
  static void teardown() throws Exception {
    compiler.close();
    sourceParser.close();
  }

  private static List<CompletionItem> complete(final String markedSource) {
    final var c = cursor(markedSource);
    final var compiled = compiler.compile("file:///Test.java", c.content(), CompileMode.FULL);
    final var cached = new CachedAnalysis(c.content(), 0, compiled.fileAnalysis());
    return engine.complete(
        new CompletionRequest(
            "file:///Test.java",
            c.content(),
            new Position(c.lspLine(), c.lspChar()),
            null,
            cached));
  }

  @Test
  void memberAccess_instanceMethod_prefixFiltered() {
    final var items =
        complete(
            """
            class Test {
                void m(java.util.ArrayList<String> list) {
                    list.sub§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("subList"));
    assertThat(items).noneMatch(i -> i.getLabel().startsWith("size"));
  }

  @Test
  void memberAccess_thisReceiver_fieldIncluded() {
    final var items =
        complete(
            """
            class Test {
                String name = "x";
                void m() {
                    this.na§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("name");
  }

  @Test
  void memberAccess_staticFqnReceiver_staticMethodIncluded() {
    final var items =
        complete(
            """
            class Test {
                void m() {
                    java.util.Collections.empty§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("emptyList"));
  }

  @Test
  void stringLiteral_noCompletions() {
    final var items =
        complete(
            """
            class Test {
                void m() {
                    String s = "hello§";
                }
            }""");
    assertThat(items).isEmpty();
  }

  @Disabled("pending nodeLine <= cursorLine fix in TypeResolver.scanForLocalDeclaration")
  @Test
  void memberAccess_methodParamSameLine_typeResolved() {
    // param declared on same line as cursor — needs nodeLine <= cursorLine in
    // scanForLocalDeclaration
    final var items =
        complete(
            """
            class Test {
                void m(String s) { s.to§ }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Disabled("pending LAMBDA_BODY context handling in CompletionEngine")
  @Test
  void lambdaBody_thisReceiver_membersReturned() {
    // LAMBDA_BODY context with 'this' receiver — isolates engine routing fix from param resolution
    final var items =
        complete(
            """
            class Test {
                String name = "x";
                void m(java.util.List<String> list) {
                    list.forEach(s -> this.na§);
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("name");
  }

  @Disabled("pending string literal receiver support in TypeResolver")
  @Test
  void memberAccess_stringLiteralReceiver_stringMethodsReturned() {
    final var items =
        complete(
            """
            class Test {
                void m() {
                    "hello".to§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Disabled("pending LAMBDA_BODY context handling and scanForLocalDeclaration <= fix")
  @Test
  void lambdaBody_memberAccess_paramTypeResolved() {
    final var items =
        complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.forEach(s -> s.to§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  // ── known issues ────────────────────────────────────────────────────────────

  @Disabled("pending parameterized type resolution in ProposalGenerator")
  @Test
  void memberAccess_typeArgResolved_notRawTypeVar() {
    // List<String>.add§ should show add(String), not add(E)
    final var items =
        complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.add§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("add(String)");
    assertThat(items).noneMatch(i -> i.getLabel().equals("add(E)"));
  }

  @Disabled("pending Object boilerplate filter in ProposalGenerator")
  @Test
  void memberAccess_objectBoilerplateExcluded() {
    // wait/notify/clone/finalize are noise and should not appear
    final var items =
        complete(
            """
            class Test {
                void m(java.util.ArrayList<String> list) {
                    list.§
                }
            }""");
    assertThat(items)
        .noneMatch(
            i ->
                i.getLabel().startsWith("wait(")
                    || i.getLabel().equals("notify()")
                    || i.getLabel().equals("notifyAll()")
                    || i.getLabel().equals("finalize()"));
  }

  @Disabled("pending Trees.isAccessible(Scope, Element, DeclaredType) implementation")
  @Test
  void memberAccess_privateMemberExcluded() {
    // private members of the receiver type must not appear
    final var items =
        complete(
            """
            class Test {
                private String secret = "x";
                public String visible = "y";
                void m() {
                    this.§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("visible");
    assertThat(items).noneMatch(i -> i.getLabel().equals("secret"));
  }

  @Test
  void memberAccess_complexReceiver_isEmpty() {
    final var items =
        complete(
            """
            class Test {
                java.util.List<String> getList() { return null; }
                void m() {
                    getList().sub§
                }
            }""");
    assertThat(items).isEmpty();
  }
}
