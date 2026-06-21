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
  public static final String SCHEMA_VERSION = "2";
  public static final String WORKSPACE_JSON = "workspace.json";
  public static final String LOCK_FILE = "lathe.lock";
  public static final String GENERATED_SOURCES = "generated-sources";
  public static final String PARAMS_FILE_PREFIX = "lsp-params-";

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

  public static boolean isParamsFile(final Path path) {
    final String name = path.getFileName().toString();
    return name.startsWith(PARAMS_FILE_PREFIX) && name.endsWith(".json");
  }
}
