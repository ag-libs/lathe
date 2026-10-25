package io.github.aglibs.lathe.install.typeindex;

import io.github.aglibs.lathe.core.LatheFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractCachePropertyTest {

  private String previousCache;

  @BeforeEach
  protected void rememberCacheOverride() {
    previousCache = System.getProperty(LatheFlags.CACHE_ROOT);
  }

  @AfterEach
  protected void restoreCacheOverride() {
    if (previousCache == null) {
      System.clearProperty(LatheFlags.CACHE_ROOT);
      return;
    }

    System.setProperty(LatheFlags.CACHE_ROOT, previousCache);
  }
}
