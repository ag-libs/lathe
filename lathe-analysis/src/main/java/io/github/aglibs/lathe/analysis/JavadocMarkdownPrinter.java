package io.github.aglibs.lathe.analysis;

import com.sun.source.doctree.AttributeTree;
import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
import com.sun.source.doctree.InheritDocTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.doctree.SeeTree;
import com.sun.source.doctree.StartElementTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.doctree.ThrowsTree;
import com.sun.source.util.DocTreeScanner;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;

final class JavadocMarkdownPrinter extends DocTreeScanner<Void, Void> {

  // A method's doc comment with its parameter names as its source declares them: an @param tag
  // inherits by position, and a class-file method's own names are synthetic.
  record MethodDoc(List<String> paramNames, DocCommentTree tree) {

    MethodDoc {
      ValidCheck.check().notNull(paramNames, "paramNames").notNull(tree, "tree").validate();
      paramNames = List.copyOf(paramNames);
    }
  }

  private final StringBuilder sb = new StringBuilder();
  private boolean inPre;
  private boolean pendingParagraph;
  private int linkStart = -1;
  private String linkHref;
  private List<String> displayNames = List.of();
  private List<String> paramNames = List.of();
  private List<ExecutableElement> inherited = List.of();
  private Function<ExecutableElement, Optional<MethodDoc>> docOf = method -> Optional.empty();
  private DocTree currentTag;

  private JavadocMarkdownPrinter() {}

  static String format(final DocCommentTree tree) {
    return tree == null
        ? ""
        : formatMethod(
            new MethodDoc(List.of(), tree), List.of(), List.of(), method -> Optional.empty());
  }

  // Prints a method's doc. An {@inheritDoc} in it takes the matching part from the first of the
  // overridden methods, nearest first, whose doc has one; docOf finds a doc on demand, so only the
  // ancestors a lookup reaches are parsed. @param tags show displayNames, the hovered method's own
  // names, by position, so an inherited doc matches the signature above it.
  static String formatMethod(
      final MethodDoc doc,
      final List<String> displayNames,
      final List<ExecutableElement> inherited,
      final Function<ExecutableElement, Optional<MethodDoc>> docOf) {
    final var printer = new JavadocMarkdownPrinter();
    printer.displayNames = displayNames;
    printer.paramNames = doc.paramNames();
    printer.inherited = inherited;
    printer.docOf = docOf;
    printer.scan(doc.tree().getFullBody(), null);
    printer.formatBlockTags(doc.tree().getBlockTags());
    printer.endLink();
    return printer.sb.toString().strip();
  }

  static String mainDescription(final DocCommentTree tree) {
    if (tree == null) {
      return "";
    }

    final var printer = new JavadocMarkdownPrinter();
    printer.scan(tree.getFullBody(), null);
    return printer.sb.toString().strip();
  }

  static Map<String, String> paramDocs(final DocCommentTree tree) {
    final var result = new LinkedHashMap<String, String>();
    if (tree == null) {
      return result;
    }
    for (final DocTree tag : tree.getBlockTags()) {
      if (!(tag instanceof ParamTree p) || p.isTypeParameter()) {
        continue;
      }
      final var printer = new JavadocMarkdownPrinter();
      printer.scan(p.getDescription(), null);
      final var desc = printer.sb.toString().strip();
      if (!desc.isBlank()) {
        result.put(p.getName().toString(), desc);
      }
    }
    return result;
  }

  private void formatBlockTags(final List<? extends DocTree> tags) {
    for (final DocTree tag : tags) {
      if (tag instanceof final ParamTree p && !p.isTypeParameter()) {
        paragraph();
        append("**@param** `%s` ".formatted(displayName(p)));
        scanTag(tag, p.getDescription());
      } else if (tag instanceof final ReturnTree r) {
        paragraph();
        append("**@return** ");
        scanTag(tag, r.getDescription());
      } else if (tag instanceof final ThrowsTree t) {
        paragraph();
        append("**@throws** `%s` ".formatted(t.getExceptionName()));
        scanTag(tag, t.getDescription());
      } else if (tag instanceof final SeeTree s) {
        paragraph();
        append("**@see** ");
        scan(s.getReference(), null);
      } else if (tag instanceof final DeprecatedTree d) {
        paragraph();
        append("**@deprecated** ");
        scan(d.getBody(), null);
      }
    }
  }

