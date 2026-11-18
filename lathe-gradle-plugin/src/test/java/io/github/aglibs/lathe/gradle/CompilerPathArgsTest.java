package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilerPathArgsTest {

  @TempDir Path project;

  private String at(final String entry) {
    return project.resolve(entry).toString();
  }

  // Every form javac takes: the option and its value apart, or joined by '=', several entries in
  // one value; the rest of the arguments stay as they were, in order.
  @Test
  void extract_eachPathOptionForm_movesItsEntriesAndKeepsTheRest() {
    final var extracted =
        CompilerPathArgs.extract(
            List.of(
                "--release",
                "25",
                "--module-path",
                String.join(File.pathSeparator, at("core.jar"), at("codecs.jar")),
                "-p",
                at("p.jar"),
                "--module-path=%s".formatted(at("eq.jar")),
                "-Xlint:cast",
                "--class-path",
                at("a.jar"),
                "-classpath",
                at("b.jar"),
                "-cp",
                String.join(File.pathSeparator, at("c.jar"), at("d.jar")),
                "--class-path=%s".formatted(at("e.jar")),
                "--module-version",
                "12.0.0"));

    assertThat(extracted.modulepath())
        .containsExactly(at("core.jar"), at("codecs.jar"), at("p.jar"), at("eq.jar"));
    assertThat(extracted.classpath())
        .containsExactly(at("a.jar"), at("b.jar"), at("c.jar"), at("d.jar"), at("e.jar"));
    assertThat(extracted.otherArgs())
        .containsExactly("--release", "25", "-Xlint:cast", "--module-version", "12.0.0");
  }

  // Arguments without a path option come back unchanged, and an option with no value after it is
  // not one to move.
  @Test
  void extract_noPathOptionOrNoValue_leavesTheArgumentsAsTheyAre() {
    final List<String> plain = List.of("--release", "17", "-parameters", "-Xlint:all");

    assertThat(CompilerPathArgs.extract(plain).otherArgs()).isEqualTo(plain);
    assertThat(CompilerPathArgs.extract(plain).classpath()).isEmpty();
    assertThat(CompilerPathArgs.extract(plain).modulepath()).isEmpty();
    assertThat(CompilerPathArgs.extract(List.of("-parameters", "--module-path")).otherArgs())
        .containsExactly("-parameters", "--module-path");
  }

  // An entry named through `..` is normalized, so it names the reactor jar the manifest records.
  @Test
  void extract_entryThroughParentSegment_normalized() {
    assertThat(
            CompilerPathArgs.extract(List.of("--module-path", at("app/../lib/build/libs/lib.jar")))
                .modulepath())
        .containsExactly(at("lib/build/libs/lib.jar"));
  }
}
