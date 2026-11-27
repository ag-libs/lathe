package io.github.aglibs.lathe.analysis.completion;

// What a simple name's slot can hold, which decides the keywords offered there.
enum KeywordSlot {
  // A statement of a block or a colon case: the keywords its scope allows.
  BLOCK_STATEMENT,
  // An expression's value: the value keywords.
  VALUE,
  // A switch expression's arrow body: a value, or a `throw` (JLS 15.28.1).
  SWITCH_RULE_VALUE,
  // A switch statement's arrow body: an expression statement, or a `throw` (JLS 14.11.1); a block
  // starts with `{`, no keyword.
  SWITCH_RULE_STATEMENT,
  // A `for` loop's update: expression statements only (JLS 14.14.1).
  EXPRESSION_STATEMENT
}
