package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ClassFileFixture implements AutoCloseable {

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
                public void greet(String name, int count) {}
            }
            """);
    TestCompiler.compileToDir(classDir, src);
    this.session = new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)));
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
