package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerInstallerTest {

  private static final long BUNDLE_SIZE = 42L;
  private static final long BUNDLE_MODIFIED = 1_000L;

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

    final Path versionDir = tmp.resolve("server");
    assertThat(install(versionDir, zip, BUNDLE_SIZE)).isTrue();

    final Path neovim = neovimDir(versionDir);
    assertThat(neovim.resolve("lua/lathe.lua")).hasContent("return {}");
    assertThat(neovim.resolve("lua/lathe/indent.lua")).hasContent("return {}");
    assertThat(neovim.resolve("ftplugin/java.lua")).hasContent("vim.bo.shiftwidth = 2");
    assertThat(neovim.resolve("after/indent/java.lua")).hasContent("vim.bo.indentexpr = ''");
    assertThat(neovim.resolve(LatheLayout.NEOVIM_MARKER)).exists();
  }

  @Test
  void installNeovimBundle_existingRuntime_replacesRuntime() throws IOException {
    final Path versionDir = tmp.resolve("server");
    final Path stale = versionDir.resolve(LatheLayout.NEOVIM_DIR).resolve("stale.lua");
    Files.createDirectories(stale.getParent());
    Files.writeString(stale, "stale");

    assertThat(install(versionDir, runtimeZip("lathe-neovim.zip", "return {}"), BUNDLE_SIZE))
        .isTrue();

    assertThat(stale).doesNotExist();
    assertThat(neovimDir(versionDir).resolve("lua/lathe.lua")).hasContent("return {}");
  }

  @Test
  void installNeovimBundle_currentMarker_skipsRuntime() throws IOException {
    final Path versionDir = tmp.resolve("server");
    install(versionDir, runtimeZip("first.zip", "return { first = true }"), BUNDLE_SIZE);

    assertThat(
            install(versionDir, runtimeZip("second.zip", "return { second = true }"), BUNDLE_SIZE))
        .isFalse();

    assertThat(neovimDir(versionDir).resolve("lua/lathe.lua"))
        .hasContent("return { first = true }");
  }

  @Test
  void installNeovimBundle_changedBundleMetadata_replacesRuntime() throws IOException {
    final Path versionDir = tmp.resolve("server");
    install(versionDir, runtimeZip("first.zip", "return { first = true }"), BUNDLE_SIZE);

    assertThat(
            install(
                versionDir, runtimeZip("second.zip", "return { second = true }"), BUNDLE_SIZE + 1))
        .isTrue();

    assertThat(neovimDir(versionDir).resolve("lua/lathe.lua"))
        .hasContent("return { second = true }");
  }

  private Path runtimeZip(final String name, final String content) throws IOException {
    return ZipFixture.create(
        tmp.resolve(name),
        Map.of(
            "lua/lathe.lua", content.getBytes(),
            "lua/lathe/indent.lua", "return {}".getBytes()));
  }

  private static boolean install(final Path versionDir, final Path zip, final long bundleSize)
      throws IOException {
    try (final InputStream in = Files.newInputStream(zip)) {
      return ServerInstaller.installNeovimBundle(in, versionDir, bundleSize, BUNDLE_MODIFIED);
    }
  }

  private static Path neovimDir(final Path versionDir) {
    return versionDir.resolve(LatheLayout.NEOVIM_DIR);
  }
}
