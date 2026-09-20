package io.github.aglibs.lathe.core;

import java.nio.file.Path;

public final class LatheLayout {

  public static final String LATHE_DIR = ".lathe";
  public static final String CACHE_DIR = ".cache";
  public static final String CACHE_LATHE_DIR = "lathe";
  public static final String CACHE_DEPS_DIR = "deps";
  public static final String CACHE_JDKS_DIR = "jdks";
  public static final String TYPE_INDEX_DIR = "type-index";
  public static final String SERVERS_DIR = "servers";
  public static final String CURRENT_LINK = "current";
  public static final String LAUNCHER_SCRIPT = "lathe-launcher.sh";
  public static final String MCP_LAUNCHER_SCRIPT = "lathe-mcp-launcher.sh";
  public static final String NVIM_DIR = "neovim";
  public static final String NVIM_BUNDLE = "lathe-neovim.zip";
  public static final String NVIM_MARKER = ".lathe-neovim.properties";
  public static final String SCHEMA_VERSION = "4";
  public static final String WORKSPACE_JSON = "workspace.json";
  public static final String LOCK_FILE = "lathe.lock";
  public static final String MODULE_INFO_JAVA = "module-info.java";
  public static final String POM_XML = "pom.xml";
  public static final String GENERATED_SOURCES = "generated-sources";
  public static final String TARGET_DIR = "target";
  public static final String CLASSES_DIR = "classes";
  public static final String TEST_CLASSES_DIR = "test-classes";
  public static final String PARAMS_FILE_PREFIX = "lsp-params-";
  public static final String STAMPS_FILE_PREFIX = "lsp-stamps-";
  public static final String DEPENDENCY_SOURCE_FILENAME = ".lathe-source.json";
  public static final String TYPE_INDEX_FILENAME = "index.json";
  public static final String TEST_LAUNCH_FILE = "test-launch.json";
  public static final String MAIN_LAUNCH_FILE = "main-launch.json";
  public static final String RUN_CONFIG_SHARED_FILE = "lathe-run.json";
  public static final String RUN_CONFIG_LOCAL_FILE = "run.json";
  public static final String TEST_LAUNCH_SCHEMA_VERSION = "1";
  public static final String MAIN_LAUNCH_SCHEMA_VERSION = "1";
  public static final String TEST_RUNNER_MAIN_CLASS =
      "io.github.aglibs.lathe.runner.LatheTestRunner";

  // Maven coordinates and keys for the in-memory model injection performed by
  // lathe-maven-extension.
  public static final String LATHE_GROUP_ID = "io.github.ag-libs";
  public static final String COMPILER_ARTIFACT_ID = "lathe-compiler";
  public static final String PLUGIN_ARTIFACT_ID = "lathe-maven-plugin";
  public static final String JUNIT_ARTIFACT_ID = "lathe-junit";
  public static final String COMPILER_ID = "lathe";
  public static final String MAVEN_COMPILER_ID_PROPERTY = "maven.compiler.compilerId";
  public static final String MAVEN_COMPILER_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
  public static final String MAVEN_COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";
  public static final String INIT_GOAL = "init";
  public static final String SYNC_GOAL = "sync";
  public static final String INIT_EXECUTION_ID = "lathe-init";
  public static final String SYNC_EXECUTION_ID = "lathe-sync";

  // The Maven phase that runs lathe:sync (bound via LifecyclePhase.PROCESS_TEST_CLASSES) and the
  // command we suggest users/agents run to refresh .lathe/. The single source for that wording —
  // every remediation string below and the MCP instructions compose it rather than repeating it.
  public static final String SYNC_PHASE = "process-test-classes";
  public static final String SYNC_COMMAND = "mvn %s".formatted(SYNC_PHASE);

  // A missing .lathe/ can mean the project is not set up for Lathe at all, or set up but not yet
  // built — so this remediation covers both rather than assuming a build alone will fix it.
  // Centralized here so the wording lives in one place.
  public static final String SETUP_REMEDIATION =
      "If this project is not set up for Lathe yet, add the lathe-maven-extension; then run `%s` to generate %s."
          .formatted(SYNC_COMMAND, LATHE_DIR);

  // A non-editor client (the MCP agent) gets no interactive sync prompt, so a tool result carries
  // this instead. The remaining %s is the stale-module list, filled at use.
  public static final String STALE_REMEDIATION =
      "Stale: module(s) %%s have source newer than their compiled classes, so cross-module results may be out of date. Run `%s` to refresh Lathe's classes."
          .formatted(SYNC_COMMAND);

  private LatheLayout() {}

  public static Path userCacheRoot() {
    final var override = System.getProperty("lathe.cache");
    if (override != null) {
      return Path.of(override);
    }

    return Path.of(System.getProperty("user.home"), CACHE_DIR, CACHE_LATHE_DIR);
  }

  public static Path serverVersionDir(final String version) {
    return userCacheRoot().resolve(SERVERS_DIR).resolve(version);
  }

  public static Path currentLink() {
    return userCacheRoot().resolve(CURRENT_LINK);
  }

  public static String cacheName(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
  }

  public static String paramsFileName(final String sourceTree) {
    return PARAMS_FILE_PREFIX + sourceTree + ".json";
  }

  public static String compiledStampsFileName(final String sourceTree) {
    return STAMPS_FILE_PREFIX + sourceTree + ".json";
  }

  public static boolean isParamsFile(final Path path) {
    final var name = path.getFileName().toString();
    return name.startsWith(PARAMS_FILE_PREFIX) && name.endsWith(".json");
  }
}
