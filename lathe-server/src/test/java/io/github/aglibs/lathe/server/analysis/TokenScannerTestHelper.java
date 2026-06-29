package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class TokenScannerTestHelper {

  private TokenScannerTestHelper() {}

  static List<SemanticToken> scanFile(final Path tmp, final String filename, final String source)
      throws IOException {
    final Path src = tmp.resolve(filename);
    Files.writeString(src, source);
    try (var compiled = TestCompiler.parse(src)) {
      return TokenScanner.scan(compiled.trees(), compiled.cu());
    }
  }

  static SemanticToken tokenAt(
      final List<SemanticToken> tokens, final int line, final int character) {
    return tokens.stream()
        .filter(
            t ->
                t.line() == line
                    && t.character() <= character
                    && character < t.character() + t.length())
        .findFirst()
        .orElse(null);
  }
}
