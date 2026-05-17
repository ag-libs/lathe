package io.github.aglibs.lathe.server.analysis;

import javax.tools.StandardJavaFileManager;

public interface SourceCompiler extends AutoCloseable {
  CompilationResult compile(String uri, String content, CompileMode mode);

  StandardJavaFileManager fileManager();

  @Override
  void close();
}
