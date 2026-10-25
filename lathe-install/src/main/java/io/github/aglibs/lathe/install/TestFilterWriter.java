package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// .lathe/<module>/test-filter.json, the class-name filter the server applies to a module's package
// and module test runs. The build tool turns its own include/exclude patterns into the regexes.
public final class TestFilterWriter {

  private static final Logger LOG = LoggerFactory.getLogger(TestFilterWriter.class);

  private TestFilterWriter() {}

  public static void write(
      final Path workspaceRoot, final String moduleRel, final TestFilterData filter) {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final Path filterFile = moduleDir.resolve(LatheLayout.TEST_FILTER_FILE);
    try {
      if (FileUtil.writeIfChanged(moduleDir, filterFile, Json.toJson(filter), false)) {
        LOG.debug("[sync] test-filter %s written".formatted(moduleRel));
      }
    } catch (final IOException e) {
      throw new SyncException(
          "lathe:sync failed to write the test filter for %s".formatted(moduleRel), e);
    }
  }
}
