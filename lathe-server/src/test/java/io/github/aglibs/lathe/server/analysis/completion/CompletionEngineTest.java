package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.analysis.CachedAnalysis;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionEngineTest {

  @TempDir private Path tmp;

  private static SourceParser sourceParser;
  private static TempSourceCompiler compiler;
  private static CompletionEngine engine;
  private CompletionEngine eng;

  @BeforeAll
  static void setup() {
    sourceParser = new SourceParser();
    compiler = new TempSourceCompiler();
    engine = new CompletionEngine(sourceParser, compiler, WorkspaceTypeIndex.empty());
  }

  @BeforeEach
  void setUpTypeIndexEngine() throws IOException {
    eng = engineWith();
  }

  @AfterAll
  static void teardown() {
    compiler.close();
    sourceParser.close();
  }

  private static List<CompletionItem> complete(final String markedSource) {
    final var c = cursor(markedSource);
    final var compiled = compiler.compile("file:///Test.java", c.content(), CompileMode.FULL);
    final var cached = new CachedAnalysis(c.content(), 0, compiled.fileAnalysis());
    return engine
        .complete(
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
    return engine
        .complete(
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

  @Test
  void importDeclaration_nonStaticImport_staticMethodNotSuggested() {
    final var items =
        complete(
            """
            import java.util.Collections.empty§;

            class Test {
            }""");
    assertThat(items).noneMatch(i -> i.getLabel().startsWith("emptyList"));
  }

  @Test
  void importDeclaration_staticImport_staticMethodSuggested() {
    final var items =
        complete(
            """
            import static java.util.Collections.empty§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("emptyList"));
  }

  @Test
  void importDeclaration_staticImport_packagePrefix_suggestsPackageSegment() {
    final var items =
        complete(
            """
            import static java.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("util");
  }

  @Test
  void importDeclaration_staticImport_packagePrefix_doesNotSuggestStaticMembers() {
    final var items =
        complete(
            """
            import static java.util.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("Collections", "concurrent");
    assertThat(items).noneMatch(i -> i.getLabel().equals("emptyList"));
  }

  @Test
  void importDeclaration_packageSegmentSuggested() {
    final var items =
        complete(
            """
            import java.ut§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("util");
  }

  @Test
  void importDeclaration_afterPackageDot_suggestsPackageSegment() {
    final var items =
        complete(
            """
            import java.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("util");
  }

  @Test
  void importDeclaration_afterNestedPackageDot_suggestsTypesAndSubpackages() {
    final var items =
        complete(
            """
            import java.util.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("Collections", "concurrent");
  }

  @Test
  void importDeclaration_typeSegmentSuggested() {
    final var items =
        complete(
            """
            import java.util.Col§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("Collections");
  }

  @Disabled("pending class-body declaration completion")
  @Test
  void classBody_emptyDeclaration_suggestsMemberDeclarationStarters() {
    final var items =
        complete(
            """
            class Test {
                private String existing;

                §
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .contains("private", "protected", "public", "static", "final", "class", "interface");
  }

  @Disabled("pending class-body declaration completion")
  @Test
  void classBody_modifierPrefix_suggestsMatchingModifier() {
    final var items =
        complete(
            """
            class Test {
                pri§
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("private");
    assertThat(items).extracting(CompletionItem::getLabel).doesNotContain("protected");
  }

  @Disabled("pending class-body type-name completion")
  @Test
  void classBody_typePrefix_suggestsVisibleTypes() {
    final var items =
        complete(
            """
            class Test {
                Str§
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("String");
  }

  @Disabled("pending class-body type-name completion")
  @Test
  void classBody_afterModifier_suggestsTypesAndNestedDeclarations() {
    final var items =
        complete(
            """
            class Test {
                private §
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .contains("String", "class", "interface", "enum", "record");
  }

  @Disabled("pending method-body statement completion")
  @Test
  void methodBody_emptyStatement_suggestsStatementStarters() {
    final var items =
        complete(
            """
            class Test {
                void run() {
                    §
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .contains("return", "if", "for", "while", "switch", "try", "throw", "new");
  }

  @Disabled("pending method-body type-name completion")
  @Test
  void methodBody_typePrefix_suggestsVisibleTypes() {
    final var items =
        complete(
            """
            class Test {
                void run() {
                    Str§ value;
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("String");
  }

  @Disabled("pending method-body type-name completion")
  @Test
  void methodBody_afterNew_suggestsConstructibleTypes() {
    final var items =
        complete(
            """
            class Test {
                void run() {
                    Object value = new Str§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("StringBuilder");
  }

  @Test
  void staticMethodBody_simpleName_doesNotSuggestInstanceMembers() {
    final var items =
        complete(
            """
            class Test {
                String instanceValue;
                static String staticValue;

                static void run() {
                    §
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("staticValue");
    assertThat(items).extracting(CompletionItem::getLabel).doesNotContain("instanceValue");
  }

  @Disabled("pending simple-name type-reference completion")
  @Test
  void typeReference_methodParam_simpleTypePrefix_suggestsMatchingType() {
    final var items =
        complete(
            """
            class Test {
                void m(Str§ param) {}
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("String");
  }

  @Disabled("pending simple-name type-reference completion")
  @Test
  void typeReference_genericTypeArg_simpleTypePrefix_suggestsMatchingType() {
    final var items =
        complete(
            """
            class Test {
                void m() {
                    java.util.List<Str§> list;
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("String");
  }

  @Disabled("pending simple-name type-reference completion")
  @Test
  void typeReference_genericTypeArg_emptyPrefix_suggestsCommonTypes() {
    final var items =
        complete(
            """
            class Test {
                void m() {
                    java.util.List<§> list;
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("String", "Integer");
  }

  @Disabled("pending extends-clause type-name completion")
  @Test
  void classHeader_extendsPrefix_suggestsSuperclasses() {
    final var items =
        complete(
            """
            class Test extends AbstractL§ {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("AbstractList");
  }

  @Disabled("pending implements-clause type-name completion")
  @Test
  void classHeader_implementsPrefix_suggestsInterfaces() {
    final var items =
        complete(
            """
            class Test implements Runn§ {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("Runnable");
  }

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
    // Presentation policy: wait/notify/finalize are almost always noise.
    // equals/hashCode/toString/getClass may remain available, but should rank below domain members.
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

  @Test
  void memberAccess_privateMemberOnOtherReceiverExcluded() {
    // Private members are valid through this.§ inside the declaring class.
    // They must not be offered through an unrelated receiver expression.
    final var items =
        complete(
            """
            class Other {
                private String secret = "x";
                public String visible = "y";
            }

            class Test {
                void m(Other other) {
                    other.§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("visible");
    assertThat(items).noneMatch(i -> i.getLabel().equals("secret"));
  }

  @Test
  void memberAccess_privateMemberOnThisReceiverIncluded() {
    final var items =
        complete(
            """
            class Test {
                private String secret = "x";

                void m() {
                    this.se§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("secret");
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
  void memberAccess_receiverInsideNewClassArg_completionsReturned() {
    // receiver.§ inside a constructor call argument — NewClassTree parent causes
    // CONSTRUCTOR_CALL classification instead of MEMBER_ACCESS because the guard
    // `when !(sentinel instanceof MemberSelectTree)` is missing on the NewClassTree case
    final var items =
        complete(
            """
            class Test {
                void m() {
                    String hello = "x";
                    new StringBuilder(hello.§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void argumentPosition_emptyPrefix_suggestsVisibleLocal() {
    final var items =
        complete(
            """
            class Test {
                static class ReceiverFactory {
                    static Receiver create() { return new Receiver(); }
                }

                static class Receiver {
                    void accept(String value) {}
                }

                void m() {
                    String value = "";
                    ReceiverFactory.create().accept(§value);
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("value");
  }

  @Test
  void argumentPosition_prefix_suggestsVisibleLocal() {
    final var items =
        complete(
            """
            class Test {
                static void accept(String value) {}

                void m() {
                    String value = "";
                    accept(val§);
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("value");
  }

  @Test
  void constructorCall_emptyArgument_suggestsVisibleLocal() {
    final var items =
        complete(
            """
            class Test {
                static class Receiver {
                    Receiver(String value) {}
                }

                void m() {
                    String value = "";
                    new Receiver(§value);
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("value");
  }

  @Test
  void constructorCall_prefix_suggestsVisibleLocal() {
    final var items =
        complete(
            """
            class Test {
                static class Receiver {
                    Receiver(String value) {}
                }

                void m() {
                    String value = "";
                    new Receiver(val§);
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("value");
  }

  @Test
  void argumentPosition_lambdaParam_suggestsVisibleParam() {
    final var items =
        complete(
            """
            class Test {
                static void consume(Object value) {}

                void m(java.util.List<String> list) {
                    list.forEach(value -> consume(val§));
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("value");
  }

  @Test
  void argumentPosition_switchPatternVar_suggestsVisiblePatternVar() {
    final var items =
        complete(
            """
            class Test {
                static void consume(Object value) {}

                void m(Object object) {
                    switch (object) {
                        case String value -> consume(val§);
                        default -> {}
                    }
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("value");
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

  @Test
  void memberAccess_staticEnumReceiver_enumConstantsReturned() {
    final var items =
        complete(
            """
            enum Kind {
                FIRST,
                SECOND
            }

            class Test {
                void m() {
                    Kind.§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("FIRST", "SECOND");
  }

  // ── pending: additional TypeResolver gaps ──────────────────────────────────

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
  void importDeclaration_staleCacheDotTrigger_packageSuggested() {
    // Simulates dot-trigger: cached content has no dot yet, completion fires
    // from the stale snapshot before the 500ms recompile.
    final var items =
        completeWithCache(
            "import java;\nclass Test {}",
            """
            import java.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("util");
  }

  @Test
  void importDeclaration_staleCacheDotTrigger_typeSuggested() {
    final var items =
        completeWithCache(
            "import java.util;\nclass Test {}",
            """
            import java.util.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("Collections");
  }

  @Test
  void importDeclaration_staticImport_staleCacheDotTrigger_packageSuggested() {
    final var items =
        completeWithCache(
            "import static java;\nclass Test {}",
            """
            import static java.§;

            class Test {
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).contains("util");
  }

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

  // ── multiline chains ──────────────────────────────────────────────────────
  // Three distinct failure modes, each with its own test:
  //
  // 1. Sentinel-line mismatch (SentinelParser): for a MemberSelectTree that spans
  //    multiple lines, getStartPosition returns the start of the ENTIRE chain, not
  //    the cursor line. Fix: use getEndPosition (always on the cursor's line).
  //
  // 2. Dangling continuation lines: when the cursor sits at the first call in a
  //    chain, injection produces __LATHE_SENTINEL__; followed by the remaining
  //    .method() calls as parse errors. Parser must still find the sentinel.
  //
  // 3. Cross-line receiver (CompletionEngine): when the receiver ends on line N and
  //    the dot is on line N+1, dotOffset > getEndPosition(receiver). Fix:
  //    skipBackWhitespace adjusts dotOffset to position right after receiver's last
  //    character, restoring the exact-match invariant for resolveByPosition.
  //    Note: simple-identifier receivers bypass resolveByPosition entirely
  //    (scanForLocalDeclaration) and therefore work without the fix.

  @Test
  void multilineChain_threeLines_completionOnSameLine() {
    // return new StringBuilder()           ← chain starts line 2 (0-indexed)
    //         .append("a")                 ← line 3
    //         .append("b").ap§             ← cursor line 4; receiver append("b") on THIS line
    // getStartPosition of __SENTINEL__ = start of chain = line 2 ≠ expectedLspLine 4
    final var items =
        complete(
            """
            class Test {
                String m() {
                    return new StringBuilder()
                            .append("a")
                            .append("b").ap§
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("append"));
  }

  @Test
  void multilineChain_cursorAtFirstCall_continuationLinesBelow() {
    // forTesting().§               ← cursor here; continuation lines exist below
    //         .packages(...)
    //         .register(...)
    // After injection: forTesting().__LATHE_SENTINEL__; followed by dangling .packages(...)
    // lines. The sentinel line check must pass AND resolveByPosition must match forTesting().
    final var items =
        complete(
            """
            class Test {
                StringBuilder makeBuilder() { return new StringBuilder(); }
                void m() {
                    makeBuilder().§
                            .append("x")
                            .append("y");
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("append"));
  }

  @Test
  void multilineChain_simpleIdentifierReceiver_crossLine() {
    // bufferedReader                ← simple local var, line N  (AbstractServerFactory pattern)
    //     .§toUp                    ← dot on line N+1
    // TypeResolver takes scanForLocalDeclaration path (name lookup, no position matching)
    // so cross-line placement is fine; contrast with method-call receiver case below.
    final var items =
        complete(
            """
            class Test {
                void m() {
                    String s = "hello";
                    s
                        .§toUp
                }
            }""");
    assertThat(items).extracting(CompletionItem::getLabel).anyMatch(l -> l.startsWith("toUpper"));
  }

  @Test
  void multilineChain_receiverOnPreviousLine_crossLineCompletion() {
    // foo()                              ← receiver ends on line 3 (0-indexed)
    //     .toL§                          ← dot is on line 4
    // dotOffset (position of '.') > getEndPosition(foo()) because of the whitespace gap.
    // Fix: skipBackWhitespace adjusts dotOffset to position right after receiver's ')'.
    final var items =
        complete(
            """
            class Test {
                String foo() { return ""; }
                void m() {
                    foo()
                        .toL§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("toLowerCase"));
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

  // ── probe-derived: real-world patterns (UX analysis on dropwizard/helidon) ───
  // Patterns observed while probing AbstractServerFactory.java,
  // ConstraintViolationExceptionMapperTest.java, and MongoDbClient.java.
  // Probe cursor positions had off-by-one errors (cursor ON the dot), so engine
  // behaviour at the correct position was not verified. Each test documents one
  // pattern that requires a specific TypeResolver path to work.

  @Test
  void memberAccess_overloadedMethodCallReceiver_correctReturnTypeResolved() {
    // assertThat(response.getStatus()).isEqualTo§
    // Outer receiver assertThat(getStatus()) is a MethodInvocationTree whose argument
    // is itself a MethodInvocationTree. resolveByPosition must attribute the outer call
    // to the int-accepting overload and return IntAssert, not StrAssert.
    final var items =
        complete(
            """
            class Test {
                static class IntAssert {
                    IntAssert isEqualTo(int v) { return this; }
                    IntAssert isGreaterThan(int v) { return this; }
                }
                static class StrAssert {
                    StrAssert isEqualTo(String v) { return this; }
                    StrAssert contains(String s) { return this; }
                }
                static IntAssert assertThat(int v) { return new IntAssert(); }
                static StrAssert assertThat(String v) { return new StrAssert(); }
                int getStatus() { return 200; }
                void m() {
                    assertThat(getStatus()).isEqual§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("isEqualTo"));
    // StrAssert-only members must not appear — confirms the right overload was picked
    assertThat(items).noneMatch(i -> i.getLabel().startsWith("contains"));
  }

  @Test
  void memberAccess_methodCallReceiver_argumentContainsClassLiteral_returnTypeResolved() {
    // assertThat(response.readEntity(String.class)).isEqualTo§
    // The receiver's argument contains a .class literal: readEntity(String.class).
    // collectReceiver must scan past the nested parens and the dotted literal without
    // breaking early on the '.' inside 'String.class'.
    final var items =
        complete(
            """
            class Test {
                static class StrAssert {
                    StrAssert isEqualTo(String v) { return this; }
                    StrAssert contains(String s) { return this; }
                    StrAssert startsWith(String s) { return this; }
                }
                static StrAssert assertThat(String v) { return new StrAssert(); }
                static <T> T readEntity(Class<T> cls) { return null; }
                void m() {
                    assertThat(readEntity(String.class)).isEqual§
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("isEqualTo"));
  }

  @Test
  void memberAccess_methodParam_insideConstructorCallArg_typeResolved() {
    // new ConnectionString(config.url§)
    // config is a method parameter, not a local variable. collectReceiver stops at the
    // opening '(' of ConnectionString, yielding "config". scanForLocalDeclaration must
    // then find it among the enclosing method's parameters, not only its local vars.
    final var items =
        complete(
            """
            class Test {
                static class Config {
                    String url() { return ""; }
                    String username() { return ""; }
                }
                static class Connection {
                    Connection(String url) {}
                }
                void m(Config config) {
                    new Connection(config.ur§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("url"));
  }

  @Test
  void memberAccess_localVar_insideStaticFactoryCallArg_typeResolved() {
    // MongoClients.create(settingsBuilder.build§)
    // settingsBuilder is a local variable; the cursor sits inside an argument to a
    // static factory. The EXPRESSION context (backwardScan detects unclosed '(') must
    // not interfere with MEMBER_ACCESS routing after SentinelParser classifies via AST.
    final var items =
        complete(
            """
            class Test {
                static class Builder {
                    Object build() { return null; }
                    Builder credential(String c) { return this; }
                }
                static Object create(Object settings) { return null; }
                void m() {
                    Builder settingsBuilder = new Builder();
                    create(settingsBuilder.buil§);
                }
            }""");
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .anyMatch(l -> l.startsWith("build"));
  }

  // --- type index: helpers ---

  private CompletionEngine engineWith() throws IOException {
    return engineWith(typeEntry("FooService", "com.example.FooService", TypeKind.CLASS));
  }

  private CompletionEngine engineWith(final TypeIndexEntry... entries) throws IOException {
    final var shardPath = writeTypeIndexShard(tmp.resolve("type-index.json"), List.of(entries));
    return new CompletionEngine(
        sourceParser, compiler, WorkspaceTypeIndex.build(List.of(shardPath)));
  }

  private static List<CompletionItem> completeWith(
      final CompletionEngine eng, final String markedSource) {
    return eng.complete(completionRequest(markedSource)).items();
  }

  private static List<CompletionItem> completeWithCurrentContentCached(
      final CompletionEngine eng, final String markedSource) {
    final var c = cursor(markedSource);
    final var compiled = compiler.compile("file:///Test.java", c.content(), CompileMode.FULL);
    final var cached = new CachedAnalysis(c.content(), 0, compiled.fileAnalysis());
    return eng.complete(
            new CompletionRequest(
                "file:///Test.java",
                c.content(),
                new Position(c.lspLine(), c.lspChar()),
                null,
                cached))
        .items();
  }

  private static CompletionRequest completionRequest(final String markedSource) {
    final var c = cursor(markedSource);
    return new CompletionRequest(
        "file:///Test.java", c.content(), new Position(c.lspLine(), c.lspChar()), null, null);
  }

  private static Path writeTypeIndexShard(final Path shardPath, final List<TypeIndexEntry> entries)
      throws IOException {
    Json.write(
        new TypeIndexFile(
            "v1",
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin("test:lib:1.0", "/lib.jar", 0L, 0L)),
            entries),
        shardPath);
    return shardPath;
  }

  private static TypeIndexEntry typeEntry(
      final String simpleName, final String qualifiedName, final TypeKind kind) {
    final var pkg = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
    return new TypeIndexEntry(simpleName, qualifiedName, pkg, kind);
  }

  // --- type index: positions where completion fires ---

  @Test
  void typeIndex_methodParam_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    void m(FooServ§ p) {}
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_genericTypeArg_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    void m() {
                        java.util.List<FooServ§> list;
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_fieldDeclaration_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    FooServ§ field;
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_methodReturnType_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    FooServ§ getService() { return null; }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_shortPrefixValidationFirstPageUnderfills_suggestsReachableLaterCandidate()
      throws IOException {
    final List<TypeIndexEntry> entries =
        Stream.concat(
                // Put the reachable candidate after the current 200-candidate validation page.
                IntStream.range(0, 220)
                    .mapToObj(
                        i ->
                            typeEntry(
                                "Maa%03d".formatted(i),
                                "missing.Maa%03d".formatted(i),
                                TypeKind.CLASS)),
                Stream.of(typeEntry("Map", "java.util.Map", TypeKind.INTERFACE)))
            .toList();
    final var indexedEngine =
        new CompletionEngine(
            sourceParser,
            compiler,
            WorkspaceTypeIndex.build(
                List.of(writeTypeIndexShard(tmp.resolve("short-prefix-underfill.json"), entries))));

    assertThat(
            completeWithCurrentContentCached(
                indexedEngine,
                """
                class Test {
                    M§ field;
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("Map");
  }

  @Test
  void typeIndex_completionOutcome_marksResultsIncomplete() {
    final var outcome =
        eng.complete(
            completionRequest(
                """
                class Test {
                    FooServ§ field;
                }"""));

    assertThat(outcome.items()).extracting(CompletionItem::getLabel).contains("FooService");
    assertThat(outcome.incomplete()).isTrue();
  }

  @Test
  void typeIndex_candidates_rankExactCaseAndJavaLangFirst() throws IOException {
    final var indexedEngine =
        engineWith(
            typeEntry("MoBeta", "com.example.MoBeta", TypeKind.CLASS),
            typeEntry("moAlpha", "com.example.moAlpha", TypeKind.CLASS),
            typeEntry("MoString", "java.lang.MoString", TypeKind.CLASS));

    assertThat(
            completeWith(
                indexedEngine,
                """
                class Test {
                    Mo§ field;
                }"""))
        .extracting(CompletionItem::getLabel)
        .containsExactly("MoString", "MoBeta", "moAlpha");
  }

  // --- type index: gaps ---

  @Test
  void typeIndex_bareUppercasePrefixInMethodBody_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    void m() {
                        FooServ§
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_bareUppercasePrefixInConstructorBody_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    Test() {
                        FooServ§
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_bareUppercasePrefixAfterConstructorCall_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    void m() {
                        new StringBuilder();
                        FooServ§
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_constructorCallTypePrefix_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    void m() {
                        Object service = new FooServ§
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_bareLowercasePrefixInMethodBody_doesNotSuggestIndexedType() throws IOException {
    final var indexedEngine =
        engineWith(typeEntry("fooService", "com.example.fooService", TypeKind.CLASS));

    assertThat(
            completeWith(
                indexedEngine,
                """
                class Test {
                    void m() {
                        fooServ§
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .doesNotContain("fooService");
  }

  @Test
  void typeIndex_localVariable_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test {
                    void m() {
                        FooServ§ local;
                    }
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_extendsClause_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test extends FooServ§ {
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeIndex_implementsClause_suggestsIndexedType() {
    assertThat(
            completeWith(
                eng,
                """
                class Test implements FooServ§ {
                }"""))
        .extracting(CompletionItem::getLabel)
        .contains("FooService");
  }

  @Test
  void typeReference_simpleNameFromTypeIndex_returnsIndexedType() throws IOException {
    final var indexedEngine = engineWith();

    final var items =
        completeWith(
            indexedEngine,
            """
            class Test {
                void m(FooServ§ param) {}
            }""");

    assertThat(items).extracting(CompletionItem::getLabel).contains("FooService");
    assertThat(items).extracting(CompletionItem::getDetail).contains("com.example.FooService");
  }
}
