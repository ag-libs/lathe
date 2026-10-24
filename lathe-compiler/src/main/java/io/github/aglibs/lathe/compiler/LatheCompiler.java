package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.LatheBuildInfo;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.LatheWorkspace;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.install.CompileCapture;
import java.io.IOException;
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
    final boolean locked = tryAcquireLock(moduleDir, moduleRel);

    try {
      final var result = javacCompiler.performCompile(config);
      if (locked) {
        syncOutput(config, moduleDir, moduleRel, result);
      } else {
        LOG.warn("[lathe] {} skipping .lathe/ sync — lock unavailable", moduleRel);
      }

      return result;
    } finally {
      if (locked) {
        try {
          LatheLock.release(LatheLayout.moduleLockFile(moduleDir));
        } catch (final IOException e) {
          LOG.warn("[lathe] {} failed to delete lock file", moduleRel, e);
        }
      }
    }
  }

  private boolean tryAcquireLock(final Path moduleDir, final Path moduleRel) {
    try {
      LatheLock.acquire(LatheLayout.moduleLockFile(moduleDir));
      return true;
    } catch (final IOException e) {
      LOG.warn("[lathe] {} failed to create lock file", moduleRel, e);
      return false;
    }
  }

  static void syncOutput(
      final CompilerConfiguration config,
      final Path moduleDir,
      final Path moduleRel,
      final CompilerResult result) {
    final var sw = Stopwatch.start();
    try {
      final ModuleConfigData moduleConfig = MavenModuleConfig.of(config);
      CompileCapture.writeParams(moduleDir, moduleConfig);
      // Maven empties its output before a full recompile, so a failed one leaves nothing to mirror:
      // keep the last good capture and stamps, so changed sources stay stale for the server.
      if (!result.isSuccess()) {
        if (result.getCompilerMessages().isEmpty()) {
          LOG.warn(
              "[lathe] {} javac failed with no diagnostics; kept the last good capture", moduleRel);
        } else {
          LOG.info("[lathe] {} compile failed; kept the last good capture", moduleRel);
        }

        return;
      }

      CompileCapture.mirror(moduleDir, moduleConfig);
      LOG.info(
          "[lathe] {} {} capture complete {}ms",
          moduleRel,
          LatheBuildInfo.summary(LatheCompiler.class),
          sw.elapsedMs());
    } catch (final IOException e) {
      LOG.warn("[lathe] {} post-compile step failed", moduleRel, e);
    }
  }

  // The compile's scope as the extension declared it for this output directory; without the
  // extension (or for an output that is neither the main nor the test one), the directory's leaf.
  static String sourceTree(final CompilerConfiguration config) {
    final String outputLocation = config.getOutputLocation();
    return System.getProperty(
        LatheLayout.Maven.treeProperty(outputLocation),
        Path.of(outputLocation).getFileName().toString());
  }

  private Optional<LatheContext> resolveLatheContext(final CompilerConfiguration config) {
    final var moduleRoot = config.getWorkingDirectory().toPath().toAbsolutePath().normalize();
    return LatheWorkspace.findRoot(moduleRoot, buildRoot())
        .map(
            workspaceRoot -> {
              final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
              final var moduleRel = workspaceRoot.relativize(moduleRoot);
              final var moduleDir = latheDir.resolve(moduleRel);
              return new LatheContext(moduleDir, moduleRel);
            });
  }

  // The directory Maven treats as this build's root — where mvn was invoked (.mvn-anchored when a
  // .mvn/ is present, otherwise the launch dir). Caps the .lathe/ search so a build inside a nested
  // worktree/checkout cannot climb out and adopt a parent repo's .lathe/.
  private static Path buildRoot() {
    final var root =
        System.getProperty("maven.multiModuleProjectDirectory", System.getProperty("user.dir"));
    return root == null ? null : Path.of(root).toAbsolutePath().normalize();
  }

  private record LatheContext(Path moduleDir, Path moduleRel) {}
}
