package io.github.aglibs.lathe.maven.typeindex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class AbstractCachePropertyTest {

  private String previousCache;

  @BeforeEach
  void rememberCacheOverride() {
    previousCache = System.getProperty("lathe.cache");
  }

  @AfterEach
  void restoreCacheOverride() {
    if (previousCache == null) {
      System.clearProperty("lathe.cache");
      return;
    }

    System.setProperty("lathe.cache", previousCache);
  }
}
