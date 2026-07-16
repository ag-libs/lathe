package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * One line of a replay process's captured output, tagged with the stream it arrived on so the
 * client can render stdout and stderr distinctly. The two streams are drained from separate pipes
 * and merged in arrival order, so ordering is exact within a stream and best-effort across the two.
 *
 * <p>{@code COMMAND} is not process output: it carries the replay launch command, emitted once
 * before the process starts so the client can show what ran the tests. It is appended to the enum
 * so the {@code STDOUT}/{@code STDERR} ordinals the client relies on stay stable.
 */
public record TranscriptLine(Stream stream, String text) {

  public enum Stream {
    STDOUT,
    STDERR,
    COMMAND
  }

  public TranscriptLine {
    ValidCheck.check().notNull(stream, "stream").notNull(text, "text").validate();
  }
}
