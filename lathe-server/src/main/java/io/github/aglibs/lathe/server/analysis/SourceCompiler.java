package io.github.aglibs.lathe.server.analysis;

import java.io.IOException;
import javax.tools.StandardJavaFileManager;

public interface SourceCompiler extends AutoCloseable {
  CompilationResult compile(String uri, String content, CompileMode mode) throws IOException;

  StandardJavaFileManager fileManager();

  @Override
  void close();
}
