package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.server.analysis.CompilationResult;
import java.io.IOException;
import javax.tools.StandardJavaFileManager;

public interface SourceCompiler {
  CompilationResult compile(String uri, String content, CompileMode mode) throws IOException;

  StandardJavaFileManager fileManager();
}
