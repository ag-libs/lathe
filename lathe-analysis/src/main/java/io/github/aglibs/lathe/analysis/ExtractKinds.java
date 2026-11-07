package io.github.aglibs.lathe.analysis;

import java.util.List;

// CodeActionKind sub-kinds for the extract refactors. Distinct sub-kinds let an editor bind each
// refactor to its own shortcut (an `only` filter that matches one kind). Advertised by the server
// capabilities and set on every action the extract providers emit, so both sides share one source
// of truth.
public final class ExtractKinds {

  public static final String VARIABLE_KIND = "refactor.extract.variable";
  public static final String CONSTANT_KIND = "refactor.extract.constant";
  public static final String FIELD_KIND = "refactor.extract.field";
  public static final String METHOD_KIND = "refactor.extract.method";
  public static final List<String> KINDS =
      List.of(VARIABLE_KIND, CONSTANT_KIND, FIELD_KIND, METHOD_KIND);

  private ExtractKinds() {}
}
