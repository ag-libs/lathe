package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LatheCompiler implements Compiler {

  private static final Logger LOG = LoggerFactory.getLogger(LatheCompiler.class);

  @SuppressWarnings("unused")
  private Compiler javacCompiler;

  @Override
  public CompilerOutputStyle getCompilerOutputStyle() {
    return javacCompiler.getCompilerOutputStyle();
  }

  @Override
  public String getInputFileEnding(final CompilerConfiguration config) throws CompilerException {
    return javacCompiler.getInputFileEnding(config);
  }

  @Override
  public String getOutputFileEnding(final CompilerConfiguration config) throws CompilerException {
    return javacCompiler.getOutputFileEnding(config);
  }

  @Override
  public String getOutputFile(final CompilerConfiguration config) throws CompilerException {
    return javacCompiler.getOutputFile(config);
  }

  @Override
  public boolean canUpdateTarget(final CompilerConfiguration config) throws CompilerException {
    return javacCompiler.canUpdateTarget(config);
  }

  @Override
  public String[] createCommandLine(final CompilerConfiguration config) throws CompilerException {
    return javacCompiler.createCommandLine(config);
  }

  @Override
  public CompilerResult performCompile(final CompilerConfiguration config)
      throws CompilerException {
    final var ctx = resolveLatheContext(config);

    if (ctx.isEmpty()) {
      LOG.debug("[lathe] no .lathe/ found — skipping");
      return javacCompiler.performCompile(config);
    }

    final var latheCtx = ctx.get();
    final var moduleDir = latheCtx.moduleDir();
    final var moduleRel = latheCtx.moduleRel();
    final var lockFile = moduleDir.resolve(LatheLayout.LOCK_FILE);

    try {
      Files.createDirectories(moduleDir);
      Files.writeString(lockFile, "");
    } catch (final IOException e) {
      LOG.warn("[lathe] {} failed to create lock file", moduleRel, e);
    }

    try {
      final var result = javacCompiler.performCompile(config);
      syncOutput(config, moduleDir, moduleRel, result);
      return result;
    } finally {
      try {
        Files.deleteIfExists(lockFile);
      } catch (final IOException e) {
        LOG.warn("[lathe] {} failed to delete lock file", moduleRel, e);
      }
    }
  }

  private void syncOutput(
      final CompilerConfiguration config,
      final Path moduleDir,
      final Path moduleRel,
      final CompilerResult result) {
    final Stopwatch sw = Stopwatch.start();
    try {
      if (!result.isSuccess() && result.getCompilerMessages().isEmpty()) {
        throw new IOException("javac returned failure with no diagnostics");
      }

      ParamsWriter.write(config, moduleDir);
      final var outputDir = Path.of(config.getOutputLocation());
      FileUtil.replaceDir(outputDir, moduleDir.resolve(outputDir.getFileName()));
      final var genSources = config.getGeneratedSourcesDirectory();
      if (genSources != null && Files.isDirectory(genSources.toPath())) {
        FileUtil.replaceDir(genSources.toPath(), moduleDir.resolve(LatheLayout.GENERATED_SOURCES));
      }

      LOG.info("[lathe] {} {}ms", moduleRel, sw.elapsedMs());
    } catch (final IOException e) {
      LOG.warn("[lathe] {} post-compile step failed", moduleRel, e);
    }
  }

  private Optional<LatheContext> resolveLatheContext(final CompilerConfiguration config) {
    final var moduleRoot = config.getWorkingDirectory().toPath();
    return WorkspaceDetector.findWorkspaceRoot(moduleRoot)
        .map(
            workspaceRoot -> {
              final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
              final var moduleRel = workspaceRoot.relativize(moduleRoot);
              final var moduleDir = latheDir.resolve(moduleRel);
              return new LatheContext(moduleDir, moduleRel);
            });
  }

  private record LatheContext(Path moduleDir, Path moduleRel) {}
}
