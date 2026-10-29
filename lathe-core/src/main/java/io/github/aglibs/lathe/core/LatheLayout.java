package io.github.aglibs.lathe.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

public final class LatheLayout {

  // The oldest JDK Lathe's code runs on. A compile-time constant, so a class built for an older
  // release (lathe-junit's listener) can compare against it without loading this one.
  public static final int MIN_JAVA_VERSION = 17;

  public static final String LATHE_DIR = ".lathe";
  public static final String CACHE_DIR = ".cache";
  public static final String CACHE_LATHE_DIR = "lathe";
  public static final String CACHE_DEPS_DIR = "deps";
  public static final String CACHE_JDKS_DIR = "jdks";
  public static final String TYPE_INDEX_DIR = "type-index";
  public static final String LOGS_DIR = "logs";
  public static final String MCP_LOG_PREFIX = "mcp-";
  public static final String LOG_SUFFIX = ".log";
  // The lock JUL's FileHandler keeps beside its file; a server killed outright leaves it behind.
  public static final String LOG_LOCK_SUFFIX = ".lck";
  public static final String SERVERS_DIR = "servers";

  // Best-effort machine-global copy of the newest installed server's launchers, for the one
  // consumer with no workspace context: a user-global MCP registration. Version-correct clients
  // use the per-workspace launcher instead.
  public static final String LATEST_DIR = "latest";

  // Removed discovery pointer; kept only so sync can delete a leftover `current` and make an
  // un-upgraded client fail loudly instead of silently running a stale server.
  public static final String LEGACY_CURRENT_LINK = "current";
  // Generated scripts are in the host's dialect only: cmd batch files on Windows, POSIX sh
  // elsewhere.
  private static final String SYNC_SCRIPT_BASE = "lathe-sync";
  public static final String LAUNCHER_SCRIPT = scriptName("lathe-launcher", Platform.WINDOWS);
  public static final String MCP_LAUNCHER_SCRIPT =
      scriptName("lathe-mcp-launcher", Platform.WINDOWS);
  public static final String SYNC_SCRIPT = scriptName(SYNC_SCRIPT_BASE, Platform.WINDOWS);
  // The launchers' fixed Java arguments, next to each copy of the launcher that reads them.
  public static final String SERVER_ARGS_FILE = "lathe-server.args";
  public static final String MCP_ARGS_FILE = "lathe-mcp.args";
  // Versions every .lathe/ file; bump it on any .lathe/ format change.
  public static final String SCHEMA_VERSION = "6";
  // Versions the ~/.cache/lathe type-index shards and dependency-source markers, separately so a
  // .lathe/ format change does not force rebuilding every cache.
  public static final String CACHE_SCHEMA_VERSION = "4";
  public static final String WORKSPACE_JSON = "workspace.json";
  public static final String JAVA_HOME_FILE = "java-home";
  public static final String JAVA_HOME_PROPERTY = "java.home";
  public static final String NATIVE_ENCODING_PROPERTY = "native.encoding";
  public static final String BIN_DIR = "bin";
  public static final String JVM_ARGS_FILE = "jvm.args";
  // A run/test launch too long for one command line passes its arguments in a temp argfile.
  public static final String LAUNCH_ARGFILE_PREFIX = "lathe-launch-";
  public static final String ARGFILE_SUFFIX = ".args";
  // Distinct names so the root module, whose module dir is .lathe/ itself, cannot alias the
  // reactor lock: releasing its module lock would otherwise end the build's lock mid-build.
  public static final String REACTOR_LOCK_FILE = "lathe.lock";
  public static final String MODULE_LOCK_FILE = "module.lock";
  // Held by the Lathe server that is writing .lathe/, so two servers on one workspace (an editor's
  // and an agent's) never write the same output at once.
  public static final String WRITE_LOCK_FILE = "write.lock";
  public static final String MODULE_INFO_JAVA = "module-info.java";
  public static final String MODULE_INFO_CLASS = "module-info.class";
  public static final String GENERATED_SOURCES = "generated-sources";
  public static final String GENERATED_TEST_SOURCES = "generated-test-sources";
  public static final String CLASSES_DIR = "classes";
  public static final String TEST_CLASSES_DIR = "test-classes";
  public static final String PARAMS_FILE_PREFIX = "lsp-params-";
  public static final String STAMPS_FILE_PREFIX = "lsp-stamps-";
  private static final String JSON_SUFFIX = ".json";
  public static final String DEPENDENCY_SOURCE_FILENAME = ".lathe-source.json";
  public static final String JAR_MAVEN_METADATA_DIR = "META-INF/maven";
  public static final String TYPE_INDEX_FILENAME = "index.json";
  public static final String TEST_LAUNCH_FILE = "test-launch.json";
  public static final String MAIN_LAUNCH_FILE = "main-launch.json";
  public static final String TEST_FILTER_FILE = "test-filter.json";
  public static final String TEST_DISCOVERY_FILE = "test-discovery.json";
  public static final String RUN_CONFIG_SHARED_FILE = "lathe-run.json";
  public static final String RUN_CONFIG_LOCAL_FILE = "run.json";
  public static final String STYLE_FILE = "style.json";
  public static final String STYLE_SHARED_FILE = "lathe-style.json";
  public static final String TEST_RUNNER_MAIN_CLASS =
      "io.github.aglibs.lathe.runner.LatheTestRunner";

