package io.github.aglibs.lathe.install.typeindex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractCachePropertyTest {

  private String previousCache;

  @BeforeEach
  protected void rememberCacheOverride() {
    previousCache = System.getProperty("lathe.cache");
  }

  @AfterEach
  protected void restoreCacheOverride() {
    if (previousCache == null) {
      System.clearProperty("lathe.cache");
      return;
    }

    System.setProperty("lathe.cache", previousCache);
  }
}
