package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * Params of the {@code lathe/sync} notification: the server asks the client to run Maven for the
 * workspace (the server never runs Maven itself). {@code captureTests} selects {@code mvn test} —
 * which re-captures {@code test-launch.json} for neotest/test-run — over the lighter {@code mvn
 * process-test-classes}. {@code modules} are the reactor-relative {@code -pl} selectors for a
 * targeted build (a source-only change in a few modules); empty means a full-reactor build.
 */
public record LatheSyncParams(String workspaceRoot, boolean captureTests, List<String> modules) {

  public LatheSyncParams {
    ValidCheck.check().notNull(workspaceRoot, "workspaceRoot").validate();
    modules = modules != null ? List.copyOf(modules) : List.of();
  }
}
