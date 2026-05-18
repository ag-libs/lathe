package io.github.aglibs.lathe.server.analysis;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public interface SourceCompiler extends AutoCloseable {
  JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

  static StandardJavaFileManager createFileManager() {
    return COMPILER.getStandardFileManager(null, null, null);
  }

  CompilationResult compile(String uri, String content, CompileMode mode);

  StandardJavaFileManager fileManager();

  @Override
  void close();
}
