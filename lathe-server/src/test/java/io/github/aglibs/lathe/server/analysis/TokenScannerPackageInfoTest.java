package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Source: "package com.example;\n"
//  line 0 col 8  "com.example" (package name, length 11) → namespace token
class TokenScannerPackageInfoTest {

  @TempDir Path tmp;

  @Test
  void semanticTokens_packageInfo_packageName_isNamespace() throws IOException {
    final List<SemanticToken> tokens =
        TokenScannerTestHelper.scanFile(tmp, "package-info.java", "package com.example;\n");

    final var tok = TokenScannerTestHelper.tokenAt(tokens, 0, 8);
    assertThat(tok).as("package name token").isNotNull();
    assertThat(tok.type()).isEqualTo("namespace");
    assertThat(tok.length()).isEqualTo(11);
    assertThat(tok.modifiers()).isEmpty();
  }

  @Test
  void semanticTokens_regularFile_packageDecl_noNamespaceToken() throws IOException {
    final List<SemanticToken> tokens =
        TokenScannerTestHelper.scanFile(
            tmp, "package-info.java", "package com.example;\nclass Foo {}\n");

    assertThat(tokens).noneMatch(t -> t.type().equals("namespace"));
  }
}
