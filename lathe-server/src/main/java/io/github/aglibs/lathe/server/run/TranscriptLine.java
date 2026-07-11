package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * One line of a replay process's captured output, tagged with the stream it arrived on so the
 * client can render stdout and stderr distinctly. The two streams are drained from separate pipes
 * and merged in arrival order, so ordering is exact within a stream and best-effort across the two.
 */
public record TranscriptLine(Stream stream, String text) {

  public enum Stream {
    STDOUT,
    STDERR
  }

  public TranscriptLine {
    ValidCheck.check().notNull(stream, "stream").notNull(text, "text").validate();
  }
}
