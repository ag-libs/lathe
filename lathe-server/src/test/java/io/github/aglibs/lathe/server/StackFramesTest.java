package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.LatheUri;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackFramesTest {

  @TempDir private Path tmp;

  @Test
  void parse_frameShapes_classFileLineAndTheFileLineSpan() {
    final String line = "\tat com.example.FooTest.fails(FooTest.java:42)";

    assertThat(StackFrames.parse(line))
        .hasValue(
            new StackFrames.Frame(
                "com.example.FooTest",
                "FooTest.java",
                42,
                line.indexOf("FooTest.java"),
                line.indexOf(")")));
    assertThat(StackFrames.parse("\tat com.example.Outer$Inner.fails(Outer.java:10)"))
        .hasValueSatisfying(
            frame -> assertThat(frame.binaryName()).isEqualTo("com.example.Outer$Inner"));
    assertThat(StackFrames.parse("\tat com.example.FooTest.lambda$test$0(FooTest.java:15)"))
        .hasValueSatisfying(
            frame -> assertThat(frame.binaryName()).isEqualTo("com.example.FooTest"));
    assertThat(StackFrames.parse("\tat com.example.Foo.<init>(Foo.java:3)"))
        .hasValueSatisfying(frame -> assertThat(frame.line()).isEqualTo(3));
    assertThat(StackFrames.parse("\tat FooTest.fails(FooTest.java:5)"))
        .hasValueSatisfying(frame -> assertThat(frame.packageName()).isEmpty());
    assertThat(StackFrames.parse("\tat java.base/java.lang.Thread.run(Thread.java:840)"))
        .hasValueSatisfying(frame -> assertThat(frame.binaryName()).isEqualTo("java.lang.Thread"));
    assertThat(
            StackFrames.parse(
                "\tat com.example.app@1.0-SNAPSHOT/com.example.app.rules.WrapperTest.fails(WrapperTest.java:68)"))
        .hasValueSatisfying(
            frame -> {
              assertThat(frame.binaryName()).isEqualTo("com.example.app.rules.WrapperTest");
              assertThat(frame.packageName()).isEqualTo("com.example.app.rules");
            });
    assertThat(
            StackFrames.parse("\tat com.example.FooTest.fails(FooTest.java:42) ~[test-classes/:?]"))
        .hasValueSatisfying(frame -> assertThat(frame.line()).isEqualTo(42));
  }

  @Test
  void parse_notAJavaFrame_empty() {
    assertThat(StackFrames.parse("Caused by: java.lang.AssertionError: boom")).isEmpty();
    assertThat(StackFrames.parse("\t... 3 more")).isEmpty();
    assertThat(StackFrames.parse("")).isEmpty();
    assertThat(StackFrames.parse("expected: <1> but was: <2>")).isEmpty();
    assertThat(StackFrames.parse("\tat jdk.internal.Native.call(Native Method)")).isEmpty();
  }

  // The exact source of each frame's class, in whichever root has it; a frame without one, or a
  // line that is no frame, has no entry.
  @Test
  void resolve_framesAcrossRoots_eachToItsClassSourceOrNothing() throws IOException {
    final Path main = tmp.resolve("app/src/main/java");
    final Path jdk = tmp.resolve("jdk-src/java.base");
    final Path outer = write(main, "com/example/Outer.java");
    final Path helper = write(main, "com/example/Helpers.java");
    final Path thread = write(jdk, "java/lang/Thread.java");
    final List<String> lines =
        List.of(
            "java.lang.IllegalStateException: boom",
            "\tat com.example.Outer$Inner.fails(Outer.java:10)",
            "\tat com.example.Hidden.call(Helpers.java:7)",
            "\tat com.example.Gone.call(Gone.java:3)",
            "\tat java.base/java.lang.Thread.run(Thread.java:840)",
            "\tat com.example.Outer.run(Outer.java:20)");

    final List<StackFrames.Resolved> resolved = StackFrames.resolve(lines, List.of(main, jdk));

    assertThat(resolved)
        .extracting(
            StackFrames.Resolved::index, StackFrames.Resolved::uri, StackFrames.Resolved::line)
        .containsExactly(
            tuple(1, LatheUri.fromPath(outer), 10),
            tuple(2, LatheUri.fromPath(helper), 7),
            tuple(4, LatheUri.fromPath(thread), 840),
            tuple(5, LatheUri.fromPath(outer), 20));
    assertThat(resolved.get(0).startColumn()).isEqualTo(lines.get(1).indexOf("Outer.java:10"));
    assertThat(resolved.get(0).endColumn()).isEqualTo(lines.get(1).indexOf(")"));
  }

  private static Path write(final Path root, final String relative) throws IOException {
    final Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, "class X {}");
  }
}
