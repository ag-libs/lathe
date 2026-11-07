package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.function.Function;

// A read tool's answer with the notes on what it cannot vouch for, read in the same request, so
// the two describe one state of the workspace. The value is the tool's own answer; mapped after the
// request, off the event loop, as building it reads the source files.
public record Noted<T>(T value, LatheNotes notes) {

  // A raw answer may be null (no hover), so only the notes are checked.
  public Noted {
    ValidCheck.check().notNull(notes, "notes").validate();
  }

  public <U> Noted<U> map(final Function<T, U> mapper) {
    return new Noted<>(mapper.apply(value), notes);
  }
}
