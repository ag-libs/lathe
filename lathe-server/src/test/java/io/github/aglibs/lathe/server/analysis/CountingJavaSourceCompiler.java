package io.github.aglibs.lathe.server.analysis;

import java.util.EnumMap;
import java.util.Map;
import javax.tools.StandardJavaFileManager;

final class CountingJavaSourceCompiler implements JavaSourceCompiler {

  private final TempSourceCompiler delegate = new TempSourceCompiler();
  private final Map<CompileMode, Integer> counts = new EnumMap<>(CompileMode.class);

  @Override
  public StandardJavaFileManager fileManager() {
    return delegate.fileManager();
  }

  @Override
  public CompilerResult compile(final String uri, final String content, final CompileMode mode) {
    counts.merge(mode, 1, Integer::sum);
    return delegate.compile(uri, content, mode);
  }

  int count(final CompileMode mode) {
    return counts.getOrDefault(mode, 0);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
