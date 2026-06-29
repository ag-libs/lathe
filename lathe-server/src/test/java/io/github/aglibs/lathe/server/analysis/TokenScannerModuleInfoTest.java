package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Source used in module-info tests (0-based lines):
//  0: module a.b {
//  1:   requires java.base;
//  2:   exports a.c;
//  3:   opens a.d;
//  4: }
//
// namespace tokens expected:
//  line 0 col  7  "a.b"       (module name,       length 3)
//  line 1 col 11  "java.base" (requires directive, length 9)
//  line 2 col 10  "a.c"       (exports directive,  length 3)
//  line 3 col  8  "a.d"       (opens directive,    length 3)
class TokenScannerModuleInfoTest {

  @TempDir Path tmp;

  private static final String SOURCE =
      """
      module a.b {
        requires java.base;
        exports a.c;
        opens a.d;
      }
      """;

  @Test
  void semanticTokens_moduleInfo_moduleName_isNamespace() throws IOException {
    final List<SemanticToken> tokens = scan();

    final var tok = tokenAt(tokens, 0, 7);
    assertThat(tok).as("module name token").isNotNull();
    assertThat(tok.type()).isEqualTo("namespace");
    assertThat(tok.length()).isEqualTo(3);
    assertThat(tok.modifiers()).isEmpty();
  }

  @Test
  void semanticTokens_moduleInfo_requiresModuleName_isNamespace() throws IOException {
    final List<SemanticToken> tokens = scan();

    final var tok = tokenAt(tokens, 1, 11);
    assertThat(tok).as("requires module name token").isNotNull();
    assertThat(tok.type()).isEqualTo("namespace");
    assertThat(tok.length()).isEqualTo(9);
  }

  @Test
  void semanticTokens_moduleInfo_exportsPackageName_isNamespace() throws IOException {
    final List<SemanticToken> tokens = scan();

    final var tok = tokenAt(tokens, 2, 10);
    assertThat(tok).as("exports package name token").isNotNull();
    assertThat(tok.type()).isEqualTo("namespace");
    assertThat(tok.length()).isEqualTo(3);
  }

  @Test
  void semanticTokens_moduleInfo_opensPackageName_isNamespace() throws IOException {
    final List<SemanticToken> tokens = scan();

    final var tok = tokenAt(tokens, 3, 8);
    assertThat(tok).as("opens package name token").isNotNull();
    assertThat(tok.type()).isEqualTo("namespace");
    assertThat(tok.length()).isEqualTo(3);
  }

  private List<SemanticToken> scan() throws IOException {
    final Path src = tmp.resolve("module-info.java");
    Files.writeString(src, SOURCE);
    try (var compiled = TestCompiler.parse(src)) {
      return TokenScanner.scan(compiled.trees(), compiled.cu());
    }
  }

  private static SemanticToken tokenAt(
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