  // What users and agents run to refresh an already-synced workspace: the script the build wrote,
  // which knows the build tool. Every remediation below and the MCP instructions compose it.
  public static final String SYNC_SCRIPT_COMMAND = syncScriptCommand(Platform.WINDOWS);
  public static final String SYNC_TESTS_FLAG = "--tests";

  public static String scriptName(final String base, final boolean windows) {
    return "%s.%s".formatted(base, windows ? "cmd" : "sh");
  }

  // cmd reads a '/' path as a switch, so on Windows the command uses '\'.
  public static String syncScriptCommand(final boolean windows) {
    return "%s%s%s"
        .formatted(LATHE_DIR, windows ? "\\" : "/", scriptName(SYNC_SCRIPT_BASE, windows));
  }

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
      "If this project is not set up for Lathe yet: for Maven, add the lathe-maven-extension and run `%s`; for Gradle, apply the `%s` settings plugin and run `%s`; for an OpenJDK checkout, run `%s %s:%s:%s`. Each generates %s."
          .formatted(
              Maven.FIRST_BUILD_COMMAND,
              Gradle.PLUGIN_ID,
              Gradle.FIRST_BUILD_COMMAND,
              Maven.MVN,
              Maven.LATHE_GROUP_ID,
              Maven.OPENJDK_PLUGIN_ARTIFACT_ID,
              Maven.SYNC_GOAL,
              LATHE_DIR);

  // The remaining %s pair is the found and expected SCHEMA_VERSION.
  public static final String SCHEMA_REMEDIATION =
      "Lathe: .lathe/ was written by another Lathe version (schema %%s, expected %%s). Run `%s` to regenerate it."
          .formatted(SYNC_SCRIPT_COMMAND);

  // The remaining %s is the error's root cause.
  public static final String MANIFEST_UNREADABLE_REMEDIATION =
      "Lathe: %s/%s could not be read (%%s); dependency sources and cross-module features are off. Run `%s` to regenerate it, and report it with the server log if it persists."
          .formatted(LATHE_DIR, WORKSPACE_JSON, SYNC_SCRIPT_COMMAND);

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
    public static final String MAVEN_SUREFIRE_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
    public static final String MAVEN_SUREFIRE_PLUGIN_ARTIFACT_ID = "maven-surefire-plugin";
    // The execution `mvn test` runs, whose configuration overrides the plugin-level one.
    public static final String SUREFIRE_TEST_EXECUTION_ID = "default-test";
    // Surefire's class-file patterns when a module configures none.
    public static final List<String> SUREFIRE_DEFAULT_INCLUDES =
        List.of("**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java");
    public static final List<String> SUREFIRE_DEFAULT_EXCLUDES = List.of("**/*$*");
    // The oldest Maven and maven-compiler-plugin Lathe supports: an older one stops the build and
    // says why. maven.version is the system property Maven sets to its own version.
    public static final String MIN_MAVEN_VERSION = "3.6.3";
    public static final String MIN_COMPILER_PLUGIN_VERSION = "3.6.2";
    public static final String MAVEN_VERSION_PROPERTY = "maven.version";
    public static final String INIT_GOAL = "init";
    public static final String SYNC_GOAL = "sync";
    public static final String INIT_EXECUTION_ID = "lathe-init";
    public static final String SYNC_EXECUTION_ID = "lathe-sync";