  private String displayName(final ParamTree param) {
    final int index = paramNames.indexOf(param.getName().toString());
    return index >= 0 && index < displayNames.size()
        ? displayNames.get(index)
        : param.getName().toString();
  }

  private void scanTag(final DocTree tag, final List<? extends DocTree> description) {
    currentTag = tag;
    scan(description, null);
    currentTag = null;
  }

  // The source's own line breaks around a paragraph tag would otherwise stack up into several
  // blank lines; a break is held until the next text so any run of them yields one.
  private void paragraph() {
    endLink();
    if (inPre) {
      return;
    }

    while (!sb.isEmpty() && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
      sb.setLength(sb.length() - 1);
    }

    pendingParagraph = true;
  }

  private void append(final String text) {
    if (pendingParagraph && !sb.isEmpty()) {
      sb.append("\n\n");
    }

    pendingParagraph = false;
    sb.append(text);
  }

  @Override
  public Void visitText(final TextTree node, final Void unused) {
    final String body = pendingParagraph ? node.getBody().stripLeading() : node.getBody();
    if (!body.isEmpty()) {
      append(body);
    }

    return null;
  }

  @Override
  public Void visitLink(final LinkTree node, final Void unused) {
    if (!node.getLabel().isEmpty()) {
      scan(node.getLabel(), null);
    } else if (node.getKind() == DocTree.Kind.LINK) {
      append("`%s`".formatted(refSignature(node.getReference())));
    } else {
      append(refSignature(node.getReference()));
    }
    return null;
  }

  @Override
  public Void visitLiteral(final LiteralTree node, final Void unused) {
    // Inline text that wraps a source line keeps the line break and comment indent; only a <pre>
    // block's layout is meaningful.
    final String body = node.getBody().getBody();
    final String text = inPre ? body : body.strip().replaceAll("\\s+", " ");
    // A <pre> block is already fenced; backticks there would show literally.
    append(node.getKind() == DocTree.Kind.CODE && !inPre ? "`%s`".formatted(text) : text);
    return null;
  }

  @Override
  public Void visitReference(final ReferenceTree node, final Void unused) {
    append("`%s`".formatted(refSignature(node)));
    return null;
  }

  @Override
  public Void visitInheritDoc(final InheritDocTree node, final Void unused) {
    for (int i = 0; i < inherited.size(); i++) {
      final Optional<MethodDoc> ancestor = docOf.apply(inherited.get(i));
      if (ancestor.isEmpty()) {
        continue;
      }

      final Optional<DocTree> tag =
          currentTag == null ? Optional.empty() : matchingTag(ancestor.get());
      final List<? extends DocTree> body =
          currentTag == null
              ? ancestor.get().tree().getFullBody()
              : tag.map(JavadocMarkdownPrinter::description).orElse(List.of());
      if (!body.isEmpty()) {
        scanInherited(
            body, tag.orElse(null), ancestor.get(), inherited.subList(i + 1, inherited.size()));
        return null;
      }
    }

    return null;
  }

  private void scanInherited(
      final List<? extends DocTree> body,
      final DocTree tag,
      final MethodDoc ancestor,
      final List<ExecutableElement> rest) {
    final List<String> ownNames = paramNames;
    final List<ExecutableElement> ownInherited = inherited;
    final DocTree ownTag = currentTag;
    paramNames = ancestor.paramNames();
    inherited = rest;
    currentTag = tag;
    scan(body, null);
    paramNames = ownNames;
    inherited = ownInherited;
    currentTag = ownTag;
  }

  private Optional<DocTree> matchingTag(final MethodDoc ancestor) {
    return ancestor.tree().getBlockTags().stream()
        .filter(tag -> matches(tag, ancestor))
        .map(DocTree.class::cast)
        .findFirst();
  }

