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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeActionTest {
  private static final String uri = "file:///Test.java";

  @TempDir private Path tmp;

  private WorkspaceTypeIndex typeIndex;
  private TempSourceCompiler compiler;
  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() throws IOException {
    typeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("index.json"),
            new TypeIndexEntry("ArrayList", "java.util.ArrayList", "java.util", TypeKind.CLASS));
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.cant.resolve");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    final DiagnosticPayload payload = (DiagnosticPayload) diag.getData();
    assertThat(payload.kind()).isEqualTo(DiagnosticPayload.Kind.TYPE_REF);
    assertThat(payload.name()).isEqualTo("ArrayList");
  }

  @Test
  void compile_unresolvedIdentifierInInitializer_setsVariableRefPayload() {
    final var uri = "file:///Test.java";
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            int x = unknownVar;
          }
        }
        """;

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);

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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);

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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Import 'java.util.ArrayList'");
    assertThat(action.getKind()).isEqualTo("quickfix");
    assertThat(action.getDiagnostics()).hasSize(1);

    final var edits = action.getEdit().getChanges().get(uri);
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
    final List<Diagnostic> diags = session.compile(uri, sourceWithoutImport, 1, CompileMode.OPEN);

    final var sourceWithImport =
        """
        package com.example;
        import java.util.ArrayList;
        class Test { ArrayList list; }
        """;
    final var actions = session.codeAction(uri, sourceWithImport, 2, toRequests(diags), typeIndex);
    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_noDiagnostics_returnsEmpty() {
    final var actions = session.codeAction("file:///Test.java", "", 1, List.of(), typeIndex);
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
                TypeKind.CLASS),
            new TypeIndexEntry(
                "PublicClass", "com.other.PublicClass", "com.other", TypeKind.CLASS));

    final var source =
        """
        package com.example;
        class Test {
          PackagePrivateClass pp;
          PublicClass pub;
        }
        """;

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), customTypeIndex);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle())
        .isEqualTo("Import 'com.other.PublicClass'");
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Add 'throws IOException' to method");
    assertThat(action.getKind()).isEqualTo("quickfix");
    assertThat(action.getDiagnostics()).hasSize(1);

    final List<TextEdit> edits = action.getEdit().getChanges().get(uri);
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final List<CodeActionRequest> sqlOnly =
        toRequests(diags).stream()
            .filter(r -> r.payload().name().equals("java.sql.SQLException"))
            .toList();
    final var actions = session.codeAction(uri, source, 1, sqlOnly, typeIndex);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Add 'throws SQLException' to method");

    final List<TextEdit> edits = action.getEdit().getChanges().get(uri);
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    final List<TextEdit> edits = actions.getFirst().getRight().getEdit().getChanges().get(uri);
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Declare local variable 'count'");
    assertThat(action.getKind()).isEqualTo("quickfix");

    final List<TextEdit> edits = action.getEdit().getChanges().get(uri);
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    final List<TextEdit> edits = actions.getFirst().getRight().getEdit().getChanges().get(uri);
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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

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

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).isEmpty();
  }

  // --- Gap regression tests (fail now, pass when each gap is fixed) ---

  @Disabled("Gap 1: TryCatchWrapProvider not yet implemented")
  @Test
  void codeAction_unreportedException_fieldInitializerLambda_offersTryCatch() {
    // Gap 1: UNREPORTED_EXCEPTION inside a field-initializer lambda should offer
    // "Wrap in try/catch". Currently returns empty — no enclosing MethodTree.
    // Will pass when TryCatchWrapProvider is implemented.
    final var source =
        """
        package com.example;
        class Test {
          Runnable r = () -> { throw new java.io.IOException("x"); };
        }
        """;

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle()).contains("try");
  }

  @Disabled(
      "Gap 1: TryCatchWrapProvider not yet implemented; AddThrowsProvider not yet suppressed in lambda context")
  @Test
  void codeAction_unreportedException_methodBodyLambda_doesNotAddThrowsToOuterMethod() {
    // Gap 1 variant: UNREPORTED_EXCEPTION inside a lambda nested in a method body.
    // AddThrowsProvider currently offers "Add throws" to the outer method, which is wrong
    // (the exception cannot escape the lambda boundary). Desired action is "Wrap in try/catch".
    // Will pass when AddThrowsProvider is suppressed in lambda context and TryCatchWrapProvider
    // is implemented.
    final var source =
        """
        package com.example;
        class Test {
          void method() {
            Runnable r = () -> { throw new java.io.IOException("x"); };
          }
        }
        """;

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, toRequests(diags), typeIndex);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle()).contains("try");
  }

  @Disabled("Gap 3: MISSING_METHOD_IMPL classification not yet added to enrichWithContext")
  @Test
  void compile_doesNotOverrideAbstract_setsMissingMethodImplPayload() {
    // Gap 3: compiler.err.does.not.override.abstract is not yet handled in enrichWithContext.
    // Will pass when MISSING_METHOD_IMPL classification is added.
    final var source =
        """
        package com.example;
        class Test implements Runnable { }
        """;

    final List<Diagnostic> diags = session.compile(uri, source, 1, CompileMode.OPEN);

    final Diagnostic diag = diagWithCode(diags, "compiler.err.does.not.override.abstract");
    assertThat(diag.getData()).isInstanceOf(DiagnosticPayload.class);
    assertThat(((DiagnosticPayload) diag.getData()).kind())
        .isEqualTo(DiagnosticPayload.Kind.MISSING_METHOD_IMPL);
  }

  // --- Helpers ---

  private static List<CodeActionRequest> toRequests(final List<Diagnostic> diags) {
    return diags.stream()
        .filter(d -> d.getData() instanceof DiagnosticPayload)
        .map(d -> new CodeActionRequest(uri, d, (DiagnosticPayload) d.getData()))
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
