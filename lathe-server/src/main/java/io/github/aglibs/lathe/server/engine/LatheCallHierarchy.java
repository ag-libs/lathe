package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/** One level of a call hierarchy: incoming callers or outgoing callees of a symbol. */
public record LatheCallHierarchy(
    boolean incoming, int total, boolean truncated, List<LatheCall> calls) {

  public LatheCallHierarchy {
    ValidCheck.check().notNull(calls, "calls").validate();
    calls = List.copyOf(calls);
  }
}
