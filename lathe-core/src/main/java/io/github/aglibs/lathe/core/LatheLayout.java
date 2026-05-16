package io.github.aglibs.lathe.core;

import java.nio.file.Path;

public final class LatheLayout {

  public static final String LATHE_DIR = ".lathe";
  public static final String CACHE_DIR = ".cache";
  public static final String CACHE_LATHE_DIR = "lathe";
  public static final String CACHE_DEPS_DIR = "deps";
  public static final String CACHE_JDKS_DIR = "jdks";
  public static final String SERVERS_DIR = "servers";
  public static final String CURRENT_LINK = "current";
  public static final String LAUNCHER_SCRIPT = "lathe-launcher.sh";
  public static final String SCHEMA_VERSION = "1";
  public static final String WORKSPACE_JSON = "workspace.json";
  public static final String LOCK_FILE = "lathe.lock";
  public static final String GENERATED_SOURCES = "generated-sources";

  private LatheLayout() {}

  public static Path userCacheRoot() {
    return Path.of(System.getProperty("user.home"), CACHE_DIR, CACHE_LATHE_DIR);
  }

  public static Path serverVersionDir(final String version) {
    return userCacheRoot().resolve(SERVERS_DIR).resolve(version);
  }

  public static Path currentLink() {
    return userCacheRoot().resolve(CURRENT_LINK);
  }
}
