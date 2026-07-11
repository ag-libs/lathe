package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Params of the {@code lathe/testOutput} notification: one streamed transcript line, tagged with
 * the run token the client minted so lines from concurrent runs route to the right output buffer.
 */
public record TestOutputParams(String token, TranscriptLine line) {

  public TestOutputParams {
    ValidCheck.check().notNull(token, "token").notNull(line, "line").validate();
  }
}
