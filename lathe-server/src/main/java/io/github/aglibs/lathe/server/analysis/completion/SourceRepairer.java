package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

interface SourceRepairer {
  Optional<String> repair(String source, List<Diagnostic<? extends JavaFileObject>> diagnostics);
}
