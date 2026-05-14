package io.github.aglibs.lathe.server;

import java.io.IOException;
import javax.tools.StandardJavaFileManager;

interface SourceCompiler {
  CompilationResult compile(String uri, String content, CompileMode mode) throws IOException;

  StandardJavaFileManager fileManager();
}
