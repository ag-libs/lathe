package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * A reactor module, the reactor modules on its class or module path, and whether it has tests and a
 * captured test launch.
 */
public record LatheModuleSummary(
    String module, List<String> dependsOn, boolean hasTests, boolean testsCaptured) {
  public LatheModuleSummary {
    ValidCheck.check().notNull(module, "module").notNull(dependsOn, "dependsOn").validate();
    dependsOn = List.copyOf(dependsOn);
  }
}
