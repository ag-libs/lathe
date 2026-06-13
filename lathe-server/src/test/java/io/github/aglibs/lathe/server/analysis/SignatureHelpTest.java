package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.SignatureHelp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignatureHelpTest {

  private static final String URI = "file:///Test.java";
  private static final String MARKER = "§";

  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() {
    session = new SourceAnalysisSession(new TempSourceCompiler());
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  // --- Basic method invocations ---

  @Test
  void signatureHelp_cursorInFirstArg_showsActiveParam0() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target("§", 1); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures()).hasSize(1);
    assertThat(help.getActiveParameter()).isEqualTo(0);
    assertThat(help.getSignatures().getFirst().getLabel()).contains("String name");
    assertThat(help.getSignatures().getFirst().getLabel()).contains("int count");
  }

  @Test
  void signatureHelp_cursorInSecondArg_showsActiveParam1() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target("x", §1); }
        }
        """;
    assertThat(signatureHelpAt(source).getActiveParameter()).isEqualTo(1);
  }

  @Test
  void signatureHelp_cursorInThirdArg_showsActiveParam2() {
    final var source =
        """
        class Test {
          void target(String a, String b, String c) {}
          void caller() { target("x", "y", "§"); }
        }
        """;
    assertThat(signatureHelpAt(source).getActiveParameter()).isEqualTo(2);
  }

  @Test
  void signatureHelp_zeroParamMethod_returnsEmptyParamList() {
    final var source =
        """
        class Test {
          void target() {}
          void caller() { target(§); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().getFirst().getParameters()).isEmpty();
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  @Test
  void signatureHelp_nestedCall_resolvesInnermostInvocation() {
    final var source =
        """
        class Test {
          String inner(int x) { return ""; }
          void outer(String s) {}
          void caller() { outer(inner(§42)); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().getFirst().getLabel()).contains("inner");
    assertThat(help.getSignatures().getFirst().getLabel()).contains("int x");
  }

  @Test
  void signatureHelp_nestedCallCommaInOuter_countsCorrectly() {
    final var source =
        """
        class Test {
          String inner(int x) { return ""; }
          void outer(String a, String b) {}
          void caller() { outer("x", inner(§42)); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().getFirst().getLabel()).contains("inner");
    assertThat(help.getActiveParameter()).isEqualTo(0);
  }

  // --- Constructor calls ---

  @Test
  void signatureHelp_constructorCall_showsParams() {
    final var source =
        """
        class Point {
          int x, y;
          Point(int x, int y) { this.x = x; this.y = y; }
        }
        class Test {
          void caller() { new Point(1, §2); }
        }
        """;
    final var help = signatureHelpAt(source);

    assertThat(help).isNotNull();
    assertThat(help.getSignatures().getFirst().getLabel()).contains("Point");
    assertThat(help.getActiveParameter()).isEqualTo(1);
  }

  // --- Outside any call ---

  @Test
  void signatureHelp_cursorOutsideCall_returnsNull() {
    final var source =
        """
        class Test {
          void caller() { §int x = 1; }
        }
        """;
    assertThat(signatureHelpAt(source)).isNull();
  }

  // --- Parameter label offsets ---

  @Test
  void signatureHelp_paramLabelOffsets_pointIntoSignatureLabel() {
    final var source =
        """
        class Test {
          void target(String name, int count) {}
          void caller() { target(§"x", 1); }
        }
        """;
    final var help = signatureHelpAt(source);
    final var sig = help.getSignatures().getFirst();
    final var label = sig.getLabel();
    final var params = sig.getParameters();

    assertThat(params).hasSize(2);
    final var p0 = params.get(0).getLabel().getRight();
    assertThat(label.substring(p0.getFirst(), p0.getSecond())).isEqualTo("String name");
    final var p1 = params.get(1).getLabel().getRight();
    assertThat(label.substring(p1.getFirst(), p1.getSecond())).isEqualTo("int count");
  }

  // --- class-file dependency ---

  @Test
  void signatureHelp_classFileDependency_showsSourceParameterNames(@TempDir final Path tmpDir)
      throws Exception {
    final var srcDir = Files.createDirectory(tmpDir.resolve("src"));
    final var classDir = Files.createDirectory(tmpDir.resolve("classes"));

    final var libCompiler = ToolProvider.getSystemJavaCompiler();
    try (final var libFm = libCompiler.getStandardFileManager(null, null, null)) {
      final var src =
          Files.writeString(
              srcDir.resolve("Greeter.java"),
              """
              public class Greeter {
                  public void greet(String name, int count) {}
              }
              """);
      libFm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
      libCompiler
          .getTask(null, libFm, null, List.of("-proc:none"), null, libFm.getJavaFileObjects(src))
          .call();
    }

    try (final var s = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final var rawSource =
          """
          class Test {
              void caller() { new Greeter().greet(§"x", 1); }
          }
          """;
      final var source = rawSource.replace(MARKER, "");
      final int markerOffset = rawSource.indexOf(MARKER);
      s.compile(URI, source, 1, CompileMode.OPEN);
      final var pos = SourceLocator.offsetToPosition(source, markerOffset);
      final var request =
          new SourceFeatureRequest(URI, source, pos, List.of(srcDir), WorkspaceManifest.empty());
      final var help = s.signatureHelp(request);

      assertThat(help).isNotNull();
      assertThat(help.getSignatures().getFirst().getLabel()).contains("String name", "int count");
    }
  }

  // --- helpers ---

  private SignatureHelp signatureHelpAt(final String rawSource) {
    final var source = rawSource.replace(MARKER, "");
    final int markerOffset = rawSource.indexOf(MARKER);
    session.compile(URI, source, 1, CompileMode.OPEN);
    final var pos = SourceLocator.offsetToPosition(source, markerOffset);
    final var request =
        new SourceFeatureRequest(URI, source, pos, List.of(), WorkspaceManifest.empty());
    return session.signatureHelp(request);
  }
}
