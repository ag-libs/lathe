package io.github.aglibs.lathe.core;

// The one OS check: whatever differs by OS asks here, never os.name itself.
public final class Platform {

  public static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

  private Platform() {}
}
