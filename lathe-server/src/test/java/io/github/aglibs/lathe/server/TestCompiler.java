package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
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

  record CrossFileTask(JavacTask task, Trees trees, CompilationUnitTree cu, StandardJavaFileManager fm) {}

  private TestCompiler() {}

  static void compileToDir(final Path classDir, final Path... sources) throws IOException {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
    compiler.getTask(null, fm, null, null, null, fm.getJavaFileObjects(sources)).call();
  }

  static CrossFileTask parseWithClasspath(final Path src, final Path classDir) throws IOException {
    return parseWith(src, StandardLocation.CLASS_PATH, classDir);
  }

  private static CrossFileTask parseWith(
      final Path src, final StandardLocation location, final Path dir) throws IOException {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    final StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
    fm.setLocationFromPaths(location, List.of(dir));
    final JavacTask task =
        (JavacTask) compiler.getTask(null, fm, null, null, null, fm.getJavaFileObjects(src));
    final CompilationUnitTree cu = task.parse().iterator().next();
    task.analyze();
    return new CrossFileTask(task, Trees.instance(task), cu, fm);
  }
}
