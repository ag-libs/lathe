package io.github.aglibs.lathe.core;

import java.nio.file.Path;

public final class LatheLayout {

  public static final String LATHE_DIR = ".lathe";
  public static final String CACHE_DIR = ".cache";
  public static final String CACHE_LATHE_DIR = "lathe";
  public static final String CACHE_DEPS_DIR = "deps";
  public static final String CACHE_JDKS_DIR = "jdks";
  public static final String SCHEMA_VERSION = "1";
  public static final String ROOT_MARKER = "root.marker";
  public static final String WORKSPACE_PROPERTIES = "workspace.properties";
  public static final String LOCK_FILE = "lathe.lock";
  public static final String GENERATED_SOURCES = "generated-sources";

  private LatheLayout() {}

  public static Path userCacheRoot() {
    return Path.of(System.getProperty("user.home"), CACHE_DIR, CACHE_LATHE_DIR);
  }
}
