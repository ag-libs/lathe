package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.MissingImportsResult.MissingImport;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import io.github.aglibs.lathe.server.engine.LatheFormat;
import io.github.aglibs.lathe.server.engine.LatheImports;
import io.github.aglibs.lathe.server.format.FormatterFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

// The file-editing tools: format_file and fix_imports.
class LatheEngineEditTest extends LatheEngineTestBase {
  @Test
  @EnabledForJreRange(minVersion = FormatterFixtures.GOOGLE_JAVA_FORMAT_MIN_JDK)
  void format_pinnedFormatter_rewritesTheChangedLinesOnce_thenAlreadyFormatted() throws Exception {
    pinGoogleJavaFormat();
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Sample.java",
            "package com.example;\n\nclass Sample {\n  int n = 1;\n\n  int  twice( ) {return n*2;}\n}\n");
    engine = new LatheEngine(tmp);

    final Optional<LatheFormat> formatted = engine.format(source);

    assertThat(formatted)
        .hasValueSatisfying(
            format -> {
              assertThat(format.path()).isEqualTo(FileUtil.slashed(tmp.relativize(source)));
              assertThat(format.firstLine()).isEqualTo(5);
              assertThat(format.lastLine()).isEqualTo(7);
            });
    assertThat(Files.readString(source)).contains("  int twice() {\n    return n * 2;\n  }\n");
    assertThat(engine.format(source)).isEmpty();
  }

  @Test
  @EnabledForJreRange(minVersion = FormatterFixtures.GOOGLE_JAVA_FORMAT_MIN_JDK)
  void format_fileThatDoesNotParse_refusesWithTheReasonAndLeavesItAlone() throws Exception {
    pinGoogleJavaFormat();
    final String broken = "package com.example;\nclass Sample { int n = ; }\n";
    final Path source = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", broken);
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.format(source))
        .hasMessageStartingWith("Could not format Sample.java");
    assertThat(Files.readString(source)).isEqualTo(broken);
  }

  // A sync resolves a pinned formatter's jars; it cannot pin one the project does not have.
  @Test
  void format_noFormatter_namesTheStyleFile_unresolvedOne_namesTheSync() throws Exception {
    final Path source =
        TestCompiler.writeModuleSource(
            tmp, "com/example/Sample.java", "package com.example; class Sample {}");
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.format(source))
        .hasMessageStartingWith("No formatter for this workspace")
        .hasMessageContaining(LatheLayout.STYLE_SHARED_FILE)
        .hasMessageNotContaining(LatheLayout.SYNC_SCRIPT_COMMAND);

    engine.close();
    Files.writeString(
        tmp.resolve(LatheLayout.STYLE_SHARED_FILE), "{\"formatter\": {\"engine\": \"google\"}}");
    engine = new LatheEngine(tmp);

    assertThatThrownBy(() -> engine.format(source))
        .hasMessage(
            "The project's formatter is not resolved yet: run `%s`."
                .formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void fixImports_mixedNames_addsTheUnambiguousOnes_leavesTheRestToTheAgent() throws Exception {
    TestCompiler.writeModuleSource(
        tmp, "com/example/util/Helper.java", "package com.example.util; public class Helper {}");
    TestCompiler.writeModuleSource(
        tmp, "com/example/a/Widget.java", "package com.example.a; public class Widget {}");
    TestCompiler.writeModuleSource(
        tmp, "com/example/b/Widget.java", "package com.example.b; public class Widget {}");
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/app/App.java",
            "package com.example.app;\nclass App {\n  Helper h;\n  Widget w;\n  Fooo f;\n}\n");
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.path()).isEqualTo(FileUtil.slashed(tmp.relativize(source)));
    assertThat(imports.added()).containsExactly("com.example.util.Helper");
    assertThat(imports.unresolved())
        .extracting(MissingImport::name, MissingImport::candidates)
        .containsExactlyInAnyOrder(
            tuple("Widget", List.of("com.example.a.Widget", "com.example.b.Widget")),
            tuple("Fooo", List.of()));
    assertThat(Files.readString(source))
        .startsWith("package com.example.app;\n\nimport com.example.util.Helper;\nclass App {");

    final LatheImports again = engine.fixImports(source);

    assertThat(again.added()).as("nothing left to add").isEmpty();
    assertThat(again.unresolved()).extracting(MissingImport::name).contains("Widget", "Fooo");
  }

  @Test
  void fixImports_nestedType_addsItsCanonicalImport() throws Exception {
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/util/Registry.java",
        "package com.example.util; public class Registry { public static class Slot {} }");
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/app/App.java",
            "package com.example.app;\nclass App {\n  Slot s;\n}\n");
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.added()).containsExactly("com.example.util.Registry.Slot");
    assertThat(imports.unresolved()).isEmpty();
  }

  // A call that another file of the same scope imports statically gets that static import, learned
  // from the workspace's own import lines with nothing configured.
  @Test
  void fixImports_memberImportedStaticallyElsewhere_addsTheStaticImport() throws Exception {
    TestCompiler.writeModuleSource(
        tmp,
        "com/example/Checked.java",
        "package com.example;\nimport static java.util.Objects.requireNonNull;\n"
            + "class Checked { Checked(Object o) { requireNonNull(o); } }\n");
    final Path source =
        TestCompiler.writeModuleSource(
            tmp,
            "com/example/Unchecked.java",
            "package com.example;\nclass Unchecked { Unchecked(Object o) { requireNonNull(o); } }\n");
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.added()).containsExactly("static java.util.Objects.requireNonNull");
    assertThat(imports.unresolved()).isEmpty();
    assertThat(Files.readString(source))
        .startsWith(
            "package com.example;\n\nimport static java.util.Objects.requireNonNull;\n"
                + "class Unchecked {");
  }

  @Test
  void fixImports_nothingMissing_writesNothing() throws Exception {
    final String clean =
        "package com.example;\nimport java.util.List;\nclass Sample { List<String> s; }\n";
    final Path source = TestCompiler.writeModuleSource(tmp, "com/example/Sample.java", clean);
    engine = new LatheEngine(tmp);

    final LatheImports imports = engine.fixImports(source);

    assertThat(imports.added()).isEmpty();
    assertThat(imports.unresolved()).isEmpty();
    assertThat(Files.readString(source)).isEqualTo(clean);
  }

  // The style file lathe:sync writes for a project whose Spotless step pins google-java-format.
  private void pinGoogleJavaFormat() throws Exception {
    Json.write(
        new WorkspaceStyleData(
            new FormatterSpec(
                LatheFlags.FORMATTER_GOOGLE,
                List.of(),
                "",
                Map.of(),
                FormatterFixtures.googleJavaFormatClasspath()),
            null),
        tmp.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE));
  }
}
