package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Params of the {@code lathe/sync} notification: the server asks the client to run Maven for the
 * workspace (the server never runs Maven itself). {@code captureTests} selects {@code mvn test} —
 * which re-captures {@code test-launch.json} for neotest/test-run — over the lighter {@code mvn
 * process-test-classes}.
 */
public record LatheSyncParams(String workspaceRoot, boolean captureTests) {

  public LatheSyncParams {
    ValidCheck.check().notNull(workspaceRoot, "workspaceRoot").validate();
  }
}
