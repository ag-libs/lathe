package io.github.aglibs.lathe.server.analysis;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import javax.lang.model.element.Element;

public final class JavadocLocator {

  private static final Logger LOG = Logger.getLogger(JavadocLocator.class.getName());

  private final SourceParser parser;

  public JavadocLocator(final SourceParser parser) {
    this.parser = parser;
  }

  public Optional<String> locate(
      final Element element, final Trees trees, final List<Path> sourceRoots) {
    if (element == null) {
      return Optional.empty();
    }

    final var samePath = trees.getPath(element);
    if (samePath != null) {
      LOG.fine(() -> "[javadoc] same-file %s".formatted(element));
      return Optional.ofNullable(trees.getDocComment(samePath));
    }

    return DefinitionLocator.findSourceFile(element, sourceRoots)
        .flatMap(
            file -> {
              LOG.fine(() -> "[javadoc] reactor %s → %s".formatted(element, file));
              return parser.parse(
                  file,
                  (parsedTrees, cu) -> {
                    final var path = SourceLocator.declarationPath(cu, element);
                    return path != null ? parsedTrees.getDocComment(path) : null;
                  });
            });
  }
}