  private boolean matches(final DocTree tag, final MethodDoc ancestor) {
    if (currentTag instanceof final ParamTree own && tag instanceof final ParamTree theirs) {
      final int index = paramNames.indexOf(own.getName().toString());
      return !own.isTypeParameter()
          && !theirs.isTypeParameter()
          && index >= 0
          && index == ancestor.paramNames().indexOf(theirs.getName().toString());
    }

    if (currentTag instanceof final ThrowsTree own && tag instanceof final ThrowsTree theirs) {
      return simpleName(own.getExceptionName()).equals(simpleName(theirs.getExceptionName()));
    }

    return currentTag instanceof ReturnTree && tag instanceof ReturnTree;
  }

  private static List<? extends DocTree> description(final DocTree tag) {
    if (tag instanceof final ParamTree p) {
      return p.getDescription();
    }

    if (tag instanceof final ThrowsTree t) {
      return t.getDescription();
    }

    return ((ReturnTree) tag).getDescription();
  }

  private static String simpleName(final ReferenceTree ref) {
    final String signature = ref.getSignature();
    return signature.substring(signature.lastIndexOf('.') + 1);
  }

  @Override
  public Void visitStartElement(final StartElementTree node, final Void unused) {
    switch (node.getName().toString().toLowerCase()) {
      case "b", "strong" -> append("**");
      case "i", "em" -> append("*");
      case "code" -> append("`");
      case "pre" -> {
        append("\n```\n");
        inPre = true;
      }
      case "p" -> paragraph();
      case "br" -> append("  \n");
      case "li" -> append("- ");
      case "a" -> startLink(node);
      default -> {}
    }
    return null;
  }

  @Override
  public Void visitEndElement(final EndElementTree node, final Void unused) {
    switch (node.getName().toString().toLowerCase()) {
      case "b", "strong" -> append("**");
      case "i", "em" -> append("*");
      case "code" -> append("`");
      case "pre" -> {
        inPre = false;
        append("\n```");
      }
      case "p" -> paragraph();
      case "li" -> append("\n");
      case "a" -> endLink();
      default -> {}
    }
    return null;
  }

  // A {@docRoot} link has no target outside the generated docs, so only its label shows.
  private void startLink(final StartElementTree node) {
    final Optional<AttributeTree> href =
        node.getAttributes().stream()
            .filter(AttributeTree.class::isInstance)
            .map(AttributeTree.class::cast)
            .filter(attribute -> attribute.getName().contentEquals("href"))
            .filter(attribute -> attribute.getValue() != null)
            .filter(
                attribute ->
                    attribute.getValue().stream().noneMatch(JavadocMarkdownPrinter::isDocRoot))
            .findFirst();
    if (href.isEmpty()) {
      return;
    }

    endLink();
    append("[");
    linkStart = sb.length();
    linkHref = attributeValue(href.get());
  }

  private static boolean isDocRoot(final DocTree tree) {
    return tree.getKind() == DocTree.Kind.DOC_ROOT;
  }

  // The label is one line: a markdown link that wraps a source line breaks apart in some clients.
  // An unclosed <a> ends at the next paragraph or tag rather than swallowing the rest of the doc.
  private void endLink() {
    if (linkHref == null) {
      return;
    }

    final String label = sb.substring(linkStart).strip().replaceAll("\\s+", " ");
    sb.setLength(linkStart);
    sb.append(label.isEmpty() ? linkHref : label).append("](").append(linkHref).append(")");
    linkStart = -1;
    linkHref = null;
  }

  // Parentheses would end the markdown link target early.
  private static String attributeValue(final AttributeTree attribute) {
    return attribute.getValue().stream()
        .map(DocTree::toString)
        .collect(Collectors.joining())
        .replace("(", "%28")
        .replace(")", "%29");
  }

  @Override
  public Void visitEntity(final EntityTree node, final Void unused) {
    append(
        switch (node.getName().toString()) {
          case "lt" -> "<";
          case "gt" -> ">";
          case "amp" -> "&";
          case "quot" -> "\"";
          case "apos" -> "'";
          case "nbsp" -> " ";
          default -> "&%s;".formatted(node.getName());
        });
    return null;
  }

  // `Type#member` reads as `Type.member`; a member of the same type (`#member`) as `member`.
  private static String refSignature(final ReferenceTree ref) {
    final String signature = ref.getSignature();
    return signature.startsWith("#") ? signature.substring(1) : signature.replace('#', '.');
  }
}
