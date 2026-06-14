package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.SignatureHelp;

final class ClassFileFixture implements AutoCloseable {

  private static final String MARKER = "§";

  private final Path srcDir;
  private final SourceAnalysisSession session;

  ClassFileFixture(final Path tmpDir) throws IOException {
    this.srcDir = Files.createDirectory(tmpDir.resolve("src"));
    final var classDir = Files.createDirectory(tmpDir.resolve("classes"));
    final var src =
        Files.writeString(
            srcDir.resolve("Greeter.java"),
            """
            public class Greeter {
                /**
                 * Creates a greeter.
                 * @param label the label
                 */
                public Greeter(String label) {}
                /**
                 * Creates a greeter with a limit.
                 * @param label the label
                 * @param max max count
                 */
                public Greeter(String label, int max) {}
                /**
                 * Greets the recipient.
                 * @param name the recipient
                 * @param count repetitions
                 */
                public void greet(String name, int count) {}
            }
            """);
    TestCompiler.compileToDir(classDir, src);
    this.session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));
  }

  SignatureHelp signatureHelpAt(final String rawSource) {
    final var source = rawSource.replace(MARKER, "");
    final int markerOffset = rawSource.indexOf(MARKER);
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final var pos = SourceLocator.offsetToPosition(source, markerOffset);
    final var request =
        new SourceFeatureRequest(
            TempSourceCompiler.TEST_URI, source, pos, List.of(srcDir), WorkspaceManifest.empty());
    return session.signatureHelp(request);
  }

  Path srcDir() {
    return srcDir;
  }

  SourceAnalysisSession session() {
    return session;
  }

  @Override
  public void close() {
    session.close();
  }
}