    // The phase that runs lathe:sync (bound via LifecyclePhase.PROCESS_TEST_CLASSES), and the build
    // command for the first sync, before the build has written the sync script.
    static final String MVN = "mvn";
    public static final String SYNC_PHASE = "process-test-classes";
    public static final String SYNC_COMMAND = "%s %s".formatted(MVN, SYNC_PHASE);
    // A test run that captures the tests without running them.
    public static final String CAPTURE_ONLY_ARG = "-D%s=true".formatted(LatheFlags.CAPTURE_ONLY);
    // A project's first build: clean, so every module compiles through Lathe, and a capture-only
    // test run. SYNC_COMMAND refreshes after that.
    public static final String FIRST_BUILD_COMMAND =
        "%s clean test %s".formatted(MVN, CAPTURE_ONLY_ARG);
    static final String MVND = "mvnd";
    // Every sync script's Maven run: no download progress in the output the user reads.
    public static final String NO_TRANSFER_PROGRESS = "--no-transfer-progress";
    static final String MAVEN_HOME_PROPERTY = "maven.home";
    static final String MVND_HOME_PROPERTY = "mvnd.home";

    // The Maven that runs this build, for the sync script to run again. Under mvnd, maven.home is
    // the Maven it embeds, so mvnd.home's client wins; plain Maven and the wrapper set maven.home.
    public static Path runningTool(final Properties systemProperties, final boolean windows) {
      final String suffix = windows ? ".cmd" : "";
      final String mvndHome = systemProperties.getProperty(MVND_HOME_PROPERTY);
      if (mvndHome != null) {
        return Path.of(mvndHome, BIN_DIR, MVND + suffix);
      }

      return Path.of(systemProperties.getProperty(MAVEN_HOME_PROPERTY), BIN_DIR, MVN + suffix);
    }

    // Hands each compile's scope from the extension to the compiler shim, which sees only an output
    // directory: system properties cross their classloaders in the one Maven JVM, and the key is
    // the output directory, so parallel module builds never collide.
    private static final String TREE_PROPERTY_PREFIX = "lathe.tree:";

    public static String treeProperty(final String outputDirectory) {
      return TREE_PROPERTY_PREFIX + FileUtil.absolute(outputDirectory);
    }

