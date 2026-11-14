package io.github.aglibs.lathe.analysis;

import static io.github.aglibs.lathe.analysis.Positions.applyEdits;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.util.List;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

// The rewrites of a declaration: to and from `var`, and into try-with-resources.
class ConvertCodeActionTest extends CodeActionTestBase {
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
    final var action = actions.get(0).getRight();
    assertThat(action.getTitle()).isEqualTo("Declare local variable 'count'");
    assertThat(action.getKind()).isEqualTo("quickfix");

    final List<TextEdit> edits = action.getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).isEqualTo("int count");
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
        actions.get(0).getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
    assertThat(edits).hasSize(1);
    assertThat(edits.get(0).getNewText()).isEqualTo("String msg");
  }

  @Test
  void codeAction_variableRef_valueVarCannotTake_offersNoDeclaration() {
    for (final String value : List.of("null", "() -> {}", "String::length")) {
      final var source =
          """
          package com.example;
          class Test {
            void method() { x = %s; }
          }
          """
              .formatted(value);

      final List<Diagnostic> diags =
          session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);

      assertThat(rightTitles(diagnosticActions(source, diags)))
          .as(value)
          .noneMatch(t -> t.startsWith("Declare local variable"));
    }
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
        actions.get(0).getRight().getEdit().getChanges().get(TempSourceCompiler.TEST_URI);
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
    // `list.get(0)` on a `? extends Number` list yields a captured type that `var`
    // upward-projects
    // (JLS 14.4.1) to the denotable `Number`, so the refactor is offered with the projected type.
    final var source =
        """
        package com.example;
        class Test {
          void m(java.util.List<? extends Number> list) {
            var x = list.get(0);
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

  @Test
  void codeAction_diamondInitializer_convertsToVarAndFillsTypeArguments() {
    // The declared type is dropped, so the diamond must be filled from the inferred arguments —
    // otherwise `var` would infer `Object`. Covers one and two type arguments.
    final var source =
        """
        package com.example;
        import java.util.ArrayList;
        import java.util.HashMap;
        import java.util.List;
        import java.util.Map;
        class Test {
          void m() {
            List<String> ls = new ArrayList<>();
            Map<String, Integer> mm = new HashMap<>();
          }
        }
        """;

    assertThat(convertVarEdits(replaceVarActionsAt(source, 7, 4)))
        .extracting(TextEdit::getNewText)
        .containsExactlyInAnyOrder("var", "<String>");
    assertThat(convertVarEdits(replaceVarActionsAt(source, 8, 4)))
        .extracting(TextEdit::getNewText)
        .containsExactlyInAnyOrder("var", "<String, Integer>");
  }

  // A diamond's type arguments are written as the file names them, imported when it does not.
  @Test
  void codeAction_diamondOfAnUnimportedType_fillsAndImportsIt() {
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            java.util.List<java.time.Instant> xs = new java.util.ArrayList<>();
          }
        }
        """;

    final List<TextEdit> edits = convertVarEdits(replaceVarActionsAt(source, 3, 38));
    final String rewritten = applyEdits(source, edits);

    assertThat(rewritten)
        .contains("import java.time.Instant;")
        .contains("var xs = new java.util.ArrayList<Instant>();");
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_losslessAndWideningLocals_convertToVar() {
    // A same-type primitive local and a reference-widening local both convert to a single `var`
    // edit; neither has a diamond to fill.
    final var source =
        """
        package com.example;
        class Test {
          void m() {
            int a = 1;
            Object o = "x";
          }
        }
        """;

    assertThat(convertVarEdits(replaceVarActionsAt(source, 3, 8)))
        .extracting(TextEdit::getNewText)
        .containsExactly("var");
    assertThat(convertVarEdits(replaceVarActionsAt(source, 4, 11)))
        .extracting(TextEdit::getNewText)
        .containsExactly("var");
  }

  @Test
  void codeAction_ineligibleDeclarations_offerNoConvertToVar() {
    // Field (`var` illegal), primitive mismatch (`long` <- `int`), multi-declarator, lambda (no
    // standalone type), an already-`var` local, and a generic call typed by its target (`var`
    // would infer `List<Object>`) must all decline the conversion.
    final var source =
        """
        package com.example;
        class Test {
          String f = "x";
          void m() {
            long big = 1;
            int a = 1, b = 2;
            Runnable r = () -> {};
            var already = 3;
            java.util.List<String> empty = java.util.Collections.emptyList();
          }
        }
        """;

    for (final int[] at : new int[][] {{2, 9}, {4, 9}, {5, 8}, {6, 13}, {7, 8}, {8, 28}}) {
      assertThat(rightTitles(replaceVarActionsAt(source, at[0], at[1])))
          .as("line %d col %d", at[0], at[1])
          .noneMatch(title -> title.equals("Convert to 'var'"));
    }
  }

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
  void codeAction_methodReferenceAssignedToVar_notOffered() {
    // A method reference cannot target `var`, so `x` has an error type; javac treats error types as
    // assignable to everything, which must not surface try-with-resources on non-compiling code.
    final var source =
        """
        package com.example;
        import java.time.Clock;
        class Test {
          void m() {
            final var x = Clock::systemUTC;
          }
        }
        """;
    assertThat(rightTitles(replaceVarActionsAt(source, 4, 4)))
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
  void codeAction_resourceReassignedOrOutlivingTheBlock_notOffered() {
    final List<String> statements =
        List.of(
            "r = new FileReader(\"y\");", "return r;", "kept = r;", "final FileReader alias = r;");
    for (final String statement : statements) {
      final var source =
          """
          package com.example;
          import java.io.FileReader;
          class Test {
            FileReader kept;
            Object m() throws Exception {
              FileReader r = new FileReader("x");
              if (kept == null) {
                %s
              }
              return null;
            }
          }
          """
              .formatted(statement);

      assertThat(rightTitles(replaceVarActionsAt(source, 5, 4)))
          .as(statement)
          .doesNotContain("Surround with try-with-resources");
    }
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

  private static String tryWithResourcesEdit(final List<Either<Command, CodeAction>> actions) {
    return editsOf(actionTitled(actions, "Surround with try-with-resources"::equals))
        .get(0)
        .getNewText();
  }

  private static List<TextEdit> convertVarEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOfActionStartingWith(actions, "Convert to 'var'");
  }
}
