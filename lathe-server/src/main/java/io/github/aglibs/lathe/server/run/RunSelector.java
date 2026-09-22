package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.launch.TestSelectionKind;

/** On-disk test selector, mapped by Gson; its field names match the {@code lathe.run.test} wire. */
record RunSelector(String selectorKind, String selectorValue) {

  TestSelection toSelection() {
    return new TestSelection(TestSelectionKind.valueOf(selectorKind), selectorValue);
  }

  static RunSelector from(final TestSelection selection) {
    return new RunSelector(selection.kind().name(), selection.value());
  }
}
