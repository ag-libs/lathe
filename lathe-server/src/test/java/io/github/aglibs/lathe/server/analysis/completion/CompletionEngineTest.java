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
    engine = new CompletionEngine(sourceParser, compiler);
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
                cached))
        .items();
  }

  /**
   * Simulates a stale cache: the snapshot is compiled from {@code cachedSource} (an older version
   * of the file), while {@code currentMarkedSource} is what the user is currently typing. Positions
   * in the cached tree may not match expressions in the current content.
   */
  private static List<CompletionItem> completeWithCache(
      final String cachedSource, final String currentMarkedSource) {
    final var c = cursor(currentMarkedSource);
    final var cachedCompiled =
        compiler.compile("file:///Test.java", cachedSource, CompileMode.FULL);
    final var cached = new CachedAnalysis(cachedSource, 0, cachedCompiled.fileAnalysis());
    return engine.complete(
            new CompletionRequest(
                "file:///Test.java",
                c.content(),
                new Position(c.lspLine(), c.lspChar()),
                null,
                cached))
        .items();
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

  @Test
  void topLevel_expressionBeforeClass_returnsEmpty() {
    // "foo." before any class body → javac parses as VariableTree → VARIABLE_DECLARATION context
    // → engine returns empty without entering MEMBER_ACCESS path
    final var items = complete("foo.§\nclass Test {}");
    assertThat(items).isEmpty();
  }

  @Disabled("pending TYPE_REFERENCE nested type completion in CompletionEngine")
  @Test
  void typeReference_dottedOuterClass_innerTypeSuggested() {
    final var items =
        complete(
            """
            class Test {
                void m(java.util.Map.En§ entry) {}
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("Entry"));
  }

  @Disabled("pending SIMPLE_NAME scope enumeration in ProposalGenerator")
  @Test
  void simpleName_localVar_suggestedByPrefix() {
    final var items =
        complete(
            """
            class Test {
                void m() {
                    String hello = "x";
                    hel§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("hello");
  }

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
  void memberAccess_complexReceiver_returnTypeResolved() {
    // method-call receiver — needs Trees.getTypeMirror on the call expression
    final var items =
        complete(
            """
            class Test {
                java.util.List<String> getList() { return null; }
                void m() {
                    getList().sub§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("subList"));
  }

  @Test
  void memberAccess_receiverInsideArgument_completionsReturned() {
    // receiver.§ inside a method call argument — SentinelParser misclassifies as ARGUMENT_POSITION
    final var items =
        complete(
            """
            class Test {
                static void consume(String s) {}
                void m() {
                    String hello = "x";
                    consume(hello.§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_samePackageType_staticMembersReturned() {
    // Helper is in the same named package — no import — TypeResolver must fall back to
    // packageName + "." + simpleName
    final var items =
        complete(
            """
            package com.example;
            class Helper {
                static String greet() { return "hi"; }
            }
            class Test {
                void m() {
                    Helper.§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("greet"));
  }

  @Test
  void memberAccess_starImport_staticMembersReturned() {
    // on-demand import — resolveViaImports only handles single-class imports
    final var items =
        complete(
            """
            import java.util.*;
            class Test {
                void m() {
                    Collections.§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("emptyList"));
  }

  // ── pending: additional TypeResolver gaps ──────────────────────────────────

  @Disabled("pending NewClassTree support in resolveByPosition")
  @Test
  void memberAccess_newClassReceiver_methodsReturned() {
    // new Foo().§ — receiver is a NewClassTree; resolveByPosition only visits
    // MethodInvocationTree / MemberSelectTree / IdentifierTree
    final var items =
        complete(
            """
            class Test {
                void m() {
                    new StringBuilder().ap§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("append"));
  }

  @Test
  void memberAccess_instanceFieldChain_typeResolved() {
    // this.field.§ — receiverText is "this.name", dotted-name branch tries
    // getTypeElement("this.name") which is null and returns without resolveByPosition
    final var items =
        complete(
            """
            class Test {
                String name = "x";
                void m() {
                    this.name.toL§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_staticFieldChain_typeResolved() {
    // System.out.§ — receiverText "System.out" is a field access, not a type FQN;
    // getTypeElement("System.out") returns null and the branch exits without position fallback
    final var items =
        complete(
            """
            class Test {
                void m() {
                    System.out.print§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("print"));
  }

  @Disabled("pending ArrayAccessTree support in resolveByPosition")
  @Test
  void memberAccess_arrayElementReceiver_typeResolved() {
    // arr[0].§ — receiver has '[', goes to resolveByPosition, but ArrayAccessTree
    // is not visited so end-position match never fires
    final var items =
        complete(
            """
            class Test {
                void m(String[] arr) {
                    arr[0].toL§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Disabled("pending TypeCastTree support in resolveByPosition")
  @Test
  void memberAccess_castReceiver_typeResolved() {
    // ((Type) expr).§ — receiver has ' ', goes to resolveByPosition, but TypeCastTree
    // is not visited so end-position match never fires
    final var items =
        complete(
            """
            class Test {
                void m(Object obj) {
                    ((String) obj).toL§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  // ── stream API chains ─────────────────────────────────────────────────────

  @Test
  void streamChain_streamMethods_returned() {
    // list.stream().§ — MethodInvocationTree for stream() attributed as Stream<String>;
    // checks that stream-specific methods (filter, map) are returned
    final var items =
        complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.stream().§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("filter"));
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("map"));
  }

  @Test
  void lambdaBody_methodCallReceiver_typeResolved() {
    // s.trim().to§ inside a lambda — receiver is a method call, not a simple name;
    // resolveByPosition must find the MethodInvocationTree for trim() inside the lambda body
    final var items =
        complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.forEach(s -> s.trim().to§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void builderChain_methodCallChain_typeResolved() {
    // new StringBuilder().append("x").ap§ — receiver is a MethodInvocationTree (append),
    // not a bare NewClassTree; resolveByPosition must match its end position at dotOffset
    final var items =
        complete(
            """
            class Test {
                void m() {
                    new StringBuilder().append("x").ap§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("append"));
  }

  // ── pending: stale-cache gaps ──────────────────────────────────────────────

  @Test
  void streamChain_staleCacheFilterLambda_typeResolved() {
    // User types a new stream chain line not present at last compile time.
    // The lambda param s is inferred from the stream element type, but the
    // entire expression is absent from the cached snapshot tree.
    final var items =
        completeWithCache(
            """
            class Test {
                void m(java.util.List<String> list) {
                }
            }""",
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.stream().filter(s -> s.to§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_staleCacheNewLine_typeResolved() {
    // User typed a brand-new line that was not present at last compile time.
    // resolveByPosition walks the cached snapshot and finds no MethodInvocationTree
    // at dotOffset because the node simply doesn't exist in the older tree.
    final var items =
        completeWithCache(
            """
            class Test {
                java.util.List<String> getList() { return null; }
                void m() {
                }
            }""",
            """
            class Test {
                java.util.List<String> getList() { return null; }
                void m() {
                    getList().sub§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("subList"));
  }

  @Test
  void memberAccess_staleCacheReplacedExpression_typeResolved() {
    // User replaced an existing expression in-place: the cached snapshot has a
    // different call at the same source position, so the dotOffset from the current
    // content hits a node with the wrong type in the cached tree.
    final var items =
        completeWithCache(
            """
            class Test {
                String foo() { return ""; }
                int bar() { return 0; }
                void m() {
                    bar();
                }
            }""",
            """
            class Test {
                String foo() { return ""; }
                int bar() { return 0; }
                void m() {
                    foo().toL§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }
}
