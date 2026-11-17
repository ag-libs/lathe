package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.server.engine.LatheDiagnostic;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

// MC-90: a file whose package is not its directory compiles, but Lathe finds a type's file from
// its package. Its diagnostics name the mismatch, a rename in it refuses saying why, and
// definition in it answers the file itself, never the compiler's temp copy.
class PackageDirectoryEngineTest extends LatheEngineTestBase {

  private static final String WRONG =
      """
      package com.example.other;
      public class Wrong {
        int count;
        int twice() { return count + count; }
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
  void rename_inFileWhosePackageIsNotItsDirectory_refusesSayingWhy() throws Exception {
    final Path wrong = TestCompiler.writeModuleSource(tmp, "com/example/Wrong.java", WRONG);
    compileIntoMirror(wrong);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> renameAt(wrong, WRONG, "count;", "total"))
        .hasMessageContaining("Nothing in this file can be renamed")
        .hasMessageContaining("com.example.other");
  }

  @Test
  void definition_inFileWhosePackageIsNotItsDirectory_answersTheFileItself() throws Exception {
    final Path wrong = TestCompiler.writeModuleSource(tmp, "com/example/Wrong.java", WRONG);
    compileIntoMirror(wrong);
    engine = new LatheEngine(tmp);
    final var use = positionOf(WRONG, "count + count");

    final List<LatheLocation> targets =
        engine.definition(wrong, use.getLine(), use.getCharacter()).value().targets();

    assertThat(targets)
        .singleElement()
        .satisfies(target -> assertThat(LatheUri.toPath(target.uri())).isEqualTo(wrong));
  }
}
