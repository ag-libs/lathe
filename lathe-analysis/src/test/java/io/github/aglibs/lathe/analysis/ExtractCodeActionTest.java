package io.github.aglibs.lathe.analysis;

import static io.github.aglibs.lathe.analysis.Positions.applyEdits;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

// Extract variable, constant and field, and replacing every occurrence of the expression.
class ExtractCodeActionTest extends CodeActionTestBase {
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
    final List<ExtractCase> cases =
        List.of(new ExtractCase("void has nothing to declare", voidCall, 3, 4, 3, 9, null));

    for (final ExtractCase c : cases) {
      final var actions =
          extractActionsSpanning(
              c.source(), c.startLine(), c.startChar(), c.endLine(), c.endChar());
      assertThat(rightTitles(actions))
          .as(c.label())
          .noneMatch(t -> t.startsWith("Extract variable"));
    }
  }

  @Test
  void codeAction_extractVariable_atStatementStart_rewritesToCompilingCode() {
    // The declaration is inserted where the extracted expression begins, so the insert and the
    // replacement share an offset; they must apply in order.
    final var source =
        """
        package com.example;
        class Test {
          void m(StringBuilder sb) {
            sb.append("x").append("y");
          }
        }
        """;
    final String rewritten =
        applyEdits(source, extractEdits(extractActionsSpanning(source, 3, 4, 3, 18)));

    assertThat(rewritten)
        .contains("StringBuilder append = sb.append(\"x\");\n    append.append(\"y\");");
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extraction_lineWiseSelection_findsTheExpression() {
    // A line-wise selection (Neovim's `V`) takes in the indentation and the `;` around the code.
    final String max = "Extract variable 'max'";
    assertExtracts(
        "void m(String a, String b) {\n    int n =\n"
            + "«        Math.max(\n            a.length(),\n            b.length());»\n  }",
        max);
    assertExtracts(
        "void m(String a, String b) {\n    use(\n"
            + "        «Math.max(\n            a.length(),\n            b.length())»);\n  }",
        max);
    assertExtracts(
        "void m(String a, String b) {\n«    int n = Math.max(a.length(), b.length());»\n  }", max);
    assertExtracts(
        "int m(String a) {\n«    return a.trim().length();»\n  }", "Extract variable 'length'");
    assertExtracts(
        "void m() {\n    use(\n«        \"hello world\");»\n  }", "Extract constant 'HELLO_WORLD'");
    assertThat(
            rightTitles(markedActions(inTestClass("void m() {\n«    use(1);\n    use(2);»\n  }"))))
        .as("several statements")
        .noneMatch(t -> t.startsWith("Extract variable"));
  }

  @Test
  void codeAction_extractVariable_evaluatedOnlyInPlace_offersNothing() {
    final Map<String, String> cases =
        Map.of(
            "right of &&", "void m(String a) { if (a != null && «a.isEmpty()») { use(a); } }",
            "?: branch", "void m(String a) { use(ok(a) ? «a.trim()» : \"\"); }",
            "while condition", "void m(List<String> l) { while («l.isEmpty()») { l.add(\"x\"); } }",
            "for condition", "void m(List<String> l) { for (int i = 0; i < «l.size()»; i++) { } }",
            "assert", "void m(String a) { assert «a.isEmpty()»; }",
            "super argument",
                "static class B { B(int x) {} }\n  static class C extends B { C(String a) { super(«a.length()»); } }",
            "intersection type", "void m() { use(«List.of(1, \"a\")»); }",
            "captured wildcard", "void m(Object o) { use(«o.getClass()»); }",
            "earlier resource",
                "void m() throws java.io.IOException {\n    try (var in = new java.io.StringReader(\"x\");"
                    + " var r = new java.io.BufferedReader(«in»)) { }\n  }",
            "earlier loop variable", "void m() { for (int i = 0, j = «i + 1»; i < j; i++) { } }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .noneMatch(t -> t.startsWith("Extract variable")));
  }

  @Test
  void codeAction_extractVariable_evaluatedOnceBeforeStatement_rewritesToCompilingCode() {
    final String variable = "Extract variable";
    assertExtracts("void m(String a) { if («a.isEmpty()») { use(a); } }", variable);
    assertExtracts(
        "void m(Map<String, Integer> m) { for (String k : «m.keySet()») { use(k); } }", variable);
    assertExtracts("void m(String a) { switch («a.trim()») { default -> use(a); } }", variable);
    assertExtracts("void m() { use(«List.of(\"a\", \"b\")»); }", variable);
    assertExtracts("void m(List<List<? extends Number>> l) { use(«l»); }", variable);

    // replace-all: the occurrence the anchor statement always evaluates covers the one in a branch
    final var branch =
        inTestClass("void m(String a, boolean b) { use(«a.trim()»); if (b) { use(a.trim()); } }");
    assertThat(rightTitles(markedActions(branch))).anyMatch(t -> t.contains("replace all 2"));
    final var onlyInBranches =
        inTestClass(
            "void m(String a, boolean b) {\n    if (b) { use(«a.trim()»); } else { use(a.trim()); }\n  }");
    assertThat(rightTitles(markedActions(onlyInBranches)))
        .noneMatch(t -> t.startsWith("Extract variable") && t.contains("replace all"));
  }

  @Test
  void codeAction_extractVariable_outsideABlock_declaresWhereItFits() {
    final String variable = "Extract variable";
    assertExtracts(
        "void m(String a) { switch (a) { case \"x\": use(«a.trim()»); break; default: break; } }",
        variable);
    assertExtracts(
        "void m(java.io.File f) throws java.io.IOException {\n"
            + "    try (var in = new java.io.FileInputStream(«f.getPath()»)) { use(in); }\n  }",
        variable);
    assertExtracts(
        "void m() { for (int i = «Integer.parseInt(\"1\")»; i < 3; i++) { use(i); } }", variable);
    assertExtracts("void m(String a) { «a.trim()»; }", "Extract variable 'trim'");
  }

  @Test
  void codeAction_extractVariable_bodyWithoutRoom_becomesABlock() {
    // A lambda returns the value only when its interface method does, a switch expression's rule
    // yields it: either mistake would not compile.
    final String variable = "Extract variable";
    assertExtracts("void m(List<String> l) { l.forEach(x -> use(«x.trim()»)); }", variable);
    assertExtracts(
        "List<Integer> m(List<String> l) {\n    return l.stream().map(x -> «x.length()» * 2)"
            + ".collect(java.util.stream.Collectors.toList());\n  }",
        variable);
    assertExtracts(
        "int m(String a) { return switch (a) { case \"x\" -> «a.length()» + 1; default -> 0; }; }",
        variable);
    assertExtracts(
        "void m(String a) { switch (a) { case \"x\" -> use(«a.trim()»); default -> use(a); } }",
        variable);
    assertExtracts("void m(String a, boolean b) { if (b) use(«a.trim()»); }", variable);
    assertExtracts("void m(List<String> l) { for (String x : l) use(«x.trim()»); }", variable);

    final String lambda =
        inTestClass("void m(List<String> l) { l.forEach(x -> use(«x.trim()»)); }");
    assertThat(applyEdits(unmarked(lambda), extractEdits(markedActions(lambda))))
        .contains("x -> {\n    String trim = x.trim();\n    use(trim);\n  });");

    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "boolean m(String a) { return java.util.Optional.of(a).filter(x -> x != null && «x.isEmpty()»).isPresent(); }"))))
        .as("pinned inside the body")
        .noneMatch(t -> t.startsWith(variable));
    assertThat(
            rightTitles(
                markedActions(inTestClass("void m(String a, boolean b) { if (b) «a.trim()»; }"))))
        .as("a whole statement alone in a body")
        .noneMatch(t -> t.startsWith(variable));
  }

  @Test
  void codeAction_annotatedDeclarations_insertInsideTheBody() {
    // A `{` in an annotation argument is not the body's: the insert lands inside the body.
    final var annotatedClass =
        """
        package com.example;
        @SuppressWarnings({"unused"})
        class Test {
          void m() { use(«"hello world"»); }
          void use(Object x) {}
        }
        """;
    final var annotatedComponent =
        """
        package com.example;
        record Test(@SuppressWarnings({"unused"}) int a) {
          void m() { use(«"hello world"»); }
          void use(Object x) {}
        }
        """;
    for (final String source : List.of(annotatedClass, annotatedComponent)) {
      final String rewritten = applyEdits(unmarked(source), constantEdits(markedActions(source)));
      assertThat(rewritten)
          .contains("{\n  private static final String HELLO_WORLD = \"hello world\";\n  void m()");
      assertCompilesClean(rewritten);
    }

    final var annotatedConstructor =
        """
        package com.example;
        @SuppressWarnings({"unused"})
        class Widget {
          private final String id;
          @SuppressWarnings({"unused"})
          Widget() {}
        }
        """;
    final String rewritten =
        applyEdits(
            annotatedConstructor, addCtorEdits(replaceVarActionsAt(annotatedConstructor, 3, 23)));
    assertThat(rewritten).contains("Widget(final String id) {\n    this.id = id;\n  }");
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_typeWritingRefactors_typeNamedLikeAnotherInTheFile_writtenQualified() {
    final String header =
        "package com.example;\nimport java.sql.Date;\nclass Test {\n  Date sql;\n";
    final Map<String, String> sources =
        Map.of(
            "Replace 'var'",
            "  long m() { «var» d = new java.util.Date(); return d.getTime(); }\n}\n",
            "Extract variable",
            "  long m() { return «new java.util.Date()».getTime(); }\n}\n",
            "Extract field",
            "  long m() { return «new java.util.Date()».getTime(); }\n}\n");
    sources.forEach(
        (action, body) -> {
          final String source = header + body;
          final List<Either<Command, CodeAction>> actions = markedActions(source);
          assertThat(rightTitles(actions)).as(action).anyMatch(t -> t.startsWith(action));
          final String rewritten =
              applyEdits(unmarked(source), editsOfActionStartingWith(actions, action));
          assertThat(rewritten)
              .as(action)
              .contains("java.util.Date ")
              .doesNotContain("import java.util.Date;");
          assertCompilesClean(rewritten);
        });

    final String declare = header + "  void m() { d = new java.util.Date(); }\n}\n";
    final List<Diagnostic> diags =
        session.compile(TempSourceCompiler.TEST_URI, declare, 1, CompileMode.OPEN);
    final String declared =
        applyEdits(
            declare,
            editsOfActionStartingWith(diagnosticActions(declare, diags), "Declare local variable"));
    assertThat(declared).contains("java.util.Date d = ").doesNotContain("import java.util.Date;");
  }

  @Test
  void codeAction_extractVariable_typeOnlyNamedQualified_importsItAndItsArguments() {
    final String source =
        """
        package com.example;
        class Test {
          int m() { return «java.util.List.of(new java.util.concurrent.atomic.AtomicLong())».size(); }
        }
        """;
    final String rewritten =
        applyEdits(
            unmarked(source), editsOfActionStartingWith(markedActions(source), "Extract variable"));

    assertThat(rewritten)
        .contains(
            "List<AtomicLong> ",
            "import java.util.List;",
            "import java.util.concurrent.atomic.AtomicLong;");
    assertCompilesClean(rewritten);
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
    // uncompilable `Factory factory = Factory;`. It climbs to the enclosing value expression, the
    // call: a whole-statement call becomes its declaration, a sub-expression call is extracted.
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
    // caret on the `F` of `Factory`: the whole statement becomes the declaration
    assertThat(applyEdits(wholeStatement, extractEdits(replaceVarActionsAt(wholeStatement, 3, 4))))
        .contains("String create = Factory.create();");

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

  // A field initializer runs before the constructor body: a final field the constructor assigns is
  // unset then (the field would not compile), and a mutable one would be read once, not on each
  // call.
  @Test
  void codeAction_expressionReadingAFieldTheConstructorSetsOrAMutableOne_offersNoField() {
    final var source =
        """
        package com.example;
        class Test {
          private final String name;
          private int count;
          Test(String name) { this.name = name; }
          int m() {
            return name.length();
          }
          int n() {
            return count * 2;
          }
        }
        """;

    assertThat(rightTitles(extractActionsSpanning(source, 6, 11, 6, 24)))
        .anyMatch(t -> t.startsWith("Extract variable"))
        .noneMatch(t -> t.startsWith("Extract field"));
    assertThat(rightTitles(extractActionsSpanning(source, 9, 11, 9, 20)))
        .noneMatch(t -> t.startsWith("Extract field"));
  }

  @Test
  void codeAction_wholeStatementExpression_offersNoField() {
    // Extracting the whole expression of an expression-statement into a field would replace the
    // statement's only expression with a bare reference, leaving the illegal statement `name;`.
    // Mirror the extract-variable guard and decline. Regression: it emitted the orphaned `name;`.
    final var source =
        """
        package com.example;
        class Test {
          private final String existing = "";
          void m() {
            Factory.create();
          }
          static class Factory { static String create() { return ""; } }
        }
        """;
    final var actions = extractActionsSpanning(source, 4, 4, 4, 20);

    assertThat(rightTitles(actions)).noneMatch(t -> t.startsWith("Extract field"));
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
    assertThat(rightTitles(actions))
        .noneMatch(t -> t.startsWith("Extract variable") && t.contains("replace all"));
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
    assertThat(rightTitles(actions))
        .noneMatch(t -> t.startsWith("Extract variable") && t.contains("replace all"));
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

  private static List<TextEdit> replaceAllEdits(final List<Either<Command, CodeAction>> actions) {
    return editsOf(actionTitled(actions, title -> title.contains("replace all")));
  }

  private static String newTextAtLineStart(final List<TextEdit> edits) {
    return insertEdit(edits).getNewText();
  }

  private static String replacementText(final List<TextEdit> edits) {
    return edits.stream()
        .filter(e -> !e.getRange().getStart().equals(e.getRange().getEnd()))
        .map(TextEdit::getNewText)
        .findFirst()
        .orElseThrow();
  }
}
