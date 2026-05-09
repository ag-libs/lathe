package io.github.aglibs.lathe.server;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

final class TestCompiler {

  record CrossFileTask(JavacTask task, Trees trees) {}

  private TestCompiler() {}

  static void compileToDir(final Path src, final Path classDir) throws IOException {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
    compiler.getTask(null, fm, null, null, null, fm.getJavaFileObjects(src)).call();
  }

  static CrossFileTask parseWithClasspath(final Path src, final Path classDir) throws IOException {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
    fm.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(classDir));
    final JavacTask task =
        (JavacTask) compiler.getTask(null, fm, null, null, null, fm.getJavaFileObjects(src));
    task.parse();
    task.analyze();
    return new CrossFileTask(task, Trees.instance(task));
  }
}
