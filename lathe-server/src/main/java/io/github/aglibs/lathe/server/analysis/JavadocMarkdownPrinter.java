package io.github.aglibs.lathe.server.analysis;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.EndElementTree;
import com.sun.source.doctree.EntityTree;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JavadocMarkdownPrinter extends DocTreeScanner<Void, Void> {

  private final StringBuilder sb = new StringBuilder();

  private JavadocMarkdownPrinter() {}

  static String format(final DocCommentTree tree) {
    if (tree == null) {
      return "";
    }

    final var printer = new JavadocMarkdownPrinter();
    printer.scan(tree.getFirstSentence(), null);
    printer.scan(tree.getBody(), null);
    printer.formatBlockTags(tree.getBlockTags());
    return printer.sb.toString().strip();
  }

  static String mainDescription(final DocCommentTree tree) {
    if (tree == null) {
      return "";
    }

    final var printer = new JavadocMarkdownPrinter();
    printer.scan(tree.getFirstSentence(), null);
    printer.scan(tree.getBody(), null);
    return printer.sb.toString().strip();
  }

  static Map<String, String> paramDocs(final DocCommentTree tree) {
    final Map<String, String> result = new LinkedHashMap<>();
    if (tree == null) {
      return result;
    }
    for (final DocTree tag : tree.getBlockTags()) {
      if (!(tag instanceof ParamTree p) || p.isTypeParameter()) {
        continue;
      }
      final var printer = new JavadocMarkdownPrinter();
      printer.scan(p.getDescription(), null);
      final String desc = printer.sb.toString().strip();
      if (!desc.isBlank()) {
        result.put(p.getName().toString(), desc);
      }
    }
    return result;
  }

  private void formatBlockTags(final List<? extends DocTree> tags) {
    for (final DocTree tag : tags) {
      switch (tag) {
        case ParamTree p when !p.isTypeParameter() -> {
          ensureSeparator();
          sb.append("**@param** `").append(p.getName()).append("` ");
          scan(p.getDescription(), null);
        }
        case ReturnTree r -> {
          ensureSeparator();
          sb.append("**@return** ");
          scan(r.getDescription(), null);
        }
        case ThrowsTree t -> {
          ensureSeparator();
          sb.append("**@throws** `").append(t.getExceptionName()).append("` ");
          scan(t.getDescription(), null);
        }
        case SeeTree s -> {
          ensureSeparator();
          sb.append("**@see** ");
          scan(s.getReference(), null);
        }
        default -> {}
      }
    }
  }

  private void ensureSeparator() {
    if (!sb.isEmpty()) {
      sb.append("\n\n");
    }
  }

  @Override
  public Void visitText(final TextTree node, final Void unused) {
    sb.append(node.getBody());
    return null;
  }

  @Override
  public Void visitLink(final LinkTree node, final Void unused) {
    if (!node.getLabel().isEmpty()) {
      scan(node.getLabel(), null);
    } else if (node.getKind() == DocTree.Kind.LINK) {
      sb.append("`").append(refSignature(node.getReference())).append("`");
    } else {
      sb.append(refSignature(node.getReference()));
    }
    return null;
  }

  @Override
  public Void visitLiteral(final LiteralTree node, final Void unused) {
    if (node.getKind() == DocTree.Kind.CODE) {
      sb.append("`").append(node.getBody().getBody()).append("`");
    } else {
      sb.append(node.getBody().getBody());
    }
    return null;
  }

  @Override
  public Void visitReference(final ReferenceTree node, final Void unused) {
    sb.append("`").append(refSignature(node)).append("`");
    return null;
  }

  @Override
  public Void visitStartElement(final StartElementTree node, final Void unused) {
    switch (node.getName().toString().toLowerCase()) {
      case "b", "strong" -> sb.append("**");
      case "i", "em" -> sb.append("*");
      case "code" -> sb.append("`");
      case "pre" -> sb.append("\n```\n");
      case "p" -> sb.append("\n\n");
      case "br" -> sb.append("  \n");
      case "li" -> sb.append("- ");
      default -> {}
    }
    return null;
  }

  @Override
  public Void visitEndElement(final EndElementTree node, final Void unused) {
    switch (node.getName().toString().toLowerCase()) {
      case "b", "strong" -> sb.append("**");
      case "i", "em" -> sb.append("*");
      case "code" -> sb.append("`");
      case "pre" -> sb.append("\n```");
      case "li" -> sb.append("\n");
      default -> {}
    }
    return null;
  }

  @Override
  public Void visitEntity(final EntityTree node, final Void unused) {
    sb.append(
        switch (node.getName().toString()) {
          case "lt" -> "<";
          case "gt" -> ">";
          case "amp" -> "&";
          case "quot" -> "\"";
          case "apos" -> "'";
          case "nbsp" -> " ";
          default -> "&" + node.getName() + ";";
        });
    return null;
  }

  private static String refSignature(final ReferenceTree ref) {
    return ref.getSignature().replace('#', '.');
  }
}
