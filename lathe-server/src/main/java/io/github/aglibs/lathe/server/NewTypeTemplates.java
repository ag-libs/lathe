package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.server.analysis.RenameProvider;
import java.nio.file.Path;
import javax.lang.model.SourceVersion;
import org.eclipse.lsp4j.Position;

// The `:LatheNew` skeletons: where each kind of new file goes and what it starts with.
final class NewTypeTemplates {

  private NewTypeTemplates() {}

  // The skeleton is left unformatted: an empty type has nothing to format, and running the
  // formatter
  // would only collapse the body and remove the caret line — the user's on-save formatter
  // canonicalises it on first save.
  static CreateTypeResult render(
      final Path sourceRoot, final String pkg, final TypeKind type, final String name) {
    return switch (type) {
      case CLASS, INTERFACE, ENUM, RECORD, TEST -> renderType(sourceRoot, pkg, type, name);
      case PACKAGE_INFO -> renderPackageInfo(sourceRoot, pkg);
      case MODULE_INFO -> renderModuleInfo(sourceRoot, name);
    };
  }

  private static CreateTypeResult renderType(
      final Path sourceRoot, final String pkg, final TypeKind type, final String name) {
    if (!RenameProvider.isValidTypeName(name)) {
      throw new IllegalArgumentException("'%s' is not a valid Java type name".formatted(name));
    }

    final var path = sourceRoot.resolve(pkg.replace('.', '/')).resolve(name + ".java");
    return new CreateTypeResult(
        path.toString(), newTypeSource(type, name, pkg), caretFor(type, name, pkg));
  }

  // The default package cannot carry a package-info, so an empty package is rejected.
  private static CreateTypeResult renderPackageInfo(final Path sourceRoot, final String pkg) {
    if (pkg.isEmpty()) {
      throw new IllegalArgumentException("package-info requires a package");
    }

    final var path = sourceRoot.resolve(pkg.replace('.', '/')).resolve("package-info.java");
    final var content = "/**\n * \n */\npackage %s;\n".formatted(pkg);
    return new CreateTypeResult(path.toString(), content, new Position(1, 3));
  }

  // The module name is a qualified name, not a simple type identifier, so it is validated with
  // isName.
  private static CreateTypeResult renderModuleInfo(final Path sourceRoot, final String name) {
    if (!SourceVersion.isName(name)) {
      throw new IllegalArgumentException("'%s' is not a valid module name".formatted(name));
    }

    final var path = sourceRoot.resolve("module-info.java");
    return new CreateTypeResult(
        path.toString(), "module %s {\n\n}\n".formatted(name), new Position(1, 0));
  }

  private static String newTypeSource(final TypeKind type, final String name, final String pkg) {
    final var header = pkg.isEmpty() ? "" : "package %s;\n\n".formatted(pkg);
    return switch (type) {
      case RECORD -> "%spublic record %s() {\n}\n".formatted(header, name);
      case CLASS, INTERFACE, ENUM ->
          "%spublic %s %s {\n\n}\n".formatted(header, type.keyword, name);
      // A JUnit 5 test class: package-private (the Jupiter idiom, matching the codebase), with one
      // empty @Test method for the caret to land in. The import follows the package header.
      case TEST ->
          "%simport org.junit.jupiter.api.Test;\n\nclass %s {\n\n  @Test\n  void name() {\n\n  }\n}\n"
              .formatted(header, name);
      case PACKAGE_INFO, MODULE_INFO ->
          throw new IllegalStateException("special unit handled by render: " + type);
    };
  }

  // Computed from the template's known shape, not by scanning it: a package header adds two leading
  // lines; a record's caret is inside the () on the declaration line, the others' on the empty body
  // line. Mirrors the newTypeSource layout above.
  private static Position caretFor(final TypeKind type, final String name, final String pkg) {
    final int declLine = pkg.isEmpty() ? 0 : 2;
    if (type == TypeKind.RECORD) {
      return new Position(declLine, "public record %s(".formatted(name).length());
    }

    // The test skeleton's caret sits on the empty @Test method body line: six lines below the
    // import (import, blank, class, blank, @Test, void), which itself is at declLine.
    if (type == TypeKind.TEST) {
      return new Position(declLine + 6, 0);
    }

    return new Position(declLine + 1, 0);
  }
}
