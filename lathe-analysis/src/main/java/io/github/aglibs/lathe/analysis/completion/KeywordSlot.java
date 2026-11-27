package io.github.aglibs.lathe.analysis.completion;

// What a simple name's slot can hold, which decides the keywords offered there.
enum KeywordSlot {
  // A statement of a block or a colon case: the keywords its scope allows.
  BLOCK_STATEMENT,
  // An expression's value: the value keywords.
  VALUE
}
