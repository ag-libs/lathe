package io.github.aglibs.lathe.server.analysis;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public interface JavaSourceCompiler extends AutoCloseable {
  JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

  static StandardJavaFileManager createFileManager() {
    return COMPILER.getStandardFileManager(null, null, null);
  }

  CompilerResult compile(String uri, String content, CompileMode mode);

  default AttributedFileAnalysis reattribute(final String uri, final String content) {
    return compile(uri, content, CompileMode.FAST).fileAnalysis();
  }

  StandardJavaFileManager fileManager();

  @Override
  void close();
}
