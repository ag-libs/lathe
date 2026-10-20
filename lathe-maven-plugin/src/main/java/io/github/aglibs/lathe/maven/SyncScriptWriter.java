package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.SyncScript;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.maven.plugin.logging.Log;

// Writes .lathe/lathe-sync.sh, the one command clients, the server, and the MCP server run to
// refresh .lathe/, so none of them compose a Maven command line. It runs the Maven and JDK that ran
// this build, and prints the exact command first so the user sees what runs.
final class SyncScriptWriter {

  private final Log log;

  SyncScriptWriter(final Log log) {
    this.log = log;
  }

  void write(final Path workspaceRoot, final SyncScript script) {
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var file = latheDir.resolve(LatheLayout.SYNC_SCRIPT);
    try {
      if (FileUtil.writeIfChanged(latheDir, file, script.posix(), true)) {
        log.info("[sync] wrote %s".formatted(file));
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write %s".formatted(file), e);
    }
  }

  // --tests captures each module's test launch without running the tests: the capture-only filter
  // excludes them all, and failIfNoTests=false keeps a build that requires tests green.
  // A scoped sync builds the modules with -am, so they resolve against fresh upstream classes. No
  // -amd: Maven does not add the dependents' own upstream, so on a large reactor a dependent fails
  // to resolve a module outside the scope; callers that need dependents name them. The build cache
  // stays off, since a cache hit skips javac and the test fork, where Lathe captures.
  static SyncScript script(final Properties systemProperties) {
    return new SyncScript(
        LatheLayout.Maven.runningTool(systemProperties),
        Path.of(systemProperties.getProperty(LatheLayout.JAVA_HOME_PROPERTY)),
        List.of(LatheLayout.Maven.NO_TRANSFER_PROGRESS, "-Dmaven.build.cache.enabled=false"),
        List.of(LatheLayout.Maven.SYNC_PHASE),
        List.of("test", "-D%s=true".formatted(LatheFlags.CAPTURE_ONLY), "-DfailIfNoTests=false"),
        List.of("-pl", LatheFlags.SYNC_MODULE_TOKEN),
        List.of("-am"));
  }
}
