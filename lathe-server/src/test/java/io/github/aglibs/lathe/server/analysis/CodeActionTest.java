package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.StandardLocation;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeActionTest {
  @TempDir private Path tmp;

  private WorkspaceTypeIndex typeIndex;
  private TempSourceCompiler compiler;
  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() throws IOException {
    typeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("index.json"),
            new TypeIndexEntry(
                "ArrayList", "java.util.ArrayList", "java.util", TypeKind.CLASS, true, List.of()));
    compiler = new TempSourceCompiler();
    session = new SourceAnalysisSession(compiler);
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  // --- Classification ---

  @Test
  void compile_unresolvedTypeInField_setsTypeRefPayload() {
    final var source =
        """
        package com.example;
        class Test {
          ArrayList list;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.cant.resolve");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.TYPE_REF);
    assertThat(payload.name()).isEqualTo("ArrayList");
  }

  @Test
  void compile_unresolvedIdentifierInInitializer_setsVariableRefPayload() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            int x = unknownVar;
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.cant.resolve");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.VARIABLE_REF);
    assertThat(payload.name()).isEqualTo("unknownVar");
  }

  @Test
  void compile_unreportedException_setsUnreportedExceptionPayload() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.io.IOException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.unreported.exception");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.UNREPORTED_EXCEPTION);
    assertThat(payload.name()).isEqualTo("java.io.IOException");
  }

  // --- Import provider ---

  @Test
  void codeAction_typeRef_returnsImportQuickFix() {
    final var source =
        """
        package com.example;
        class Test {
          ArrayList list;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Import 'java.util.ArrayList'");
    assertThat(action.getKind()).isEqualTo("quickfix");
    assertThat(action.getDiagnostics()).hasSize(1);

    final var edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText()).isEqualTo("import java.util.ArrayList;\n");
    assertThat(edits.getFirst().getRange().getStart().getLine()).isEqualTo(1);
  }

  @Test
  void codeAction_alreadyImportedType_isSkipped() {
    final var sourceWithoutImport =
        """
        package com.example;
        class Test { ArrayList list; }
        """;
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, sourceWithoutImport, 1, CompileMode.OPEN);

    final var sourceWithImport =
        """
        package com.example;
        import java.util.ArrayList;
        class Test { ArrayList list; }
        """;
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            sourceWithImport,
            2,
            rangeAt(0, 0),
            toRequests(diags),
            typeIndex);
    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_noDiagnostics_returnsEmpty() {
    final var actions =
        session.codeAction(TempSourceCompiler.TEST_URI, "", 1, rangeAt(0, 0), List.of(), typeIndex);
    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_inaccessibleType_isSkipped() throws IOException {
    final var classDir = tmp.resolve("classes");
    final var ppFile = tmp.resolve("PackagePrivateClass.java");
    Files.writeString(
        ppFile,
        """
        package com.other;
        class PackagePrivateClass {}
        """);
    final var pubFile = tmp.resolve("PublicClass.java");
    Files.writeString(
        pubFile,
        """
        package com.other;
        public class PublicClass {}
        """);
    TestCompiler.compileToDir(classDir, ppFile, pubFile);
    compiler.fileManager().setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(classDir));

    final WorkspaceTypeIndex customTypeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("custom_index.json"),
            new TypeIndexEntry(
                "PackagePrivateClass",
                "com.other.PackagePrivateClass",
                "com.other",
                TypeKind.CLASS,
                true,
                List.of()),
            new TypeIndexEntry(
                "PublicClass",
                "com.other.PublicClass",
                "com.other",
                TypeKind.CLASS,
                true,
                List.of()));

    final var source =
        """
        package com.example;
        class Test {
          PackagePrivateClass pp;
          PublicClass pub;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI,
            source,
            1,
            rangeAt(0, 0),
            toRequests(diags),
            customTypeIndex);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle())
        .isEqualTo("Import 'com.other.PublicClass'");
  }

  @Test
  void codeAction_typeRef_reactorEntryNotOnClasspath_offersImport() {
    // Reactor type is in the type index but has no class file on the compilation classpath.
    // This happens when a project type is created/renamed but not yet synced via Maven.
    final var reactorEntry =
        new TypeIndexEntry(
            "MyCustomException",
            "com.example.MyCustomException",
            "com.example",
            TypeKind.CLASS,
            true,
            List.of());
    final var reactorIndex = WorkspaceTypeIndex.build(List.of(), List.of(List.of(reactorEntry)));

    final var source =
        """
        package com.other;
        class Test {
          void m() throws MyCustomException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI, source, 1, rangeAt(0, 0), toRequests(diags), reactorIndex);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle())
        .isEqualTo("Import 'com.example.MyCustomException'");
  }

  // --- AddThrows provider ---

  @Test
  void codeAction_unreportedException_addsThrowsAndImport() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.io.IOException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(2);
    final var throwsAction = findThrowsAction(actions);
    assertThat(throwsAction.getTitle()).isEqualTo("Add 'throws IOException' to method");
    assertThat(throwsAction.getKind()).isEqualTo("quickfix");
    assertThat(throwsAction.getDiagnostics()).hasSize(1);

    final List<TextEdit> edits =
        throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(2);

    final var throwsEdit =
        edits.stream().filter(e -> e.getNewText().contains("throws")).findFirst().orElseThrow();
    assertThat(throwsEdit.getNewText()).isEqualTo(" throws IOException");

    final var importEdit =
        edits.stream().filter(e -> e.getNewText().contains("import")).findFirst().orElseThrow();
    assertThat(importEdit.getNewText()).isEqualTo("import java.io.IOException;\n");
  }

  @Test
  void codeAction_unreportedException_appendsToExistingThrows() {
    final var source =
        """
        package com.example;
        import java.io.IOException;
        class Test {
          void method() throws IOException { helper(); }
          void helper() throws java.io.IOException, java.sql.SQLException {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final List<CodeActionRequest> sqlOnly =
        toRequests(diags).stream()
            .filter(r -> r.payload().name().equals("java.sql.SQLException"))
            .toList();
    final var actions =
        session.codeAction(
            TempSourceCompiler.TEST_URI, source, 1, rangeAt(0, 0), sqlOnly, typeIndex);

    assertThat(actions).hasSize(2);
    final var throwsAction = findThrowsAction(actions);
    assertThat(throwsAction.getTitle()).isEqualTo("Add 'throws SQLException' to method");

    final List<TextEdit> edits =
        throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits.getFirst().getNewText()).isEqualTo(", SQLException");
  }

  @Test
  void codeAction_unreportedJavaLangException_addsThrowsWithoutImport() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { helper(); }
          void helper() throws java.lang.Exception {}
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(2);
    final var throwsAction = findThrowsAction(actions);
    final List<TextEdit> edits =
        throwsAction.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText()).isEqualTo(" throws Exception");
  }

  // --- DeclareVariable provider ---

  @Test
  void codeAction_variableRef_intAssignment_declaresLocalVariable() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { count = 0; }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Declare local variable 'count'");
    assertThat(action.getKind()).isEqualTo("quickfix");

    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText()).isEqualTo("int count");
  }

  @Test
  void codeAction_variableRef_stringAssignment_declaresLocalVariableWithoutImport() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { msg = "hello"; }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final List<TextEdit> edits =
        actions.getFirst().getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText()).isEqualTo("String msg");
  }

  @Test
  void codeAction_variableRef_rhsOfLocalDecl_returnsEmpty() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            int x = unknownVar;
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_variableRef_fieldLevel_returnsEmpty() {
    // DeclareVariable is suppressed for field-level: field initializers are VariableTree,
    // not ExpressionStatementTree, so the provider correctly offers nothing.
    final var source =
        """
        package com.example;
        class Test {
          int x = unknownVar;
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).isEmpty();
  }

  // --- Gap regression tests ---

  @Test
  void codeAction_unreportedException_fieldInitializerLambda_offersTryCatch() {
    // Gap 1: UNREPORTED_EXCEPTION inside a field-initializer lambda should offer
    // "Wrap in try/catch" instead of depending on an enclosing MethodTree.
    final var source =
        """
        package com.example;
        class Test {
          Runnable r = () -> { throw new java.io.IOException("x"); };
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Wrap in try/catch");
    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText())
        .contains("try {")
        .contains("throw new java.io.IOException(\"x\");")
        .contains("catch (java.io.IOException e)");
  }

  @Test
  void codeAction_unreportedException_methodBodyLambda_doesNotAddThrowsToOuterMethod() {
    // Gap 1 variant: UNREPORTED_EXCEPTION inside a lambda nested in a method body.
    // AddThrowsProvider must not offer "Add throws" to the outer method, because the exception
    // cannot escape the lambda boundary. Desired action is "Wrap in try/catch".
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = () -> { throw new java.io.IOException("x"); };
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Wrap in try/catch");
    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText()).doesNotContain("throws");
  }

  @Test
  void codeAction_unreportedException_anonymousClassMethod_offersTryCatch() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = new Runnable() {
              @Override
              public void run() {
                throw new java.io.IOException("x");
              }
            };
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Wrap in try/catch");
    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText())
        .contains("try {")
        .contains("throw new java.io.IOException(\"x\");")
        .contains("catch (java.io.IOException e)")
        .doesNotContain("throws");
  }

  @Test
  void compile_doesNotOverrideAbstract_setsMissingMethodImplPayload() {
    final var source =
        """
        package com.example;
        class Test implements Runnable { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.does.not.override.abstract");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.MISSING_METHOD_IMPL);
    assertThat(payload.name()).isEqualTo("Test");
  }

  // --- EG-002 regression ---

  @Test
  void codeAction_unreportedException_methodBody_offersBothWrapAndThrows() {
    final var source =
        """
        package com.example;
        class Test {
          void method() { throw new java.io.IOException("x"); }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(2);
    assertThat(actions.stream().map(a -> a.getRight().getTitle()))
        .contains("Add 'throws IOException' to method", "Wrap in try/catch");
  }

  @Test
  void codeAction_unreportedException_lambdaBody_offersOnlyWrap() {
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = () -> { throw new java.io.IOException("x"); };
          }
        }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle()).isEqualTo("Wrap in try/catch");
  }

  // --- MissingMethodImpl provider ---

  @Test
  void codeAction_missingMethodImpl_singleAbstractMethod_generatesStub() {
    final var source =
        """
        package com.example;
        class Test implements Runnable { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Implement abstract methods");
    assertThat(action.getKind()).isEqualTo("quickfix");

    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText())
        .contains("@Override")
        .contains("public void run()")
        .contains("throw new UnsupportedOperationException()");
  }

  @Test
  void codeAction_missingMethodImpl_multipleAbstractMethods_generatesAllStubs() {
    final var source =
        """
        package com.example;
        import java.util.Iterator;
        class Test implements Iterator<String> { }
        """;

    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var actions = diagnosticActions(source, diags);

    assertThat(actions).hasSize(1);
    final List<TextEdit> edits =
        actions.getFirst().getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    final String stubText = edits.getFirst().getNewText();
    assertThat(stubText).contains("public boolean hasNext()").contains("public String next()");
  }

  // --- Replace-var provider (request-driven, CA-5) ---

  @Test
  void codeAction_varLocal_offersReplaceWithInferredType() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var s = "hello";
          }
        }
        """;
    // cursor on the `var` local declaration; no diagnostic drives this
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'String'");
  }

  @Test
  void codeAction_varGenericType_replacesWithGenericsAndAddsImport() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var list = new java.util.ArrayList<String>();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'ArrayList<String>'");
    final List<TextEdit> edits =
        actions.getFirst().getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).anyMatch(e -> e.getNewText().contains("import java.util.ArrayList"));
  }

  @Test
  void codeAction_varPrimitive_replacesWithPrimitiveType() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var n = 42;
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'int'");
  }

  @Test
  void codeAction_varAnonymousClass_offersNoReplace() {
    // The inferred type is an anonymous class, which cannot be written explicitly, so keep `var`.
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            var r = new Runnable() { public void run() {} };
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Replace 'var'"));
  }

  @Test
  void codeAction_varUpwardProjectedType_replacesWithProjectedType() {
    // `list.getFirst()` on a `? extends Number` list yields a captured type that `var`
    // upward-projects
    // (JLS 14.4.1) to the denotable `Number`, so the refactor is offered with the projected type.
    final var source =
        """
        package com.example;
        class Test {
          void m(java.util.List<? extends Number> list) {
            var x = list.getFirst();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 8);

    assertThat(rightTitles(actions)).contains("Replace 'var' with 'Number'");
  }

  @Test
  void codeAction_explicitTypedLocal_offersNoReplace() {
    // A normal explicitly-typed local is not a `var`, so no refactor is offered.
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            String s = "hello";
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 3, 11);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Replace 'var'"));
  }

  // --- Extract-variable provider (request-driven) ---

  @Test
  void codeAction_subExpression_extractsLocalAndReplacesOccurrence() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            System.out.println(compute(2) + 1);
          }
          int compute(int n) { return n; }
        }
        """;
    // select `compute(2)` inside the println argument
    final var actions = extractActionsSpanning(source, 3, 23, 3, 33);

    assertThat(rightTitles(actions)).contains("Extract variable 'compute'");
    final List<TextEdit> edits = extractEdits(actions);
    assertThat(edits).hasSize(2);
    assertThat(newTextAtLineStart(edits)).isEqualTo("int compute = compute(2);\n    ");
    assertThat(replacementText(edits)).isEqualTo("compute");
  }

  @Test
  void codeAction_genericTypedExpression_addsImport() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            use(make());
          }
          java.util.List<String> make() { return null; }
          void use(java.util.List<String> l) {}
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 8, 3, 14);

    assertThat(rightTitles(actions)).contains("Extract variable 'make'");
    final List<TextEdit> edits = extractEdits(actions);
    assertThat(edits).hasSize(3);
    assertThat(newTextAtLineStart(edits)).isEqualTo("List<String> make = make();\n    ");
    assertThat(edits).anyMatch(e -> e.getNewText().contains("import java.util.List"));
  }

  @Test
  void codeAction_emptyRangeInsideExpression_extractsEnclosingExpression() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            use(compute(2) + 1);
          }
          int compute(int n) { return n; }
          void use(int n) {}
        }
        """;
    // caret sitting inside `compute(2)` with no selection resolves to the enclosing call
    final var actions = replaceVarActionsAt(source, 3, 12);

    assertThat(rightTitles(actions)).contains("Extract variable 'compute'");
  }

  @Test
  void codeAction_extractVariable_derivesNameFromSelectedExpression() {
    final var newClass =
        """
        package com.example;
        class Test {
          void m() {
            use(new StringBuilder());
          }
          void use(StringBuilder b) {}
        }
        """;
    final var getter =
        """
        package com.example;
        class Test {
          void m() {
            use(getName().length());
          }
          String getName() { return ""; }
          void use(int n) {}
        }
        """;
    final var collision =
        """
        package com.example;
        class Test {
          void m() {
            int compute = 0;
            use(compute(2));
          }
          int compute(int n) { return n; }
          void use(int n) {}
        }
        """;
    final var fieldAccess =
        """
        package com.example;
        class Test {
          String label = "x";
          void m() {
            use(this.label);
          }
          void use(String s) {}
        }
        """;
    final List<ExtractCase> cases =
        List.of(
            new ExtractCase(
                "new-class names after its type",
                newClass,
                3,
                8,
                3,
                27,
                "Extract variable 'stringBuilder'"),
            new ExtractCase(
                "getter strips get/is prefix", getter, 3, 8, 3, 17, "Extract variable 'name'"),
            new ExtractCase(
                "collision with a local gets a numeric suffix",
                collision,
                4,
                8,
                4,
                18,
                "Extract variable 'compute1'"),
            new ExtractCase(
                "field read names after the field",
                fieldAccess,
                4,
                8,
                4,
                18,
                "Extract variable 'label'"));

    for (final ExtractCase c : cases) {
      final var actions =
          extractActionsSpanning(
              c.source(), c.startLine(), c.startChar(), c.endLine(), c.endChar());
      assertThat(rightTitles(actions)).as(c.label()).contains(c.expectedTitle());
    }
  }

  @Test
  void codeAction_extractVariable_unsafeSelection_offersNothing() {
    final var voidCall =
        """
        package com.example;
        class Test {
          void m() {
            run();
          }
          void run() {}
        }
        """;
    final var wholeStatement =
        """
        package com.example;
        class Test {
          void m() {
            compute(2);
          }
          int compute(int n) { return n; }
        }
        """;
    final var bracelessIf =
        """
        package com.example;
        class Test {
          void m(boolean b) {
            if (b) use(compute(2));
          }
          int compute(int n) { return n; }
          void use(int n) {}
        }
        """;
    final List<ExtractCase> cases =
        List.of(
            new ExtractCase("void has nothing to declare", voidCall, 3, 4, 3, 9, null),
            new ExtractCase(
                "whole ExpressionStatement expression", wholeStatement, 3, 4, 3, 14, null),
            new ExtractCase("braceless if body is not a block", bracelessIf, 3, 15, 3, 25, null));

    for (final ExtractCase c : cases) {
      final var actions =
          extractActionsSpanning(
              c.source(), c.startLine(), c.startChar(), c.endLine(), c.endChar());
      assertThat(rightTitles(actions))
          .as(c.label())
          .noneMatch(t -> t.startsWith("Extract variable"));
    }
  }

  private record ExtractCase(
      String label,
      String source,
      int startLine,
      int startChar,
      int endLine,
      int endChar,
      String expectedTitle) {}

  @Test
  void codeAction_staticCallReceiver_extractsCallNotTypeReference() {
    // A caret on the type name of a static call must not extract the type reference, which produced
    // uncompilable `Factory factory = Factory;`. It climbs to the enclosing value expression: a
    // whole-statement call offers nothing, a call used as a sub-expression extracts the call
    // itself.
    final var wholeStatement =
        """
        package com.example;
        class Test {
          void m() {
            Factory.create();
          }
          static class Factory { static String create() { return ""; } }
        }
        """;
    // caret on the `F` of `Factory`
    assertThat(rightTitles(replaceVarActionsAt(wholeStatement, 3, 4)))
        .noneMatch(t -> t.startsWith("Extract variable"));

    final var subExpression =
        """
        package com.example;
        class Test {
          void m() {
            use(Factory.create());
          }
          void use(String s) {}
          static class Factory { static String create() { return ""; } }
        }
        """;
    final var actions = replaceVarActionsAt(subExpression, 3, 8);
    assertThat(rightTitles(actions)).contains("Extract variable 'create'");
    assertThat(newTextAtLineStart(extractEdits(actions)))
        .isEqualTo("String create = Factory.create();\n    ");
  }

  // --- Extract-constant provider ---

  @Test
  void codeAction_stringLiteral_extractsConstantNamedFromContent() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            use("hello world");
          }
          void use(String s) {}
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 8, 3, 21);

    assertThat(rightTitles(actions)).contains("Extract constant 'HELLO_WORLD'");
    final List<TextEdit> edits = constantEdits(actions);
    assertThat(newTextAtLineStart(edits))
        .startsWith("private static final String HELLO_WORLD = \"hello world\";");
    assertThat(replacementText(edits)).isEqualTo("HELLO_WORLD");
  }

  @Test
  void codeAction_numericLiteral_extractsConstantWithDefaultName() {
    final var source =
        """
        package com.example;
        class Test {
          int m() {
            return 42;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 13);

    assertThat(rightTitles(actions)).contains("Extract constant 'CONSTANT'");
    assertThat(newTextAtLineStart(constantEdits(actions)))
        .startsWith("private static final int CONSTANT = 42;");
  }

  @Test
  void codeAction_nonConstantExpression_offersNoConstant() {
    // a method call is not a compile-time constant — extract variable is still offered, constant
    // not
    final var source =
        """
        package com.example;
        class Test {
          int m() {
            return compute();
          }
          int compute() { return 1; }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 20);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract variable"));
    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract constant"));
  }

  @Test
  void codeAction_repeatedLiteral_offersReplaceAllConstant() {
    final var source =
        """
        package com.example;
        class Test {
          int a() {
            return 7;
          }
          int b() {
            return 7 + 7;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 12);

    assertThat(rightTitles(actions))
        .contains(
            "Extract constant 'CONSTANT'",
            "Extract constant 'CONSTANT' (replace all 3 occurrences)");
  }

  // --- Extract-field provider ---

  @Test
  void codeAction_expressionReadingOnlyFields_extractsInstanceField() {
    final var source =
        """
        package com.example;
        class Test {
          private final int base = compute();
          int m() {
            return base * 2;
          }
          int compute() { return 5; }
        }
        """;
    final var actions = extractActionsSpanning(source, 4, 11, 4, 19);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract field"));
    final List<TextEdit> edits = fieldEdits(actions);
    final String name = replacementText(edits);
    assertThat(insertedDecl(edits))
        .startsWith("private final int ")
        .contains(name)
        .endsWith("= base * 2;");
    assertThat(insertLine(edits)).isEqualTo(2);
  }

  @Test
  void codeAction_expressionReadingParameter_offersNoField() {
    final var source =
        """
        package com.example;
        class Test {
          int m(int x) {
            return x * 2;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 16);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract variable"));
    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  @Test
  void codeAction_staticContext_offersNoField() {
    final var source =
        """
        package com.example;
        class Test {
          static int m() {
            return compute() + 1;
          }
          static int compute() { return 1; }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 24);

    assertThat(rightTitles(actions)).anyMatch(t -> t.startsWith("Extract variable"));
    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  @Test
  void codeAction_repeatedFieldExpression_offersReplaceAllField() {
    final var source =
        """
        package com.example;
        class Test {
          private final int base = compute();
          int a() {
            return base + 1;
          }
          int b() {
            return base + 1;
          }
          int compute() { return 5; }
        }
        """;
    final var actions = extractActionsSpanning(source, 4, 11, 4, 19);

    assertThat(rightTitles(actions))
        .anyMatch(t -> t.startsWith("Extract field") && t.contains("replace all 2 occurrences"));
  }

  @Test
  void codeAction_recordBody_offersNoField() {
    final var source =
        """
        package com.example;
        record Test(int base) {
          int m() {
            return base() * 2;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 11, 3, 21);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
  }

  // --- Extract-variable: replace all occurrences ---

  @Test
  void codeAction_repeatedExpression_offersReplaceAllOccurrences() {
    final var source =
        """
        package com.example;
        class Test {
          void m(Config config) {
            use(config.name());
            use(config.name());
            use(config.name());
          }
          void use(String s) {}
        }
        class Config { String name() { return ""; } }
        """;
    // select the first `config.name()`
    final var actions = extractActionsSpanning(source, 3, 8, 3, 21);

    assertThat(rightTitles(actions))
        .contains("Extract variable 'name'", "Extract variable 'name' (replace all 3 occurrences)");
    final List<TextEdit> edits = replaceAllEdits(actions);
    assertThat(edits).hasSize(4); // one declaration + three replacements
    assertThat(edits).filteredOn(e -> e.getNewText().equals("name")).hasSize(3);
    assertThat(newTextAtLineStart(edits)).isEqualTo("String name = config.name();\n    ");
  }

  @Test
  void codeAction_differentReceivers_doesNotMergeOccurrences() {
    // a.name() and b.name() spell the same but resolve to different receiver elements, so semantic
    // equality refuses to merge them — only the base single-occurrence action is offered.
    final var source =
        """
        package com.example;
        class Test {
          void m(Config a, Config b) {
            use(a.name());
            use(b.name());
          }
          void use(String s) {}
        }
        class Config { String name() { return ""; } }
        """;
    final var actions = extractActionsSpanning(source, 3, 8, 3, 16);

    assertThat(rightTitles(actions)).contains("Extract variable 'name'");
    assertThat(rightTitles(actions)).noneMatch(t -> t.contains("replace all"));
  }

  @Test
  void codeAction_readReassignedBetweenOccurrences_refusesReplaceAll() {
    // `x + 1` occurs twice, but `x` is reassigned between them, so collapsing would capture a stale
    // value — replace-all is refused; the base extraction of the first occurrence still stands.
    final var source =
        """
        package com.example;
        class Test {
          int m(int x) {
            int a = x + 1;
            x = 5;
            int b = x + 1;
            return a + b;
          }
        }
        """;
    final var actions = extractActionsSpanning(source, 3, 12, 3, 17);

    assertThat(rightTitles(actions)).contains("Extract variable 'value'");
    assertThat(rightTitles(actions)).noneMatch(t -> t.contains("replace all"));
  }

  @Test
  void codeAction_replaceAll_insertsBeforeEarliestOccurrenceStatement() {
    // The anchor is the earliest statement that contains an occurrence, not the first statement of
    // the method — the unrelated leading statement must not move the declaration up.
    final var source =
        """
        package com.example;
        class Test {
          void m(Config config) {
            int unrelated = 0;
            use(config.name());
            use(config.name());
          }
          void use(String s) {}
        }
        class Config { String name() { return ""; } }
        """;
    final var actions = extractActionsSpanning(source, 4, 8, 4, 21);

    final List<TextEdit> edits = replaceAllEdits(actions);
    final TextEdit insert =
        edits.stream()
            .filter(e -> e.getNewText().startsWith("String name"))
            .findFirst()
            .orElseThrow();
    assertThat(insert.getRange().getStart().getLine()).isEqualTo(4);
  }

  // --- Try-with-resources provider (request-driven) ---

  @Test
  void codeAction_autoCloseableDeclaration_wrapsFollowingStatements() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r.read();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(rightTitles(actions)).contains("Surround with try-with-resources");
    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n      r.read();\n    }");
  }

  @Test
  void codeAction_declarationAsLastStatement_wrapsWithEmptyBody() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n    }");
  }

  @Test
  void codeAction_fourSpaceIndent_bodyUsesInferredIndentStep() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
            void m() throws Exception {
                FileReader r = new FileReader("x");
                r.read();
            }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 8);

    // step inferred as 4 spaces, so the body lands at 12 columns.
    assertThat(tryWithResourcesEdit(actions)).contains("\n            r.read();\n");
  }

  @Test
  void codeAction_nonAutoCloseableDeclaration_notOffered() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            String s = "x";
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 3, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_declarationWithoutInitializer_notOffered() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() {
            FileReader r;
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 4, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_reassignedResource_notOffered() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r = new FileReader("y");
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 4, 4)))
        .doesNotContain("Surround with try-with-resources");
  }

  @Test
  void codeAction_trailingCloseCall_isDroppedFromBody() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r.read();
            r.close();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n      r.read();\n    }");
  }

  @Test
  void codeAction_closeIsOnlyStatement_wrapsWithEmptyBody() {
    final var source =
        """
        package com.example;
        import java.io.FileReader;
        class Test {
          void m() throws Exception {
            FileReader r = new FileReader("x");
            r.close();
          }
        }
        """;
    final var actions = replaceVarActionsAt(source, 4, 4);

    assertThat(tryWithResourcesEdit(actions))
        .isEqualTo("try (FileReader r = new FileReader(\"x\")) {\n    }");
  }

  // --- Helpers ---

  private static String tryWithResourcesEdit(final List<Either<Command, CodeAction>> actions) {
    final CodeAction action =
        actions.stream()
            .map(Either::getRight)
            .filter(a -> "Surround with try-with-resources".equals(a.getTitle()))
            .findFirst()
            .orElseThrow();
    return action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI).getFirst().getNewText();
  }

  private List<Either<Command, CodeAction>> replaceVarActionsAt(
      final String source, final int line, final int character) {
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    return session.codeAction(
        TempSourceCompiler.TEST_URI, source, 1, rangeAt(line, character), List.of(), typeIndex);
  }

  private List<Either<Command, CodeAction>> extractActionsSpanning(
      final String source,
      final int startLine,
      final int startChar,
      final int endLine,
      final int endChar) {
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var range = new Range(new Position(startLine, startChar), new Position(endLine, endChar));
    return session.codeAction(TempSourceCompiler.TEST_URI, source, 1, range, List.of(), typeIndex);
  }

  private static List<TextEdit> extractEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Extract variable");
  }

  private static List<TextEdit> constantEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Extract constant");
  }

  private static List<TextEdit> fieldEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Extract field");
  }

  // The inserted declaration text, stripped of the leading/trailing whitespace that positions it on
  // its own line, so a placement-agnostic assertion can match the declaration itself.
  private static String insertedDecl(final List<TextEdit> edits) {
    return insertEdit(edits).getNewText().strip();
  }

  private static int insertLine(final List<TextEdit> edits) {
    return insertEdit(edits).getRange().getStart().getLine();
  }

  private static TextEdit insertEdit(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> e.getRange().getStart().equals(e.getRange().getEnd()))
        .filter(e -> !e.getNewText().startsWith("import "))
        .findFirst()
        .orElseThrow();
  }

  private static List<TextEdit> editsOfActionStartingWith(
      final List<Either<Command, CodeAction>> actions, final String titlePrefix) {
    return actions.stream()
        .filter(Either::isRight)
        .map(Either::getRight)
        .filter(a -> a.getTitle().startsWith(titlePrefix))
        .findFirst()
        .orElseThrow()
        .getEdit()
        .getChanges()
        .get(TempSourceCompiler.TEST_URI);
  }

  private static List<TextEdit> replaceAllEdits(final List<Either<Command, CodeAction>> actions) {
    return actions.stream()
        .filter(Either::isRight)
        .map(Either::getRight)
        .filter(a -> a.getTitle().contains("replace all"))
        .findFirst()
        .orElseThrow()
        .getEdit()
        .getChanges()
        .get(TempSourceCompiler.TEST_URI);
  }

  private static String newTextAtLineStart(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> e.getRange().getStart().equals(e.getRange().getEnd()))
        .filter(e -> !e.getNewText().startsWith("import "))
        .map(TextEdit::getNewText)
        .findFirst()
        .orElseThrow();
  }

  private static String replacementText(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> !e.getRange().getStart().equals(e.getRange().getEnd()))
        .map(TextEdit::getNewText)
        .findFirst()
        .orElseThrow();
  }

  private List<Either<Command, CodeAction>> diagnosticActions(
      final String source, final List<Diagnostic> diags) {
    return session.codeAction(
        TempSourceCompiler.TEST_URI, source, 1, rangeAt(0, 0), toRequests(diags), typeIndex);
  }

  private static Range rangeAt(final int line, final int character) {
    final var pos = new Position(line, character);
    return new Range(pos, pos);
  }

  private static List<String> rightTitles(final List<Either<Command, CodeAction>> actions) {
    return actions.stream().filter(Either::isRight).map(a -> a.getRight().getTitle()).toList();
  }

  private static CodeAction findThrowsAction(final List<Either<Command, CodeAction>> actions) {
    return actions.stream()
        .map(a -> a.getRight())
        .filter(a -> a.getTitle().startsWith("Add 'throws"))
        .findFirst()
        .orElseThrow();
  }

  private static List<CodeActionRequest> toRequests(final List<Diagnostic> diags) {
    return diags.stream()
        .filter(d -> d.getData() instanceof DiagnosticPayload)
        .map(
            d ->
                new CodeActionRequest(
                    TempSourceCompiler.TEST_URI, d, (DiagnosticPayload) d.getData()))
        .toList();
  }

  private static Diagnostic diagWithCode(final List<Diagnostic> diags, final String codePrefix) {
    return diags.stream()
        .filter(
            d ->
                d.getCode() != null
                    && d.getCode().isLeft()
                    && d.getCode().getLeft().startsWith(codePrefix))
        .findFirst()
        .orElseThrow();
  }
}
