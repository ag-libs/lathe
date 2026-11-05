package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.analysis.testing.TestJavac;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheRename;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// rename_symbol: what it rewrites across files and modules, and every reason it refuses.
class LatheEngineRenameTest extends LatheEngineTestBase {
  @Test
  void rename_methodUsedInAnotherFile_rewritesBothFilesOnDisk_listsTheNameLeftAsText()
      throws Exception {
    final GreetFixture fx = greetFixture();
    final Path labels =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Labels.java",
            "package com.example;\nclass Labels { String key = \"greet\"; }\n");
    engine = new LatheEngine(tmp);

    final LatheRename rename = renameAt(fx.callee(), fx.calleeSource(), "greet", "welcome");

    assertThat(rename.newName()).isEqualTo("welcome");
    assertThat(rename.totalEdits()).isGreaterThanOrEqualTo(2);
    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Callee.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"));
    assertThat(Files.readString(fx.callee())).contains("void welcome()").doesNotContain("greet");
    assertThat(Files.readString(fx.caller())).contains("c.welcome()").doesNotContain("greet");
    assertThat(rename.mentions())
        .as("a string literal is never renamed, only listed")
        .singleElement()
        .satisfies(mention -> assertThat(mention.uri()).endsWith("Labels.java"));
    assertThat(Files.readString(labels)).contains("\"greet\"");
  }

  @Test
  void rename_thenRenameBack_afterAQueryOnTheCaller_restoresTheCaller() throws Exception {
    final GreetFixture fx = crossPackageGreetFixture();
    engine = new LatheEngine(tmp);
    // A query leaves no open document behind to answer the rename back from its old text.
    engine.diagnostics(fx.caller());

    renameAt(fx.callee(), fx.calleeSource(), "greet", "welcome");
    assertThat(Files.readString(fx.caller())).contains("c.welcome()");

    // The renamed caller was written to disk, never opened: only a candidate index that follows
    // the disk finds it again.
    final LatheRename back = renameAt(fx.callee(), fx.calleeSource(), "greet", "greet");

    assertThat(back.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"));
    assertThat(Files.readString(fx.callee())).isEqualTo(fx.calleeSource());
    assertThat(Files.readString(fx.caller())).isEqualTo(fx.callerSource());
  }

  @Test
  void rename_methodAlsoCalledFromGeneratedSource_leavesTheGeneratedSourceAlone() throws Exception {
    final GreetFixture fx = greetFixture();
    // An annotation processor's output that calls the method: it regenerates from the renamed
    // source, so editing it would only collide with the regenerated copy.
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    final String generatedSource =
        "package com.example; class CalleeHelper { void run(Callee c) { c.greet(); } }";
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/CalleeHelper.java"),
            generatedSource,
            1_000L);
    engine = new LatheEngine(tmp);

    final LatheRename rename = renameAt(fx.callee(), fx.calleeSource(), "greet", "welcome");

    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"))
        .noneSatisfy(uri -> assertThat(uri).endsWith("CalleeHelper.java"));
    assertThat(Files.readString(generated)).isEqualTo(generatedSource);
  }

  @Test
  void rename_symbolDeclaredOutsideTheProject_refusesWithTheReasonAndEditsNothing()
      throws Exception {
    final String source =
        """
        package com.example;
        class Sample {
          int size(String text) { return text.length(); }
          @Override public String toString() { return "sample"; }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    // A JDK type, a JDK method, and a project method overriding a JDK one.
    List.of("String text", "length", "toString")
        .forEach(
            symbol -> {
              assertThatThrownBy(() -> renameAt(file, source, symbol, "renamed"))
                  .as(symbol)
                  .hasMessageContaining("outside the project");
            });
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  @Test
  void rename_toATakenOrInvalidName_refusesWithTheReasonAndEditsNothing() throws Exception {
    final String source =
        """
        package com.example;
        class Sample {
          int count;
          int total;
          int sum() { return count + total; }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(file, source, "count", "total"))
        .hasMessageContaining(
            "`count` cannot be renamed to `total`: Sample already declares field `total`");
    assertThatThrownBy(() -> renameAt(file, source, "count", "a.b"))
        .hasMessageContaining("`a.b` is not a valid name");
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  // Task has no Task.java of its own; its members and its nested type's are still the project's,
  // while its override of a JDK method stays refused.
  @Test
  void rename_memberOfASecondaryTopLevelType_renamesItAndStillRefusesAJdkOverride()
      throws Exception {
    final String source =
        """
        package com.example;
        class Sample {
          void use(Task task, Task.Step step) { task.run(); step.next(); }
        }
        interface Task {
          void run();
          class Step { void next() {} @Override public String toString() { return "step"; } }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(file, source, "toString", "renamed"))
        .hasMessageContaining("outside the project");
    // From each first occurrence, a use, on the text the previous rename left.
    for (final String name : List.of("run", "next", "Step", "Task")) {
      renameAt(file, Files.readString(file), name, "%sRenamed".formatted(name));
    }

    assertThat(Files.readString(file))
        .contains("void use(TaskRenamed task, TaskRenamed.StepRenamed step)")
        .contains("task.runRenamed(); step.nextRenamed();")
        .contains("interface TaskRenamed {")
        .contains("void runRenamed();")
        .contains("class StepRenamed { void nextRenamed() {}");
  }

  // Renaming the interface method also edits the override, which then no longer overrides its
  // superclass's run: Thread's outside the project (Task), Base's inside it (Step). Starting from
  // the override hits the same conflict. A diamond whose every parent is in the family renames.
  @Test
  void rename_overrideAlsoOverridingAnUnrelatedSupertype_refusesButAFamilyDiamondRenames()
      throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Task", "package com.example; interface Task { void run(); }",
            "Step", "package com.example; interface Step { void run(); }",
            "Base", "package com.example; class Base { public void run() {} }",
            "Worker",
                "package com.example; class Worker extends Thread implements Task {"
                    + " @Override public void run() {} }",
            "Job",
                "package com.example; class Job extends Base implements Step {"
                    + " @Override public void run() {} }",
            "Plan", "package com.example; interface Plan { void run(); }",
            "Phase", "package com.example; interface Phase extends Plan { void run(); }",
            "Impl",
                "package com.example; class Impl implements Plan, Phase {"
                    + " @Override public void run() {} }");
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        sources.entrySet().stream()
            .collect(
                Collectors.toMap(
                    source -> "com/example/%s.java".formatted(source.getKey()),
                    Map.Entry::getValue)),
        List.of(),
        1_000L);
    engine = new LatheEngine(tmp);

    Map.of("Task", "Worker.java", "Step", "Job.java", "Job", "Job.java")
        .forEach(
            (start, override) ->
                assertThat(
                        catchThrowable(
                            () ->
                                renameAt(exampleFile(start), sources.get(start), "run", "execute")))
                    .as(start)
                    .hasMessageContaining("override in %s".formatted(override)));
    sources.forEach(
        (type, source) -> assertThat(exampleFile(type)).as(type).content().isEqualTo(source));

    renameAt(exampleFile("Plan"), sources.get("Plan"), "run", "execute");
    List.of("Plan", "Phase", "Impl")
        .forEach(
            type ->
                assertThat(exampleFile(type))
                    .as(type)
                    .content()
                    .contains("void execute()")
                    .doesNotContain("run"));
  }

  // A subtype implementing the interface method through a superclass outside the family, Thread's
  // (Worker) or a project class's that overrides nothing (Job), would lose its implementation. One
  // implemented through a superclass in the family renames with it.
  @Test
  void rename_methodImplementedThroughASuperclassOutsideTheFamily_refusesNamingTheSubtype()
      throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Task", "package com.example; interface Task { void run(); }",
            "Worker", "package com.example; class Worker extends Thread implements Task {}",
            "Base", "package com.example; class Base { public void run() {} }",
            "Step", "package com.example; interface Step { void run(); }",
            "Job", "package com.example; class Job extends Base implements Step {}",
            "Plan", "package com.example; interface Plan { void run(); }",
            "AbstractPlan",
                "package com.example; abstract class AbstractPlan implements Plan {"
                    + " public void run() {} }",
            "Impl", "package com.example; class Impl extends AbstractPlan implements Plan {}");
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        sources.entrySet().stream()
            .collect(
                Collectors.toMap(
                    source -> "com/example/%s.java".formatted(source.getKey()),
                    Map.Entry::getValue)),
        List.of(),
        1_000L);
    engine = new LatheEngine(tmp);

    Map.of(
            "Task",
            "Worker implements it through Thread.run",
            "Step",
            "Job implements it through Base.run")
        .forEach(
            (start, reason) ->
                assertThat(
                        catchThrowable(
                            () ->
                                renameAt(exampleFile(start), sources.get(start), "run", "execute")))
                    .as(start)
                    .hasMessageContaining(reason));
    sources.forEach(
        (type, source) -> assertThat(exampleFile(type)).as(type).content().isEqualTo(source));

    renameAt(exampleFile("Plan"), sources.get("Plan"), "run", "execute");
    List.of("Plan", "AbstractPlan")
        .forEach(
            type -> assertThat(exampleFile(type)).as(type).content().contains("void execute()"));
  }

  // A rename that changes what a name means is refused, naming the place: a use the new name
  // captures (a shadowed field, a call another file makes) or a renamed site that would then mean
  // something else (another overload, an inherited method, a local). The same renames where nothing
  // changes meaning go through, a same-named local in a sibling method included.
  @Test
  void rename_changingWhatANameMeans_refusesNamingThePlaceAndOtherwiseRenames() throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Shadow",
                """
                package com.example;
                import java.util.function.IntUnaryOperator;
                class Shadow {
                  int value = 10;
                  int count = 1;
                  int param(int x) { return x + value; }
                  IntUnaryOperator lambda() { return y -> y + value; }
                  int local() { int z = 1; return z + value; }
                  int field() { int total = 0; return count + total; }
                  int safe(int w) { return w + 1; }
                  int sibling() { int n = 1; return n; }
                  int other() { int value = 2; return value; }
                }
                """,
            "Overload",
                """
                package com.example;
                class Overload {
                  void setValue(int v) {}
                  long twice(long n) { return n * 2; }
                  void use() { twice(7); }
                }
                """,
            "Api",
                """
                package com.example;
                public class Api {
                  public void bar(long n) {}
                  public void foo(int n) {}
                }
                """,
            "Caller",
                """
                package com.example;
                class Caller {
                  void call(Api api) { api.bar(1); }
                }
                """,
            "Base", "package com.example; class Base { void setValue(int v) {} void greet() {} }",
            "Greeter", "package com.example; class Greeter extends Base { void welcome() {} }",
            "Sub",
                """
                package com.example;
                class Sub extends Base {
                  long twice(long n) { return n * 2; }
                  void use() { twice(7); }
                }
                """);
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        sources.entrySet().stream()
            .collect(
                Collectors.toMap(
                    source -> "com/example/%s.java".formatted(source.getKey()),
                    Map.Entry::getValue)),
        List.of(),
        1_000L);
    engine = new LatheEngine(tmp);

    final List<List<String>> refused =
        List.of(
            List.of(
                "Shadow",
                "x)",
                "value",
                "`value` at Shadow.java:6 would then refer to the renamed parameter"),
            List.of(
                "Shadow",
                "y ->",
                "value",
                "`value` at Shadow.java:7 would then refer to the renamed parameter"),
            List.of(
                "Shadow",
                "z =",
                "value",
                "`value` at Shadow.java:8 would then refer to the renamed local variable"),
            List.of(
                "Shadow",
                "count =",
                "total",
                "Shadow.java:9 would then refer to the local variable `total`"),
            List.of(
                "Overload",
                "twice(long",
                "setValue",
                "Overload.java:5 would then refer to the method `Overload.setValue(int)`"),
            List.of(
                "Sub",
                "twice(long",
                "setValue",
                "Sub.java:4 would then refer to the method `Base.setValue(int)`"),
            List.of(
                "Api",
                "foo(",
                "bar",
                "`bar` at Caller.java:3 would then refer to the renamed method"));
    refused.forEach(
        probe ->
            assertThat(
                    catchThrowable(
                        () ->
                            renameAt(
                                exampleFile(probe.get(0)),
                                sources.get(probe.get(0)),
                                probe.get(1),
                                probe.get(2))))
                .as("%s %s -> %s", probe.get(0), probe.get(1), probe.get(2))
                .hasMessageContaining(probe.get(3)));
    sources.forEach(
        (type, source) -> assertThat(exampleFile(type)).as(type).content().isEqualTo(source));

    renameAt(exampleFile("Shadow"), sources.get("Shadow"), "w)", "v");
    renameAt(exampleFile("Shadow"), Files.readString(exampleFile("Shadow")), "n = 1", "value");
    renameAt(exampleFile("Overload"), sources.get("Overload"), "twice(long", "doubled");
    assertThat(exampleFile("Shadow"))
        .content()
        .contains("int safe(int v) { return v + 1; }")
        .contains("int sibling() { int value = 1; return value; }");
    assertThat(exampleFile("Overload")).content().contains("void use() { doubled(7); }");
  }

  // A call in another module that the new name would capture is refused too: that module's files
  // are attributed with the edited declaration. A call no overload change can reach renames.
  @Test
  void rename_capturingACallInAnotherModule_refusesUnlessNoCallChangesMeaning() throws Exception {
    final String api =
        """
        package com.example.api;
        public class Api {
          public void bar(long n) {}
          public void foo(int n) {}
        }
        """;
    final Path apiFile =
        TestCompiler.writeMirroredModule(
                tmp, "api", Map.of("com/example/api/Api.java", api), List.of(), 1_000L)
            .get(0);
    final Function<String, Path> app =
        argument ->
            IOUtil.unchecked(
                () ->
                    TestCompiler.writeMirroredModule(
                            tmp,
                            "app",
                            Map.of(
                                "com/example/app/Remote.java",
                                """
                                package com.example.app;
                                class Remote {
                                  void call(com.example.api.Api api) { api.bar(%s); }
                                }
                                """
                                    .formatted(argument)),
                            List.of("api"),
                            1_000L)
                        .get(0));
    app.apply("2");
    engine = new LatheEngine(tmp);

    assertThat(catchThrowable(() -> renameAt(apiFile, api, "foo(", "bar")))
        .hasMessageContaining("`bar` at Remote.java:3 would then refer to the renamed method");
    assertThat(apiFile).content().isEqualTo(api);

    engine.close();
    app.apply("2L");
    engine = new LatheEngine(tmp);
    renameAt(apiFile, api, "foo(", "bar");
    assertThat(apiFile).content().contains("public void bar(int n) {}");
  }

  // A type renamed onto a name a file already uses through an on-demand import would capture that
  // use; renamed onto a free name, it renames and moves its file.
  @Test
  void rename_typeCapturingAnImportedName_refusesAndOtherwiseRenamesAndMoves() throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Helper", "package com.example;\npublic class Helper {}\n",
            "UsesList",
                """
                package com.example;
                import java.util.*;
                class UsesList {
                  List<String> names = new ArrayList<>();
                  Helper helper = new Helper();
                }
                """);
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        sources.entrySet().stream()
            .collect(
                Collectors.toMap(
                    source -> "com/example/%s.java".formatted(source.getKey()),
                    Map.Entry::getValue)),
        List.of(),
        1_000L);
    engine = new LatheEngine(tmp);

    assertThat(
            catchThrowable(
                () -> renameAt(exampleFile("Helper"), sources.get("Helper"), "Helper {", "List")))
        .hasMessageContaining("`List` at UsesList.java:4 would then refer to the renamed class");
    sources.forEach(
        (type, source) -> assertThat(exampleFile(type)).as(type).content().isEqualTo(source));

    renameAt(exampleFile("Helper"), sources.get("Helper"), "Helper {", "Assistant");
    assertThat(exampleFile("Assistant")).content().contains("public class Assistant {}");
    assertThat(exampleFile("UsesList")).content().contains("Assistant helper = new Assistant();");
  }

  // Renames whose sites are not plain names the check sees as they are: record components (header,
  // accessor, canonical constructor), enum constants in case labels, static imports, Javadoc links,
  // an interface method with its implementation, a method reference, a type renamed at its `new`.
  // None changes meaning, so each renames.
  @Test
  void rename_unusualSitesKeepingTheirMeaning_renames() throws Exception {
    final Map<String, String> sources =
        Map.of(
            "Point",
                """
                package com.example;
                record Point(int x, int y) {
                  Point {
                    if (x < 0) { throw new IllegalArgumentException(); }
                  }
                  int sum() { return x + y(); }
                }
                """,
            "Color", "package com.example;\nenum Color { RED, GREEN }\n",
            "Shape",
                """
                package com.example;
                interface Shape {
                  /** Area; see {@link #area()}. */
                  double area();
                }
                """,
            "Circle",
                """
                package com.example;
                import java.util.function.ToDoubleFunction;
                class Circle implements Shape {
                  public double area() { return 1; }
                  ToDoubleFunction<Shape> ref() { return Shape::area; }
                }
                """,
            "Util",
                "package com.example;\nclass Util { static int twice(int n) { return n * 2; } }\n",
            "Client",
                """
                package com.example;
                import static com.example.Util.twice;
                class Client {
                  int use(Point p, Color c) {
                    switch (c) {
                      case RED: return twice(p.x());
                      default: return new Helper().hashCode();
                    }
                  }
                }
                """,
            "Helper", "package com.example;\nclass Helper {}\n");
    TestCompiler.writeMirroredModule(
        tmp,
        "module",
        sources.entrySet().stream()
            .collect(
                Collectors.toMap(
                    source -> "com/example/%s.java".formatted(source.getKey()),
                    Map.Entry::getValue)),
        List.of(),
        1_000L);
    engine = new LatheEngine(tmp);

    final List<List<String>> renames =
        List.of(
            List.of("Point", "x, int", "left"),
            List.of("Color", "RED", "CRIMSON"),
            List.of("Shape", "area();", "surface"),
            List.of("Util", "twice(int", "doubled"),
            List.of("Client", "Helper()", "Assistant"));
    for (final List<String> rename : renames) {
      final Path file = exampleFile(rename.get(0));
      renameAt(file, Files.readString(file), rename.get(1), rename.get(2));
    }

    assertThat(exampleFile("Point"))
        .content()
        .contains("record Point(int left, int y)")
        .contains("if (left < 0)")
        .contains("return left + y();");
    assertThat(exampleFile("Shape"))
        .content()
        .contains("{@link #surface()}")
        .contains("double surface();");
    assertThat(exampleFile("Circle"))
        .content()
        .contains("public double surface()")
        .contains("Shape::surface");
    assertThat(exampleFile("Client"))
        .content()
        .contains("import static com.example.Util.doubled;")
        .contains("case CRIMSON: return doubled(p.left());")
        .contains("new Assistant().hashCode()");
  }

  // A common new name brings in many files that already use it; they are attributed in bounded
  // batches with the edited files, and a capture in the last batch still refuses the rename.
  @Test
  void rename_captureAmongManyFilesUsingTheNewName_refuses() throws Exception {
    final String api =
        """
        package com.example;
        public class Api {
          public void bar(long n) {}
          public void foo(int n) {}
        }
        """;
    final Map<String, String> sources = new HashMap<>();
    sources.put("com/example/Api.java", api);
    IntStream.range(0, 104)
        .forEach(
            i ->
                sources.put(
                    "com/example/Caller%03d.java".formatted(i),
                    "package com.example;\nclass Caller%03d { void c(Api a) { a.bar(1L); } }\n"
                        .formatted(i)));
    sources.put(
        "com/example/ZCaller.java",
        "package com.example;\nclass ZCaller { void c(Api a) { a.bar(1); } }\n");
    final Path apiFile =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L).stream()
            .filter(file -> file.endsWith("Api.java"))
            .findFirst()
            .orElseThrow();
    engine = new LatheEngine(tmp);

    assertThat(catchThrowable(() -> renameAt(apiFile, api, "foo(", "bar")))
        .hasMessageContaining("`bar` at ZCaller.java:2 would then refer to the renamed method");
  }

  // Each module's check sees only the edits of modules it depends on: app2's edited caller, which
  // uses a type only app2 has, is not attributed in app1, where it could not compile and would
  // leave app1's renamed calls unresolved. Nothing changes meaning, so the rename goes through.
  @Test
  void rename_editsInIndependentModules_eachCheckedWithWhatItSeesAndRenames() throws Exception {
    final String api = "package com.example.api;\npublic class Api { public void foo() {} }\n";
    final Path apiFile =
        TestCompiler.writeMirroredModule(
                tmp, "api", Map.of("com/example/api/Api.java", api), List.of(), 1_000L)
            .get(0);
    TestCompiler.writeMirroredModule(
        tmp,
        "app1",
        Map.of(
            "com/example/app1/Use1.java",
            "package com.example.app1;\nclass Use1 { void u(com.example.api.Api a) { a.foo(); } }\n"),
        List.of("api"),
        1_000L);
    TestCompiler.writeMirroredModule(
        tmp,
        "app2",
        Map.of(
            "com/example/app2/local/Local.java",
            "package com.example.app2.local;\npublic class Local {}\n",
            "com/example/app2/Use2.java",
            "package com.example.app2;\nimport com.example.app2.local.Local;\nclass Use2 { Local l; void u(com.example.api.Api a) { a.foo(); } }\n"),
        List.of("api"),
        1_000L);
    engine = new LatheEngine(tmp);

    renameAt(apiFile, api, "foo(", "bar");

    assertThat(apiFile).content().contains("public void bar() {}");
    assertThat(tmp.resolve("app1/src/main/java/com/example/app1/Use1.java"))
        .content()
        .contains("a.bar();");
    assertThat(tmp.resolve("app2/src/main/java/com/example/app2/Use2.java"))
        .content()
        .contains("a.bar();");
  }

  // A pattern variable is a local: renaming one `s` leaves a same-named pattern variable in another
  // method alone, as for any local.
  @Test
  void rename_patternVariable_renamesOnlyItsOwnUses() throws Exception {
    final String source =
        """
        package com.example;
        class Patterns {
          int first(Object o) { return o instanceof String s ? s.length() : 0; }
          int second(Object o) { return o instanceof String s ? s.length() : 1; }
        }
        """;
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Patterns.java", source);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), file);
    engine = new LatheEngine(tmp);

    renameAt(file, source, "s ?", "text");

    assertThat(file)
        .content()
        .contains("int first(Object o) { return o instanceof String text ? text.length() : 0; }")
        .contains("int second(Object o) { return o instanceof String s ? s.length() : 1; }");
  }

  private Path exampleFile(final String type) {
    return tmp.resolve("module/src/main/java/com/example/%s.java".formatted(type));
  }

  // A static import names every member of its name. Renaming one overload, the import must stay for
  // the others, and the renamed calls in that file would be left without one; a member alone under
  // its name renames its import.
  @Test
  void rename_overloadOfAStaticallyImportedName_refuses_soleMemberRenamesItsImport()
      throws Exception {
    final String util =
        """
        package com.example;
        public class Util {
          public static int go(int i) { return i; }
          public static String go(String s) { return s; }
          public static int solo() { return 1; }
        }
        """;
    final String user =
        """
        package com.example.app;
        import static com.example.Util.go;
        import static com.example.Util.solo;
        class User {
          int u() { return go(1) + go("x").length() + solo(); }
        }
        """;
    final Path utilFile = TestCompiler.writeModuleSource(tmp, "com/example/Util.java", util);
    final Path userFile = TestCompiler.writeModuleSource(tmp, "com/example/app/User.java", user);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), utilFile, userFile);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(utilFile, util, "go(int", "goInt"))
        .hasMessageContaining("`go` cannot be renamed: User.java imports it statically");
    assertThat(Files.readString(userFile)).isEqualTo(user);

    renameAt(utilFile, util, "solo", "single");
    assertThat(Files.readString(userFile))
        .contains("import static com.example.Util.single;")
        .contains("+ single()");
  }

  @Test
  void rename_symbolDeclaredInGeneratedSource_refusesWithTheReasonAndEditsNothing()
      throws Exception {
    final Path generated =
        TestCompiler.writeAt(
            tmp.resolve(".lathe/module")
                .resolve(LatheLayout.generatedSourcesDir("classes"))
                .resolve("com/example/SampleBuilder.java"),
            "package com.example; class SampleBuilder { void name() {} }",
            1_000L);
    final String source =
        "package com.example; class Sample { void run(SampleBuilder b) { b.name(); } }";
    final Path file = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", source);
    // After the source, whose write declares the module without generated sources.
    TestCompiler.writeModuleParams(
        tmp,
        "module",
        tmp.resolve("module/src/main/java"),
        tmp.resolve("module/target/generated-sources/annotations"));
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), file, generated);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(file, source, "name", "label"))
        .hasMessageContaining("generated source");
    assertThat(Files.readString(file)).isEqualTo(source);
  }

  @Test
  void rename_nestedType_renamesTheDeclarationAndEveryUse() throws Exception {
    final NestedFixture fx = nestedFixture();
    engine = new LatheEngine(tmp);

    renameAt(fx.user(), fx.userSource(), "Inner", "Nested");

    assertThat(Files.readString(fx.outer())).contains("class Nested").doesNotContain("Inner");
    assertThat(Files.readString(fx.user())).contains("Outer.Nested a");
    assertThat(Files.readString(fx.other())).contains("Outer.Nested b");
  }

  @Test
  void rename_publicTypeUsedInDownstreamModule_movesFileAndMirrorsOnlyTheNewClass()
      throws Exception {
    final String apiSource =
        "package com.example.api;\npublic class Api { public int a() { return 1; } }\n";
    final Path api =
        TestCompiler.writeMirroredModule(
                tmp, "api", Map.of("com/example/api/Api.java", apiSource), List.of(), 1_000L)
            .get(0);
    final Path app =
        TestCompiler.writeMirroredModule(
                tmp,
                "app",
                Map.of(
                    "com/example/app/App.java",
                    "package com.example.app;\nimport com.example.api.Api;\n"
                        + "class App { int u() { return new Api().a(); } }\n"),
                List.of("api"),
                1_000L)
            .get(0);
    engine = new LatheEngine(tmp);

    final LatheRename rename = renameAt(api, apiSource, "Api {", "Renamed");

    final Path renamed = api.resolveSibling("Renamed.java");
    assertThat(api).doesNotExist();
    assertThat(Files.readString(renamed)).contains("public class Renamed");
    assertThat(Files.readString(app)).contains("new Renamed()").doesNotContain("Api()");
    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Renamed.java"))
        .anySatisfy(uri -> assertThat(uri).endsWith("App.java"));
    assertThat(rename.paths()).contains(renamed, app);
    assertThat(engine.notes(rename.paths()).notChecked()).isEmpty();

    // The commit swapped the mirror's class and compiled the dependent against it, in one go.
    final Path apiClasses = tmp.resolve(".lathe/api/classes/com/example/api");
    assertThat(apiClasses.resolve("Renamed.class")).exists();
    assertThat(apiClasses.resolve("Api.class")).doesNotExist();
    assertThat(TestCompiler.stampedMtimes(tmp.resolve(".lathe/app"), "classes"))
        .containsEntry(
            Path.of("com/example/app/App.java"), Files.getLastModifiedTime(app).toMillis());
    assertThat(tmp.resolve(".lathe/lathe.lock")).doesNotExist();
  }

  @Test
  void rename_moreFilesThanTheBulkThreshold_compilesEveryFileIntoTheMirror() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Callee.java", "package com.example; class Callee { void greet() {} }");
    for (int i = 0; i < 55; i++) {
      sources.put(
          "com/example/Caller%d.java".formatted(i),
          "package com.example; class Caller%d { void run(Callee c) { c.greet(); } }".formatted(i));
    }

    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    engine = new LatheEngine(tmp);

    renameAt(files.get(0), sources.get("com/example/Callee.java"), "greet", "welcome");

    // Every touched file is compiled and stamped before rename returns, not deferred to a sync.
    final Map<Path, Long> stamps =
        TestCompiler.stampedMtimes(tmp.resolve(".lathe/module"), "classes");
    for (final Path file : files) {
      assertThat(stamps)
          .containsEntry(
              tmp.resolve("module/src/main/java").relativize(file),
              Files.getLastModifiedTime(file).toMillis());
    }
  }
}
