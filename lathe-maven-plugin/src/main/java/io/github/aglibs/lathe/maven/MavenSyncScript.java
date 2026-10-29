package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Platform;
import io.github.aglibs.lathe.install.SyncScript;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

// Maven's .lathe/lathe-sync.sh, the one command clients, the server, and the MCP server run to
// refresh .lathe/, so none of them compose a Maven command line. It runs the Maven and JDK that ran
// this build, and prints the exact command first so the user sees what runs.
final class MavenSyncScript {

  private MavenSyncScript() {}

  // --tests captures each module's test launch without running the tests: the capture-only filter
  // excludes them all, and failIfNoTests=false keeps a build that requires tests green.
  // A scoped sync builds the modules with -am, so they resolve against fresh upstream classes. No
  // -amd: Maven does not add the dependents' own upstream, so on a large reactor a dependent fails
  // to resolve a module outside the scope; callers that need dependents name them. The build cache
  // stays off, since a cache hit skips javac and the test fork, where Lathe captures.
  static SyncScript of(final Properties systemProperties) {
    return new SyncScript(
        LatheLayout.Maven.runningTool(systemProperties, Platform.WINDOWS),
        Path.of(systemProperties.getProperty(LatheLayout.JAVA_HOME_PROPERTY)),
        List.of(LatheLayout.Maven.NO_TRANSFER_PROGRESS, "-Dmaven.build.cache.enabled=false"),
        List.of(LatheLayout.Maven.SYNC_PHASE),
        List.of("test", LatheLayout.Maven.CAPTURE_ONLY_ARG, "-DfailIfNoTests=false"),
        List.of("-pl", LatheFlags.SYNC_MODULE_TOKEN),
        List.of("-am"));
  }
}
