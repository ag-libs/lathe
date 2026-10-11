package io.github.aglibs.lathe.core;

import java.nio.file.Path;
import java.util.Collection;

public final class LatheLayout {

  public static final String LATHE_DIR = ".lathe";
  public static final String CACHE_DIR = ".cache";
  public static final String CACHE_LATHE_DIR = "lathe";
  public static final String CACHE_DEPS_DIR = "deps";
  public static final String CACHE_JDKS_DIR = "jdks";
  public static final String TYPE_INDEX_DIR = "type-index";
  public static final String LOGS_DIR = "logs";
  public static final String MCP_LOG_PREFIX = "mcp-";
  public static final String LOG_SUFFIX = ".log";
  public static final String SERVERS_DIR = "servers";

  // Best-effort machine-global pointer at the newest installed server, for the one consumer with no
  // workspace context: a user-global MCP registration. Version-correct clients use the
  // per-workspace
  // launcher instead.
  public static final String LATEST_LINK = "latest";

  // Removed discovery pointer; kept only so sync can delete a leftover `current` and make an
  // un-upgraded client fail loudly instead of silently running a stale server.
  public static final String LEGACY_CURRENT_LINK = "current";
  public static final String LAUNCHER_SCRIPT = "lathe-launcher.sh";
  public static final String MCP_LAUNCHER_SCRIPT = "lathe-mcp-launcher.sh";
  public static final String SYNC_SCRIPT = "lathe-sync.sh";
  // Versions every .lathe/ file; bump it on any .lathe/ format change.
  public static final String SCHEMA_VERSION = "5";
  // Versions the ~/.cache/lathe type-index shards and dependency-source markers, separately so a
  // .lathe/ format change does not force rebuilding every cache.
  public static final String CACHE_SCHEMA_VERSION = "4";
  public static final String WORKSPACE_JSON = "workspace.json";
  public static final String JAVA_HOME_FILE = "java-home";
  public static final String JVM_ARGS_FILE = "jvm.args";
  // Distinct names so the root module, whose module dir is .lathe/ itself, cannot alias the
  // reactor lock: releasing its module lock would otherwise end the build's lock mid-build.
  public static final String REACTOR_LOCK_FILE = "lathe.lock";
  public static final String MODULE_LOCK_FILE = "module.lock";
  public static final String MODULE_INFO_JAVA = "module-info.java";
  public static final String GENERATED_SOURCES = "generated-sources";
  public static final String GENERATED_TEST_SOURCES = "generated-test-sources";
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
  public static final String STYLE_FILE = "style.json";
  public static final String STYLE_SHARED_FILE = "lathe-style.json";
  public static final String TEST_RUNNER_MAIN_CLASS =
      "io.github.aglibs.lathe.runner.LatheTestRunner";

  // What users and agents run to refresh an already-synced workspace: the script the build wrote,
  // which knows the build tool. Every remediation below and the MCP instructions compose it.
  public static final String SYNC_SCRIPT_COMMAND = "%s/%s".formatted(LATHE_DIR, SYNC_SCRIPT);
  public static final String SYNC_TESTS_FLAG = "--tests";

  // Scoped form: refresh the given modules and their upstream.
  public static String scopedSyncCommand(final Collection<String> modules) {
    return "%s %s".formatted(SYNC_SCRIPT_COMMAND, String.join(" ", modules));
  }

  // Re-captures the module's test launch without running its tests.
  public static String captureTestsCommand(final String module) {
    return "%s %s %s".formatted(SYNC_SCRIPT_COMMAND, SYNC_TESTS_FLAG, module);
  }

  // A missing .lathe/ can mean the project is not set up for Lathe at all, or set up but not yet
  // built — so this remediation covers both rather than assuming a build alone will fix it. No sync
  // script exists yet, so it names each front-end's first command instead.
  public static final String SETUP_REMEDIATION =
      "If this project is not set up for Lathe yet: for Maven, add the lathe-maven-extension and run `%s`; for an OpenJDK checkout, run `%s %s:%s:%s`. Either generates %s."
          .formatted(
              Maven.SYNC_COMMAND,
              Maven.MVN,
              Maven.LATHE_GROUP_ID,
              Maven.OPENJDK_PLUGIN_ARTIFACT_ID,
              Maven.SYNC_GOAL,
              LATHE_DIR);

  // The remaining %s pair is the found and expected SCHEMA_VERSION.
  public static final String SCHEMA_REMEDIATION =
      "Lathe: .lathe/ was written by another Lathe version (schema %%s, expected %%s). Run `%s` to regenerate it."
          .formatted(SYNC_SCRIPT_COMMAND);

  private LatheLayout() {}

