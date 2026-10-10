package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// A [start, end) span of a document, in character offsets.
record TextRange(int start, int end) {

  TextRange {
    ValidCheck.check().assertTrue(start >= 0 && end >= start, "range").validate();
  }
}
