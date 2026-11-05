package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.analysis.SourceLocator.offsetToPosition;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.server.analysis.testing.TestJavac;
import io.github.aglibs.lathe.server.engine.LatheRename;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

// The engine over a temp workspace, with the fixtures several of its tool families share.
abstract class LatheEngineTestBase {
  @TempDir protected Path tmp;
  protected LatheEngine engine;

  @AfterEach
  void close() {
    if (engine != null) {
      engine.close();
    }
  }

  // Renames the symbol at the first occurrence of `at` in the file's source.
  protected LatheRename renameAt(
      final Path file, final String source, final String at, final String newName) {
    final var pos = offsetToPosition(source, source.indexOf(at));
    return engine.rename(file, pos.getLine(), pos.getCharacter(), newName);
  }

  // Record a compile stamp per source so a freshly started engine sees the module as already
  // synced; a later writeNewer() edit is then the only thing that reads as changed.
  protected void stampModule(final Path... sources) throws Exception {
    final Path sourceRoot = tmp.resolve("module/src/main/java");
    CompiledStamps.record(
        tmp.resolve(".lathe/module"),
        "classes",
        Arrays.stream(sources)
            .collect(
                Collectors.toUnmodifiableMap(
                    sourceRoot::relativize,
                    source -> IOUtil.unchecked(() -> TestCompiler.buildStamp(source)))));
  }

  protected void writeNewer(final Path source, final String content) throws Exception {
    Files.writeString(source, content);
    final long stamp =
        TestCompiler.stampedMtimes(tmp.resolve(".lathe/module"), "classes").values().stream()
            .max(Long::compareTo)
            .orElse(0L);
    Files.setLastModifiedTime(source, FileTime.fromMillis(stamp + 10_000));
  }

  // Callee.greet() called from Caller.run(), compiled into .lathe so cross-file resolution works.
  protected GreetFixture greetFixture() throws Exception {
    final String calleeSource =
        """
        package com.example;
        class Callee {
          void greet() {}
        }
        """;
    final Path callee =
        TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", calleeSource);
    final String callerSource =
        "package com.example; class Caller { void run(Callee c) { c.greet(); } }";
    final Path caller =
        TestCompiler.writeModuleSource(tmp, "com/example/Caller.java", callerSource);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    return new GreetFixture(callee, caller, calleeSource, callerSource);
  }

  // Public Callee.greet() called from Caller in another package, so only the reactor-wide
  // candidate index (no same-package scan) leads a search from Callee to Caller.
  protected GreetFixture crossPackageGreetFixture() throws Exception {
    final String calleeSource =
        "package com.example;\npublic class Callee {\n  public void greet() {}\n}\n";
    final Path callee =
        TestCompiler.writeModuleSource(tmp, "com/example/Callee.java", calleeSource);
    final String callerSource =
        "package com.other;\nclass Caller { void run(com.example.Callee c) { c.greet(); } }\n";
    final Path caller = TestCompiler.writeModuleSource(tmp, "com/other/Caller.java", callerSource);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), callee, caller);
    return new GreetFixture(callee, caller, calleeSource, callerSource);
  }

  // Outer declares a nested class and a nested interface; User uses the class from its package,
  // Other from another package through an import of Outer only, and implements the interface.
  protected NestedFixture nestedFixture() throws Exception {
    final String outerSource =
        """
        package com.example;
        public class Outer {
          public static class Inner {}
          public interface Listener {}
        }
        """;
    final Path outer = TestCompiler.writeModuleSource(tmp, "com/example/Outer.java", outerSource);
    final String userSource = "package com.example; class User { Outer.Inner a; }";
    final Path user = TestCompiler.writeModuleSource(tmp, "com/example/User.java", userSource);
    final Path other =
        TestCompiler.writeModuleSource(
            tmp,
            "com/other/Other.java",
            """
            package com.other;
            import com.example.Outer;
            class Other implements Outer.Listener { Outer.Inner b; }
            """);
    TestJavac.compileToDir(tmp.resolve(".lathe/module/classes"), outer, user, other);
    return new NestedFixture(outer, user, other, outerSource, userSource);
  }

  protected record NestedFixture(
      Path outer, Path user, Path other, String outerSource, String userSource) {}

  protected record GreetFixture(
      Path callee, Path caller, String calleeSource, String callerSource) {}
}
