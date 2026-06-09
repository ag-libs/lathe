package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.StandardLocation;
import org.eclipse.lsp4j.CodeActionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeActionTest {

  @TempDir private Path tmp;

  private WorkspaceTypeIndex typeIndex;
  private TempSourceCompiler compiler;
  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() throws IOException {
    typeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("index.json"),
            new TypeIndexEntry("ArrayList", "java.util.ArrayList", "java.util", TypeKind.CLASS));
    compiler = new TempSourceCompiler();
    session = new SourceAnalysisSession(compiler);
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  @Test
  void codeAction_unresolvedType_returnsImportQuickFix() {
    final var source =
        """
        package com.example;

        class Test {
          ArrayList list;
        }
        """;

    final var uri = "file:///Test.java";
    final var diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions = session.codeAction(uri, source, 1, new CodeActionContext(diags), typeIndex);

    assertThat(actions).hasSize(1);

    final var action = actions.getFirst().getRight();
    assertThat(action.getTitle()).isEqualTo("Import 'java.util.ArrayList'");
    assertThat(action.getKind()).isEqualTo("quickfix");
    assertThat(action.getDiagnostics()).hasSize(1);

    final var edits = action.getEdit().getChanges().get(uri);
    assertThat(edits).hasSize(1);
    assertThat(edits.getFirst().getNewText()).isEqualTo("import java.util.ArrayList;\n");
    assertThat(edits.getFirst().getRange().getStart().getLine()).isEqualTo(1);
  }

  @Test
  void codeAction_alreadyImportedType_isSkipped() {
    final var uri = "file:///Test.java";
    // Compile without the import to get a real diagnostic with data set
    final var sourceWithoutImport =
        """
        package com.example;
        class Test { ArrayList list; }
        """;
    final var diags = session.compile(uri, sourceWithoutImport, 1, CompileMode.OPEN);

    // Code action on a version that already has the import — expect no fixes
    final var sourceWithImport =
        """
        package com.example;
        import java.util.ArrayList;
        class Test { ArrayList list; }
        """;
    final var actions =
        session.codeAction(uri, sourceWithImport, 2, new CodeActionContext(diags), typeIndex);
    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_noDiagnostic_returnsEmpty() {
    final var actions =
        session.codeAction("file:///Test.java", "", 1, new CodeActionContext(List.of()), typeIndex);
    assertThat(actions).isEmpty();
  }

  @Test
  void codeAction_inaccessibleType_isSkipped() throws IOException {
    final var classDir = tmp.resolve("classes");
    final var ppFile = tmp.resolve("PackagePrivateClass.java");
    Files.writeString(
        ppFile,
        """
        package com.other;
        class PackagePrivateClass {}
        """);
    final var pubFile = tmp.resolve("PublicClass.java");
    Files.writeString(
        pubFile,
        """
        package com.other;
        public class PublicClass {}
        """);
    TestCompiler.compileToDir(classDir, ppFile, pubFile);
    compiler.fileManager().setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(classDir));

    final var customTypeIndex =
        TempSourceCompiler.typeIndex(
            tmp.resolve("custom_index.json"),
            new TypeIndexEntry(
                "PackagePrivateClass",
                "com.other.PackagePrivateClass",
                "com.other",
                TypeKind.CLASS),
            new TypeIndexEntry(
                "PublicClass", "com.other.PublicClass", "com.other", TypeKind.CLASS));

    final var source =
        """
        package com.example;

        class Test {
          PackagePrivateClass pp;
          PublicClass pub;
        }
        """;

    final var uri = "file:///Test.java";
    final var diags = session.compile(uri, source, 1, CompileMode.OPEN);
    final var actions =
        session.codeAction(uri, source, 1, new CodeActionContext(diags), customTypeIndex);

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getRight().getTitle())
        .isEqualTo("Import 'com.other.PublicClass'");
  }
}
