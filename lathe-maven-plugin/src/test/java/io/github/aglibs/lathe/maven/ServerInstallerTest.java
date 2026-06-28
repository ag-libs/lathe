package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerInstallerTest {

  @TempDir Path tmp;

  @Test
  void installNeovimBundle_validBundle_extractsRuntime() throws IOException {
    final Path zip =
        ZipFixture.create(
            tmp.resolve("lathe-neovim.zip"),
            Map.of(
                "lua/lathe.lua", "return {}".getBytes(),
                "lua/lathe/indent.lua", "return {}".getBytes(),
                "ftplugin/java.lua", "vim.bo.shiftwidth = 2".getBytes(),
                "after/indent/java.lua", "vim.bo.indentexpr = ''".getBytes()));

    try (final var in = Files.newInputStream(zip)) {
      ServerInstaller.installNeovimBundle(in, tmp.resolve("server"));
    }

    final Path neovim = tmp.resolve("server").resolve(LatheLayout.NEOVIM_DIR);
    assertThat(neovim.resolve("lua/lathe.lua")).hasContent("return {}");
    assertThat(neovim.resolve("lua/lathe/indent.lua")).hasContent("return {}");
    assertThat(neovim.resolve("ftplugin/java.lua")).hasContent("vim.bo.shiftwidth = 2");
    assertThat(neovim.resolve("after/indent/java.lua")).hasContent("vim.bo.indentexpr = ''");
  }

  @Test
  void installNeovimBundle_existingRuntime_replacesRuntime() throws IOException {
    final Path versionDir = tmp.resolve("server");
    final Path stale = versionDir.resolve(LatheLayout.NEOVIM_DIR).resolve("stale.lua");
    Files.createDirectories(stale.getParent());
    Files.writeString(stale, "stale");
    final Path zip =
        ZipFixture.create(tmp.resolve("lathe-neovim.zip"), "lua/lathe.lua", "return {}");

    try (final var in = Files.newInputStream(zip)) {
      ServerInstaller.installNeovimBundle(in, versionDir);
    }

    assertThat(stale).doesNotExist();
    assertThat(versionDir.resolve(LatheLayout.NEOVIM_DIR).resolve("lua/lathe.lua"))
        .hasContent("return {}");
  }
}
