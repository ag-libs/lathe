package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * A named config's selection summary, sent to the client for {@code :LatheRun}/{@code :LatheDebug}
 * completion. {@code target} is the main class or test selector, {@code summary} a short overlay
 * hint (key jvmArgs).
 */
public record RunConfigInfo(
    String name, String kind, String module, String target, String summary) {

  public RunConfigInfo {
    ValidCheck.check().notBlank(name, "name").notBlank(kind, "kind").validate();
  }
}
