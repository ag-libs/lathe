package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheModuleDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

// MC-90: a file whose package is not its directory compiles, but Lathe finds a type's file from
// its package. Its diagnostics name the mismatch, verify_change included; a rename of a member in
// it refuses saying why, while a local or a parameter still renames; and definition in it answers
// the file itself, never the compiler's temp copy or a same-named file its package names.
class PackageDirectoryEngineTest extends LatheEngineTestBase {

  private static final String WRONG =
      """
      package com.example.other;
      public class Wrong<T> {
        int count;
        private int hidden;
        T item;
        int twice(int factor) { int local = count * factor + hidden; return local + count; }
      }
      """;
  private static final String RIGHT =
      """
      package com.example;
      public class Right {
        int count;
        int twice() { return count + count; }
      }
      """;

  @Test
  void diagnostics_packageNotItsDirectory_errorNamingBoth_matchingFileClean() throws Exception {
    final Path wrong = TestCompiler.writeModuleSource(tmp, "com/example/Wrong.java", WRONG);
    final Path right = TestCompiler.writeModuleSource(tmp, "com/example/Right.java", RIGHT);
    compileIntoMirror(wrong, right);
    engine = new LatheEngine(tmp);

    final List<LatheDiagnostic> diagnostics = engine.diagnostics(wrong).value();

    assertThat(diagnostics)
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(LatheDiagnostic.Severity.ERROR);
              assertThat(diagnostic.message())
                  .contains("com.example.other")
                  .contains("package com.example");
            });
    assertThat(engine.diagnostics(right).value()).isEmpty();
  }

  @Test
  void verifyChange_fileWhosePackageIsNotItsDirectory_reportsTheMismatch() throws Exception {
    final Path wrong = TestCompiler.writeModuleSource(tmp, "com/example/Wrong.java", WRONG);
    compileIntoMirror(wrong);
    engine = new LatheEngine(tmp);

    assertThat(engine.verifyChange(List.of(wrong)).perModule())
        .flatExtracting(LatheModuleDiagnostics::diagnostics)
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(LatheDiagnostic.Severity.ERROR);
              assertThat(diagnostic.message()).contains("com.example.other");
            });
  }

  // What a rename searches only in the file (a local, a parameter, a type parameter, a private
  // member) is renamed there, as before the check; a member it finds by its package is refused
  // with the reason.
  @Test
  void rename_inFileWhosePackageIsNotItsDirectory_fileScopedRenamedMemberRefused()
      throws Exception {
    final Path wrong = TestCompiler.writeModuleSource(tmp, "com/example/Wrong.java", WRONG);
    compileIntoMirror(wrong);
    engine = new LatheEngine(tmp);

    assertThat(renameAt(wrong, WRONG, "local =", "result").totalEdits()).isEqualTo(2);
    assertThat(renameAt(wrong, Files.readString(wrong), "factor)", "scale").totalEdits())
        .isEqualTo(2);
    assertThat(renameAt(wrong, Files.readString(wrong), "T>", "E").totalEdits()).isEqualTo(2);
    assertThat(renameAt(wrong, Files.readString(wrong), "hidden;", "secret").totalEdits())
        .isEqualTo(2);
    assertThatThrownBy(() -> renameAt(wrong, Files.readString(wrong), "count;", "total"))
        .hasMessageContaining("`count` cannot be renamed")
        .hasMessageContaining("com.example.other");
  }

  // The package names a file too, another copy of the class: definition still answers the file
  // asked about.
  @Test
  void definition_inFileWhosePackageIsNotItsDirectory_answersTheFileItself() throws Exception {
    final Path wrong = TestCompiler.writeModuleSource(tmp, "com/example/Wrong.java", WRONG);
    TestCompiler.writeModuleSource(tmp, "com/example/other/Wrong.java", WRONG);
    compileIntoMirror(wrong);
    engine = new LatheEngine(tmp);
    final var use = positionOf(WRONG, "count *");

    final List<LatheLocation> targets =
        engine.definition(wrong, use.getLine(), use.getCharacter()).value().targets();

    assertThat(targets)
        .singleElement()
        .satisfies(target -> assertThat(LatheUri.toPath(target.uri())).isEqualTo(wrong));
  }
}
