package io.github.aglibs.lathe.server.analysis;

import java.io.IOException;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;

public interface SourceCompiler {
  CompilationResult compile(String uri, String content, CompileMode mode) throws IOException;

  JavaCompiler compiler();

  StandardJavaFileManager fileManager();
}
