package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Extract method: what it extracts, how the call replaces it, and when it offers nothing.
class ExtractMethodCodeActionTest extends CodeActionTestBase {
  @Test
  void codeAction_extractMethod_expression_becomesPrivateMethodAfterItsMember() {
    final var source =
        inTestClass("int m(final String a) {\n    return «a.trim().length() * 2»;\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              int m(final String a) {
                return extracted(a);
              }

              private int extracted(final String a) {
                return a.trim().length() * 2;
              }
            """);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extractMethod_contexts_rewriteToCompilingCode() {
    final String method = "Extract method";
    // static contexts: a static method, a static field, a this(...) argument
    assertExtracts("static int s(String a) { return «a.length() + 1»; }", method);
    assertExtracts("static int g = «Integer.parseInt(\"4\") + 1»;", method);
    assertExtracts("Test(int x) {}\n  Test(String s) { this(«s.length() + 1»); }", method);
    // a lambda's parameter and a captured local become parameters; a field initializer works too
    assertExtracts("void m(List<String> l, String p) { l.forEach(x -> use(«x + p»)); }", method);
    assertExtracts("int base = 2;\n  int f = «base * 3»;", method);
    // inside an anonymous class the method joins that class, where its field is visible
    assertExtracts(
        "Runnable m() { return new Runnable() { int n = 1; public void run() { use(«n + 1»); } }; }",
        method);
    // checked exceptions as instantiated at the call, and every signature type imported
    assertExtracts(
        "String m(Optional<String> o) throws java.io.IOException {\n"
            + "    return «o.orElseThrow(java.io.IOException::new)»;\n  }",
        method);
    assertExtracts(
        "int m(Map<String, java.util.concurrent.atomic.AtomicInteger> c) { return «c.size() + 1»; }",
        method);
    // a nested type the new method's class cannot see by its simple name is written from its
    // top-level type (bare `Module` would be java.lang.Module)
    assertThat(
            assertExtracts(
                "static class Route { static class Module {} }\n"
                    + "  int m(Route.Module x) { return «x.hashCode() + 1»; }",
                method))
        .contains("private int extracted(Route.Module x)");
    // two nested types of one simple name stay apart, each from its enclosing type
    assertThat(
            assertExtracts(
                "static class A { static class Builder {} }\n  static class B { static class Builder {} }\n"
                    + "  int m(A.Builder a, B.Builder b) { return «a.hashCode() + b.hashCode()»; }",
                method))
        .contains("private int extracted(A.Builder a, B.Builder b)");
    // a private type of the file itself is used by its name, never imported
    assertExtracts("private record P(int v) {}\n  int m(P p) { return «p.v() + 1»; }", method);
    // the method's type variables the signature needs, with those their bounds name
    assertThat(
            assertExtracts(
                "<A, K, V extends Comparable<K>> V m(List<V> l, A a) { use(a); return «l.get(0)»; }",
                method))
        .contains("private <K, V extends Comparable<K>> V extracted(List<V> l)");
    // a class's type variable in an instance method; a name an inherited method already has
    assertExtracts("static class Box<E> { E e; E m(E x) { return «x != null ? x : e»; } }", method);
    assertExtracts(
        "static class Base { void extracted() {} }\n"
            + "  static class Sub extends Base { int m(String a) { return «a.length() + 1»; } }",
        "Extract method 'extracted1'");
  }

  @Test
  void codeAction_extractMethod_notMovableOrPointless_offersNothing() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("void", "void m(String a) { «use(a)»; }");
    cases.put("writes a local used later", "int m(int i) { int j = «i++»; return i + j; }");
    cases.put("non-denotable", "void m() { use(«List.of(1, \"a\")»); }");
    cases.put("constant", "int m() { return «1 + 2»; }");
    cases.put(
        "enum case label",
        "enum E { A, B }\n  void m(E e) { switch (e) { case «A»: break; default: break; } }");
    cases.put(
        "annotation value",
        "@interface Ann { Class<?> value(); }\n  @Ann(«String.class») void m() {}");
    cases.put("interface", "interface I { default int m(String a) { return «a.length() + 1»; } }");
    cases.put(
        "static with class type variable",
        "static class Box<E> {\n    Box(E e) { this(«List.of(e)»); }\n    Box(List<E> l) {}\n  }");
    cases.put("local class", "int m() { class L { int v() { return 1; } } return «new L().v()»; }");
    cases.put("enum constant creation", "enum E { A«(\"a\")»; E(String s) {} }");
    cases.put(
        "type variable only the return type names",
        "<T> void m() { final var l = «new ArrayList<T>()»; List<T> t = l; use(t); }");
    cases.put("assignment target", "void m(int[] a) { «a[0]» = 1; }");
    cases.put("this", "Object m() { return «this»; }");
    cases.put("a bare variable", "Object m(Object o) { return «o»; }");
    cases.put("enum constant argument", "enum E { A(«\"a\".trim()»); E(String s) {} }");
    cases.put(
        "inner class's super(...) argument",
        "int f = 1;\n  static class Base { Base(int x) {} }\n"
            + "  class Inner extends Base { Inner() { super(«f + 1»); } }");
    cases.put(
        "a local type in the signature",
        "void m() { record P(int v) {} final P p = new P(1); use(«p.v() + 1»); }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .noneMatch(t -> t.startsWith("Extract method")));
  }

  @Test
  void codeAction_extractMethod_statements_becomeVoidMethod() {
    final var source =
        inTestClass(
            "void m(String a) {\n    String t = a.trim();\n«    use(t);\n    use(t.length());»\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              void m(String a) {
                String t = a.trim();
                extracted(t);
              }

              private void extracted(String t) {
                use(t);
                use(t.length());
              }
            """);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extractMethod_declarationUsedLater_movesToTheCall() {
    final var source =
        inTestClass(
            "void m(String a) {\n«    final String t = a.trim();\n    use(t);»\n    use(t.length());\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              void m(String a) {
                final String t = extracted(a);
                use(t.length());
              }

              private String extracted(String a) {
                final String t = a.trim();
                use(t);
                return t;
              }
            """);
    assertCompilesClean(rewritten);
  }

  @Test
  void codeAction_extractMethod_statementsReturningOnEveryPath_returnTheCall() {
    final var source =
        inTestClass(
            "int m(String a) {\n    use(a);\n«    if (a.isEmpty()) {\n      return 0;\n    }\n"
                + "    return a.length();»\n  }");
    final String rewritten =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(markedActions(source), "Extract method 'extracted'"));

    assertThat(rewritten)
        .contains(
            """
              int m(String a) {
                use(a);
                return extracted(a);
              }

              private int extracted(String a) {
                if (a.isEmpty()) {
                  return 0;
                }
                return a.length();
              }
            """);
    assertCompilesClean(rewritten);

    // a lambda's return is its own: the statements stay, though the lambda around them may move
    assertThat(
            assertExtracts(
                "java.util.function.Function<String, Integer> f = s -> { «use(s);\n    return 1;» };",
                "Extract method"))
        .doesNotContain("return extracted(");
  }

  @Test
  void codeAction_extractMethod_statementContexts_rewriteToCompilingCode() {
    final String method = "Extract method";
    assertExtracts(
        "void m(List<String> l) { for (String s : l) { «use(s);\n    use(s.trim());» } }", method);
    // no value before the region: the new method declares the local itself
    assertExtracts(
        "void m(boolean b) {\n    String line;\n«    line = String.valueOf(b);\n    use(line);»\n  }",
        method);
    // a bare array initializer is returned with its array creation
    assertThat(assertExtracts("String[] names = «{\"a\", \"b\"}»;", method))
        .contains("return new String[] {\"a\", \"b\"};");
    // the warnings the enclosing method suppresses stay suppressed
    assertThat(
            assertExtracts(
                "@SuppressWarnings(\"deprecation\") int m(java.util.Date d) { return «d.getYear() + 1»; }",
                method))
        .contains("  @SuppressWarnings(\"deprecation\")\n  private int extracted(");
    // a local declaration's suppressions join the method's, in one annotation
    assertThat(
            assertExtracts(
                "@SuppressWarnings(\"deprecation\") void m(Object o) {\n"
                    + "    @SuppressWarnings(\"unchecked\") final Map<String, Object> x = "
                    + "«(Map<String, Object>) o»;\n    use(x);\n  }",
                method))
        .contains(
            "  @SuppressWarnings({\"unchecked\", \"deprecation\"})\n  private Map<String, Object> extracted(");
    // the file's final parameters are mirrored, except on one the code assigns
    assertThat(
            assertExtracts(
                "void m(final String a) {\n    int n = a.length();\n«    n = n * 2;\n    use(a);»\n"
                    + "    use(n);\n  }",
                method))
        .contains("private int extracted(int n, final String a)");
    // a local the new method declares has its type imported, however the source spelled it
    assertThat(
            assertExtracts(
                "void m() {\n    java.util.concurrent.atomic.AtomicLong n;\n"
                    + "«    n = new java.util.concurrent.atomic.AtomicLong();\n    use(n);»\n  }",
                method))
        .contains("import java.util.concurrent.atomic.AtomicLong;", "    AtomicLong n;");
    // a loop with its own break moves whole; case statements without their break
    assertExtracts(
        "void m(List<String> l) { «for (String s : l) { if (s.isEmpty()) break; use(s); }» }",
        method);
    assertExtracts(
        "void m(int k) { switch (k) { case 1: «use(k);\n    use(k + 1);» break; default: break; } }",
        method);
    // checked exceptions: declared when thrown, not when caught inside
    assertExtracts(
        "void m(java.io.File f) throws java.io.IOException { «new java.io.FileInputStream(f).close();» }",
        method);
    assertExtracts(
        "void m(java.io.File f) {\n    «try { new java.io.FileInputStream(f).close(); }"
            + " catch (java.io.IOException e) { use(e); }»\n  }",
        method);
    assertExtracts(
        "void m(List<String> l, String p) { l.forEach(x -> { «use(x + p);» }); }", method);
    assertExtracts("static void s(String a) { «System.out.println(a);» }", method);
    // an earlier local assigned at the top level comes back out, its old value going in only when
    // read before; read earlier in the loop, the next iteration needs it
    assertThat(
            assertExtracts(
                "void m(String a) {\n    int n = 0;\n«    n = a.length();\n    use(n);»\n    use(n);\n  }",
                method))
        .contains(
            "n = extracted(a);", "    int n;\n    n = a.length();\n    use(n);\n    return n;");
    // a lone assignment extracts the value it stores
    assertThat(assertExtracts("void m(String a) { int n = 0; «n = a.length();» use(n); }", method))
        .contains("n = extracted(a);", "return a.length();");
    assertThat(assertExtracts("void m(int n) { «n = n * 2;\n    use(n);» use(n + 1); }", method))
        .contains("n = extracted(n);");
    assertThat(
            assertExtracts(
                "void m(List<String> l) { int n = 0; for (String s : l) { use(n); «n = s.length();» } }",
                method))
        .contains("n = extracted(s);");

    // a declaration used later is not a statement region yet; its value still is an expression
    final var declaration =
        inTestClass("void m(String a) {\n«    String t = a.trim();»\n    use(t);\n  }");
    assertThat(
            applyEdits(
                unmarked(declaration),
                editsOfActionStartingWith(markedActions(declaration), "Extract method")))
        .contains("String t = extracted(a);");
  }

  @Test
  void codeAction_extractMethod_statementsChangingControlOrState_offerNothing() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("return on some paths", "int m(String a) { «if (a == null) return 0;» return 1; }");
    cases.put("return from a void method", "void m(String a) { «use(a);\n    return;» }");
    cases.put(
        "continue out",
        "void m(List<String> l) { for (String s : l) { «if (s.isEmpty()) continue;» use(s); } }");
    cases.put(
        "case with its break",
        "void m(int k) { switch (k) { case 1: «use(k); break;» default: break; } }");
    cases.put("never completes", "void m() { «throw new IllegalStateException();» }");
    cases.put(
        "never completes, in a switch expression",
        "int m(int k) { return switch (k) { case 1 -> { «throw new IllegalStateException();» }"
            + " default -> 0; }; }");
    cases.put(
        "assigned conditionally",
        "void m(String a) { int n = 0; «if (a.isEmpty()) n = 1;» use(n); }");
    cases.put("two values", "void m() { «int x = 1;\n    int y = 2;» use(x + y); }");
    cases.put(
        "final field",
        "final String name;\n  Test(String n) { «this.name = n.trim();\n    use(n);» }");
    cases.put("this(...)", "Test(int x) {}\n  Test() { «this(1);» }");
    cases.put("local class", "void m() { class L {} «use(new L());» }");
    cases.put("local class used afterwards", "void m() { «record P(int v) {}» use(new P(1)); }");
    cases.put(
        "rethrow",
        "void m(java.io.File f) throws java.io.IOException {\n"
            + "    try { f.getCanonicalPath(); } catch (java.io.IOException e) { «throw e;» }\n  }");
    cases.put("two blocks", "void m(boolean b) { «use(1); if (b) { use(2);» } }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .noneMatch(t -> t.startsWith("Extract method")));
  }

  @Test
  void codeAction_extractMethod_copies_replacedInTheClassOwnMembers() {
    // copies in another member count, in a nested class do not; one in a static member makes the
    // replace-all method static while the single one stays an instance method
    final String source =
        inTestClass(
            "static String NAME = \"x\";\n"
                + "  void m() { use(«NAME.trim()»); }\n"
                + "  static void s() { System.out.println(NAME.trim()); }\n"
                + "  static class N { void n() { System.out.println(NAME.trim()); } }");
    final var actions = markedActions(source);
    assertThat(rightTitles(actions))
        .contains(
            "Extract method 'extracted'", "Extract method 'extracted' (replace all 2 occurrences)");
    final String all =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(actions, "Extract method 'extracted' (replace"));
    assertThat(all)
        .contains("private static String extracted()")
        .contains("System.out.println(extracted());");
    assertCompilesClean(all);
    assertCompilesClean(
        applyEdits(
            unmarked(source), editsOfActionStartingWith(actions, "Extract method 'extracted'")));

    // an if's parentheses are the statement's: replaced, `if extracted()` would not parse
    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "boolean m(Object o) { boolean b = «(o == null)»; if (o == null) { use(o); } return b; }"))))
        .contains("Extract method 'extracted'")
        .noneMatch(t -> t.startsWith("Extract method 'extracted' (replace all"));
    // a copy in an annotation value is not a copy: it needs a constant, not a call
    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "@interface Ann { Class<?> value(); }\n"
                            + "  Object m() { return «String.class»; }\n  @Ann(String.class) void n() {}"))))
        .noneMatch(t -> t.startsWith("Extract method 'extracted' (replace all"));
    // a poly expression typed differently elsewhere is not a copy: one method returns one type
    assertThat(
            rightTitles(
                markedActions(
                    inTestClass(
                        "void m() { List<String> a = «List.of()»; List<Integer> b = List.of(); use(a); use(b); }"))))
        .contains("Extract method 'extracted'")
        .noneMatch(t -> t.startsWith("Extract method 'extracted' (replace all"));

    // a copy that is an assignment's target is not a copy
    assertThat(
            rightTitles(markedActions(inTestClass("void m(int[] a) { use(«a[0]»); a[0] = 1; }"))))
        .contains("Extract method 'extracted'")
        .noneMatch(t -> t.contains("replace all"));
  }

  @Test
  void codeAction_extractMethod_typeNamedLikeAnotherInTheFile_writtenQualified()
      throws IOException {
    // the simple name is taken by an import, by a type the file declares, or by one of its package
    final String imported =
        """
        package com.example;
        import java.sql.Date;
        class Test {
          Date sql;
          long m(java.util.Date d) { return «d.getTime() + 1»; }
        }
        """;
    final String declared =
        inTestClass(
            "static class Date {}\n  long m(java.util.Date d) { return «d.getTime() + 1»; }");
    final Path sibling = tmp.resolve("Date.java");
    Files.writeString(sibling, "package com.example;\npublic class Date {}\n");
    final Path classDir = tmp.resolve("sibling-classes");
    TestCompiler.compileToDir(classDir, sibling);
    session.close();
    session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));
    final String samePackage =
        inTestClass("long m(java.util.Date d) { return «d.getTime() + 1»; }");
    for (final String source : List.of(imported, declared, samePackage)) {
      final String rewritten =
          applyEdits(
              unmarked(source), editsOfActionStartingWith(markedActions(source), "Extract method"));
      assertThat(rewritten)
          .contains("private long extracted(java.util.Date d)")
          .doesNotContain("import java.util.Date;");
      assertCompilesClean(rewritten);
    }
  }

  @Test
  void codeAction_extractMethod_typeNameOfTheFile_keptOrRefusedWhenUnwritable() {
    // a nested type the file imports keeps the name the file uses
    final String imported =
        """
        package com.example;
        import java.util.Map.Entry;
        class Test {
          int m(Entry<String, String> e) { return «e.hashCode() + 1»; }
        }
        """;
    final String rewritten =
        applyEdits(
            unmarked(imported),
            editsOfActionStartingWith(markedActions(imported), "Extract method"));
    assertThat(rewritten).contains("private int extracted(Entry<String, String> e)");
    assertCompilesClean(rewritten);

    // another class's private nested type, returned by its method, cannot be written here
    final String hidden =
        """
        package com.example;
        class Other {
          private static class Hidden {}
          static Hidden make() { return new Hidden(); }
        }
        class Test {
          Object m() { return «Other.make()»; }
        }
        """;
    assertThat(rightTitles(markedActions(hidden))).noneMatch(t -> t.startsWith("Extract method"));
  }

  @Test
  void codeAction_extractMethod_statementCopies_calledWithTheirOwnLocals() {
    // a copy in another method, its locals and its declaration named differently
    final String source =
        inTestClass(
            "void m(String a) {\n«    final String t = a.trim();\n    use(t);»\n    use(t.length());\n  }\n"
                + "  void n(String b) {\n    final String u = b.trim();\n    use(u);\n"
                + "    use(u.isEmpty());\n  }");
    final var actions = markedActions(source);
    assertThat(rightTitles(actions))
        .contains("Extract method 'extracted' (replace all 2 occurrences)");
    final String all =
        applyEdits(
            unmarked(source),
            editsOfActionStartingWith(actions, "Extract method 'extracted' (replace"));
    assertThat(all)
        .contains(
            """
              void m(String a) {
                final String t = extracted(a);
                use(t.length());
              }
            """,
            """
              void n(String b) {
                final String u = extracted(b);
                use(u.isEmpty());
              }
            """);
    assertCompilesClean(all);
  }

  @Test
  void codeAction_extractMethod_statementLookalikes_notReplaced() {
    final Map<String, String> cases =
        Map.of(
            "a local of another type",
            "void m(String a) { «use(a);\n    use(a + 1);» }\n  void n(int b) { use(b); use(b + 1); }",
            "two locals standing for one",
            "void m(String a, String b) { «use(a);\n    use(b);» }\n  void n(String c) { use(c); use(c); }",
            "overlapping the selection",
            "void m(String a) { «use(a);\n    use(a);» use(a); }",
            "a value used after the copy",
            "void m(String a) { «int x = a.length();\n    use(x);» }\n"
                + "  void n(String b) { int y = b.length(); use(y); use(y); }");
    cases.forEach(
        (label, member) ->
            assertThat(rightTitles(markedActions(inTestClass(member))))
                .as(label)
                .contains("Extract method 'extracted'")
                .noneMatch(t -> t.contains("replace all")));
  }
}