  // Names only the Maven front-end uses (lathe-compiler, lathe-maven-extension, lathe-maven-plugin,
  // lathe-install, lathe-openjdk-maven-plugin). Build-neutral modules must not reference them;
  // LatheLayoutTest guards that.
  public static final class Maven {

    public static final String POM_XML = "pom.xml";
    public static final String POM_PACKAGING = "pom";
    public static final String TEST_JAR_CLASSIFIER = "tests";
    public static final String MVN_DIR = ".mvn";
    public static final String MVN_JVM_CONFIG_FILE = "jvm.config";
    // MAVEN_OPTS as the build sees it: Maven exposes the environment as env.* session properties,
    // and mvnd forwards the client's environment per build (it never applies MAVEN_OPTS to its
    // daemon JVM), so this reads the same under mvn and mvnd.
    public static final String MAVEN_OPTS_PROPERTY = "env.MAVEN_OPTS";

    // Coordinates and keys for the in-memory model injection performed by lathe-maven-extension.
    public static final String LATHE_GROUP_ID = "io.github.ag-libs";
    public static final String COMPILER_ARTIFACT_ID = "lathe-compiler";
    public static final String PLUGIN_ARTIFACT_ID = "lathe-maven-plugin";
    public static final String OPENJDK_PLUGIN_ARTIFACT_ID = "lathe-openjdk-maven-plugin";
    public static final String JUNIT_ARTIFACT_ID = "lathe-junit";
    public static final String COMPILER_ID = "lathe";
    public static final String MAVEN_COMPILER_ID_PROPERTY = "maven.compiler.compilerId";
    public static final String MAVEN_COMPILER_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
    public static final String MAVEN_COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";
    public static final String INIT_GOAL = "init";
    public static final String SYNC_GOAL = "sync";
    public static final String INIT_EXECUTION_ID = "lathe-init";
    public static final String SYNC_EXECUTION_ID = "lathe-sync";

    // The phase that runs lathe:sync (bound via LifecyclePhase.PROCESS_TEST_CLASSES), and the build
    // command for the first sync, before the build has written the sync script.
    static final String MVN = "mvn";
    public static final String SYNC_PHASE = "process-test-classes";
    public static final String SYNC_COMMAND = "%s %s".formatted(MVN, SYNC_PHASE);

    // Hands each compile's scope from the extension to the compiler shim, which sees only an output
    // directory: system properties cross their classloaders in the one Maven JVM, and the key is
    // the output directory, so parallel module builds never collide.
    private static final String TREE_PROPERTY_PREFIX = "lathe.tree:";

    public static String treeProperty(final String outputDirectory) {
      return TREE_PROPERTY_PREFIX + Path.of(outputDirectory).toAbsolutePath().normalize();
    }

    private Maven() {}
  }

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

  public static Path serversDir() {
    return userCacheRoot().resolve(SERVERS_DIR);
  }

  public static Path latestLink() {
    return userCacheRoot().resolve(LATEST_LINK);
  }

  public static Path legacyCurrentLink() {
    return userCacheRoot().resolve(LEGACY_CURRENT_LINK);
  }

  public static Path logsDir() {
    return userCacheRoot().resolve(LOGS_DIR);
  }

  // Per-session MCP log file, agent-independent; sessionId is the caller's process tag so each
  // agent session maps to one file and files sort chronologically.
  public static Path mcpSessionLog(final String sessionId) {
    return logsDir().resolve("%s%s%s".formatted(MCP_LOG_PREFIX, sessionId, LOG_SUFFIX));
  }

  public static String cacheName(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
  }

  public static Path reactorLockFile(final Path latheDir) {
    return latheDir.resolve(REACTOR_LOCK_FILE);
  }

  public static Path moduleLockFile(final Path moduleDir) {
    return moduleDir.resolve(MODULE_LOCK_FILE);
  }

  public static String paramsFileName(final String sourceTree) {
    return PARAMS_FILE_PREFIX + sourceTree + ".json";
  }

  public static String compiledStampsFileName(final String sourceTree) {
    return STAMPS_FILE_PREFIX + sourceTree + ".json";
  }

  // The main and test compile each mirror their annotation-processor output to a scope-specific dir
  // (like classes/test-classes) so the test compile does not clobber the main compile's sources.
  public static String generatedSourcesDir(final String sourceTree) {
    return TEST_CLASSES_DIR.equals(sourceTree) ? GENERATED_TEST_SOURCES : GENERATED_SOURCES;
  }

  public static boolean isParamsFile(final Path path) {
    final var name = path.getFileName().toString();
    return name.startsWith(PARAMS_FILE_PREFIX) && name.endsWith(".json");
  }
}
