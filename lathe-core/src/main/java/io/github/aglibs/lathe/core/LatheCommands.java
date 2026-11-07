package io.github.aglibs.lathe.core;

import java.util.List;

// The server's workspace/executeCommand names, the client protocol: lathe.<area>.<action>. The
// Neovim client keeps the same table in lua/lathe/commands.lua; renaming one is a protocol change
// (LatheFlags.LATHE_PROTOCOL).
public final class LatheCommands {

  public static final String RUN_TEST = "lathe.run.test";
  public static final String RUN_MAIN = "lathe.run.main";
  public static final String RUN_NAMED = "lathe.run.named";
  public static final String RUN_CANCEL = "lathe.run.cancel";
  public static final String DEBUG_TEST = "lathe.debug.test";
  public static final String DEBUG_MAIN = "lathe.debug.main";
  public static final String DEBUG_NAMED = "lathe.debug.named";
  public static final String RUN_CONFIGS_LIST = "lathe.runConfigs.list";
  public static final String RUN_CONFIGS_SAVE = "lathe.runConfigs.save";
  public static final String RUNNABLES_FILE = "lathe.runnables.file";
  public static final String RUNNABLES_DIR = "lathe.runnables.dir";
  public static final String TESTS_DISCOVER = "lathe.tests.discover";
  public static final String TESTS_MODULES = "lathe.tests.modules";
  public static final String TESTS_SOURCES = "lathe.tests.sources";
  public static final String TESTS_COUNTERPARTS = "lathe.tests.counterparts";
  public static final String RESOURCES_LIST = "lathe.resources.list";
  public static final String RESOURCES_OPEN = "lathe.resources.open";
  public static final String RESOURCES_REFRESH = "lathe.resources.refresh";
  public static final String WORKSPACE_MODULES = "lathe.workspace.modules";
  public static final String WORKSPACE_PACKAGES = "lathe.workspace.packages";
  public static final String WORKSPACE_CONTEXT = "lathe.workspace.context";
  public static final String TYPES_CREATE = "lathe.types.create";
  public static final String TYPES_HIERARCHY = "lathe.types.hierarchy";
  public static final String TYPES_INSTANTIATIONS = "lathe.types.instantiations";
  public static final String IMPORTS_MISSING = "lathe.imports.missing";
  public static final String IMPORTS_EDITS = "lathe.imports.edits";

  // Every command, as the server advertises them.
  public static final List<String> ALL =
      List.of(
          RUN_TEST,
          RUN_MAIN,
          RUN_NAMED,
          RUN_CANCEL,
          DEBUG_TEST,
          DEBUG_MAIN,
          DEBUG_NAMED,
          RUN_CONFIGS_LIST,
          RUN_CONFIGS_SAVE,
          RUNNABLES_FILE,
          RUNNABLES_DIR,
          TESTS_DISCOVER,
          TESTS_MODULES,
          TESTS_SOURCES,
          TESTS_COUNTERPARTS,
          RESOURCES_LIST,
          RESOURCES_OPEN,
          RESOURCES_REFRESH,
          WORKSPACE_MODULES,
          WORKSPACE_PACKAGES,
          WORKSPACE_CONTEXT,
          TYPES_CREATE,
          TYPES_HIERARCHY,
          TYPES_INSTANTIATIONS,
          IMPORTS_MISSING,
          IMPORTS_EDITS);

  private LatheCommands() {}
}