    private Maven() {}
  }

  public static final class Gradle {

    // The oldest Gradle Lathe supports: the first with gradle.lifecycle.beforeProject, which
    // applies Lathe to each project without cross-project configuration (Isolated Projects).
    public static final String MIN_GRADLE_VERSION = "8.8";
    public static final String PLUGIN_ID = "io.github.ag-libs.lathe";
    // The task the sync script runs, and its argument that captures the tests without running them.
    public static final String SYNC_TASK = "latheSync";
    public static final String CAPTURE_ONLY_ARG = "-P%s=true".formatted(LatheFlags.CAPTURE_ONLY);
    // A project's first run, for the setup message: it writes .lathe/ without running the tests.
    public static final String FIRST_BUILD_COMMAND =
        "./gradlew %s %s".formatted(SYNC_TASK, CAPTURE_ONLY_ARG);

    // The build files a sync fingerprints, so the server notices a changed build: the settings
    // file, gradle.properties and the version catalogs at the root, each project's build file, and
    // the build logic in buildSrc/ and included builds, where Gradle builds keep their compiler
    // settings.
    public static final List<String> ROOT_BUILD_FILES =
        List.of("settings.gradle", "settings.gradle.kts", "gradle.properties");
    public static final List<String> PROJECT_BUILD_FILES =
        List.of("build.gradle", "build.gradle.kts");
    public static final String CATALOG_DIR = "gradle";
    public static final String CATALOG_SUFFIX = ".toml";
    public static final String BUILD_SRC_DIR = "buildSrc";
    public static final List<String> BUILD_LOGIC_SUFFIXES =
        List.of(".gradle", ".gradle.kts", ".java", ".kt", ".groovy", ".toml", ".properties");
    // The daemon's JVM options; module-access flags there reach a compile that runs in the daemon.
    public static final String DAEMON_JVM_ARGS_PROPERTY = "org.gradle.jvmargs";
    // What Gradle's test worker adds to a test JVM's launch, not the build: its own jar (on the
    // class or module path), its -D properties, and, on a modular run, itself as the main module.
    public static final String TEST_WORKER_JAR = "gradle-worker.jar";
    public static final String TEST_WORKER_PROPERTY_PREFIX = "-Dorg.gradle.";
    public static final String TEST_WORKER_MAIN_MODULE = "-Djdk.module.main=gradle.worker";
    // Where each module's test runs load lathe-junit from as an agent: .lathe/<module>/.agent/, a
    // path known as the plugin applies, before a build script can move the build directory. Hidden,
    // since .lathe/<module>/<child> is also the module directory of a child project in <child>/,
    // which the copy into this directory would empty.
    public static final String AGENT_DIR = ".agent";
    public static final String AGENT_JAR = "lathe-junit.jar";
    // Each project's facts for the root's workspace write to merge, in .lathe/<module>/.
    public static final String FACTS_FILE = "lathe-facts.json";
    // Output and state directories inside a build-logic build, never part of it.
    public static final List<String> BUILD_LOGIC_SKIPPED_DIRS = List.of("build", ".gradle");

    // The launcher of the Gradle distribution that ran the build (gradle.gradleHomeDir), not the
    // wrapper: the same version, with no download step.
    public static Path runningTool(final Path gradleHome, final boolean windows) {
      return gradleHome.resolve(BIN_DIR).resolve(windows ? "gradle.bat" : "gradle");
    }

    private Gradle() {}
  }

  public static Path userCacheRoot() {
    final var override = System.getProperty(LatheFlags.CACHE_ROOT);
    if (override != null) {
      return Path.of(override);
    }

    return Path.of(System.getProperty("user.home"), CACHE_DIR, CACHE_LATHE_DIR);
  }

  public static Path serverVersionDir(final String version) {
    return userCacheRoot().resolve(SERVERS_DIR).resolve(version);
  }

  // Nested like ~/.m2: every segment already appears in the artifact's local-repository path, so
  // it is legal wherever that repository lives. A flat "g:a:v" name is illegal on Windows.
  public static Path artifactDir(
      final Path root, final String groupId, final String artifactId, final String version) {
    return root.resolve(groupId).resolve(artifactId).resolve(version);
  }

  public static Path serversDir() {
    return userCacheRoot().resolve(SERVERS_DIR);
  }

  public static Path latestDir() {
    return userCacheRoot().resolve(LATEST_DIR);
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

  public static boolean isMcpSessionLog(final Path path) {
    final String name = path.getFileName().toString();
    return name.startsWith(MCP_LOG_PREFIX) && name.endsWith(LOG_SUFFIX);
  }

  public static String cacheName(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
  }

  public static Path reactorLockFile(final Path latheDir) {
    return latheDir.resolve(REACTOR_LOCK_FILE);
  }

  // .lathe/<module>/, relative to the workspace root ("" or "." for the root module).
  public static Path moduleDir(final String module) {
    return Path.of(LATHE_DIR, module);
  }

  public static Path writeLockFile(final Path latheDir) {
    return latheDir.resolve(WRITE_LOCK_FILE);
  }

  public static Path moduleLockFile(final Path moduleDir) {
    return moduleDir.resolve(MODULE_LOCK_FILE);
  }

  public static String paramsFileName(final String sourceTree) {
    return "%s%s%s".formatted(PARAMS_FILE_PREFIX, sourceTree, JSON_SUFFIX);
  }

  public static String compiledStampsFileName(final String sourceTree) {
    return "%s%s%s".formatted(STAMPS_FILE_PREFIX, sourceTree, JSON_SUFFIX);
  }

  // The main and test compile each mirror their annotation-processor output to a scope-specific dir
  // (like classes/test-classes) so the test compile does not clobber the main compile's sources.
  public static String generatedSourcesDir(final String sourceTree) {
    return TEST_CLASSES_DIR.equals(sourceTree) ? GENERATED_TEST_SOURCES : GENERATED_SOURCES;
  }

  public static boolean isParamsFile(final Path path) {
    final var name = path.getFileName().toString();
    return name.startsWith(PARAMS_FILE_PREFIX) && name.endsWith(JSON_SUFFIX);
  }

  // The source tree an lsp-params file describes: lsp-params-<tree>.json.
  public static String paramsSourceTree(final Path params) {
    final var name = params.getFileName().toString();
    return name.substring(PARAMS_FILE_PREFIX.length(), name.length() - JSON_SUFFIX.length());
  }
}
