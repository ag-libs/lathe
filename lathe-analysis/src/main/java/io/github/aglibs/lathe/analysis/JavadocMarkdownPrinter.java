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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
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

  // A method's doc to print and what an {@inheritDoc} in it can reach: `doc` is the method's own
  // doc or, when it has none, the nearest overridden method's; `inherited` the overridden methods
  // after it, nearest first, whose docs a DocReader reads on demand, so only the ones a lookup
  // reaches are parsed; `displayNames` the method's own parameter names, which @param tags show by
  // position, so an inherited doc matches the signature it describes; `thrownNames` the simple
  // names of the exceptions its `throws` clause declares, whose @throws it may inherit.
  record DocChain(
      MethodDoc doc,
      List<String> displayNames,
      List<ExecutableElement> inherited,
      List<String> thrownNames) {

    DocChain {
      ValidCheck.check()
          .notNull(doc, "doc")
          .notNull(displayNames, "displayNames")
          .notNull(inherited, "inherited")
          .notNull(thrownNames, "thrownNames")
          .validate();
      displayNames = List.copyOf(displayNames);
      inherited = List.copyOf(inherited);
      thrownNames = List.copyOf(thrownNames);
    }
  }

  // What signature help shows of a doc: its main description, and the description of each
  // parameter it documents, by the parameter's position.
  record SignatureDocs(String description, Map<Integer, String> paramDocs) {

    SignatureDocs {
      ValidCheck.check()
          .notNull(description, "description")
          .notNull(paramDocs, "paramDocs")
          .validate();
      paramDocs = Map.copyOf(paramDocs);
    }
  }

  // How a deprecation reads: first, as the javadoc tool puts it, with the tag's text after it.
  static final String DEPRECATED = "**Deprecated.**";

  private final StringBuilder sb = new StringBuilder();
  private boolean inPre;
  private boolean pendingParagraph;
  private int linkStart = -1;
  private String linkHref;
  private List<String> displayNames = List.of();
  private List<String> paramNames = List.of();
  private List<ExecutableElement> inherited = List.of();
  private List<String> thrownNames = List.of();
  private Function<ExecutableElement, Optional<MethodDoc>> docOf = method -> Optional.empty();
  private DocTree currentTag;

  private JavadocMarkdownPrinter() {}

  static String format(final DocCommentTree tree) {
    return tree == null
        ? ""
        : formatMethod(
            new DocChain(new MethodDoc(List.of(), tree), List.of(), List.of(), List.of()),
            method -> Optional.empty());
  }

  // `docOf` reads an overridden method's doc on demand: a DocReader's, for one request.
  static String formatMethod(
      final DocChain chain, final Function<ExecutableElement, Optional<MethodDoc>> docOf) {
    final var printer = printing(chain, docOf);
    final DocCommentTree tree = chain.doc().tree();
    tree.getBlockTags().stream()
        .filter(DeprecatedTree.class::isInstance)
        .map(DeprecatedTree.class::cast)
        .findFirst()
        .ifPresent(printer::printDeprecation);
    printer.printBody(tree);
    printer.formatBlockTags(tree.getBlockTags());
    printer.endLink();
    return printer.sb.toString().strip();
  }

  private void printDeprecation(final DeprecatedTree tag) {
    append("%s ".formatted(DEPRECATED));
    scanTag(tag, tag.getBody());
    paragraph();
  }

  static SignatureDocs signatureDocs(
      final DocChain chain, final Function<ExecutableElement, Optional<MethodDoc>> docOf) {
    final var description = printing(chain, docOf);
    description.printBody(chain.doc().tree());
    description.endLink();
    final Map<Integer, String> paramDocs = new HashMap<>();
    final List<String> names = chain.doc().paramNames();
    for (final DocTree tag : chain.doc().tree().getBlockTags()) {
      if (!(tag instanceof final ParamTree param) || param.isTypeParameter()) {
        continue;
      }

      final int index = names.indexOf(param.getName().toString());
      if (index < 0 || index >= chain.displayNames().size() || paramDocs.containsKey(index)) {
        continue;
      }

      final String doc = printParam(chain, docOf, param);
      if (!doc.isBlank()) {
        paramDocs.put(index, doc);
      }
    }

    for (int index = 0; index < chain.displayNames().size(); index++) {
      if (!paramDocs.containsKey(index)) {
        final var printer = printing(chain, docOf);
        printer.nearest(printer.paramAt(index)).ifPresent(printer::printFound);
        printer.endLink();
        final String doc = printer.sb.toString().strip();
        if (!doc.isBlank()) {
          paramDocs.put(index, doc);
        }
      }
    }

    return new SignatureDocs(description.sb.toString().strip(), paramDocs);
  }

  private static String printParam(
      final DocChain chain,
      final Function<ExecutableElement, Optional<MethodDoc>> docOf,
      final ParamTree param) {
    final var printer = printing(chain, docOf);
    printer.scanTag(param, param.getDescription());
    printer.endLink();
    return printer.sb.toString().strip();
  }

  private static JavadocMarkdownPrinter printing(
      final DocChain chain, final Function<ExecutableElement, Optional<MethodDoc>> docOf) {
    final var printer = new JavadocMarkdownPrinter();
    printer.displayNames = chain.displayNames();
    printer.paramNames = chain.doc().paramNames();
    printer.inherited = chain.inherited();
    printer.thrownNames = chain.thrownNames();
    printer.docOf = docOf;
    return printer;
  }

  // In javadoc's order: parameters by position, the return value, exceptions, then references. A
  // part the doc leaves out comes from the nearest overridden doc that has it, as the javadoc tool
  // fills it in: a parameter's description, the return value's, and that of an exception the
  // method's `throws` clause declares.
  private void formatBlockTags(final List<? extends DocTree> tags) {
    final List<ParamTree> params =
        tags.stream()
            .filter(ParamTree.class::isInstance)
            .map(ParamTree.class::cast)
            .filter(param -> !param.isTypeParameter())
            .toList();
    params.forEach(
        param ->
            printTag(
                "**@param** `%s` ".formatted(displayName(param)), param, param.getDescription()));
    final Set<Integer> documented =
        params.stream()
            .map(param -> paramNames.indexOf(param.getName().toString()))
            .collect(Collectors.toUnmodifiableSet());
    for (int index = 0; index < displayNames.size(); index++) {
      if (!documented.contains(index)) {
        final String header = "**@param** `%s` ".formatted(displayNames.get(index));
        nearest(paramAt(index)).ifPresent(found -> printInheritedTag(header, found));
      }
    }

    final List<ReturnTree> returns =
        tags.stream().filter(ReturnTree.class::isInstance).map(ReturnTree.class::cast).toList();
    returns.forEach(tag -> printTag("**@return** ", tag, tag.getDescription()));
    if (returns.isEmpty()) {
      nearest((tag, doc) -> tag instanceof ReturnTree)
          .ifPresent(found -> printInheritedTag("**@return** ", found));
    }

    final List<ThrowsTree> throwsTags =
        tags.stream().filter(ThrowsTree.class::isInstance).map(ThrowsTree.class::cast).toList();
    throwsTags.forEach(
        tag ->
            printTag(
                "**@throws** `%s` ".formatted(tag.getExceptionName()), tag, tag.getDescription()));
    final Set<String> thrownDocumented =
        throwsTags.stream()
            .map(tag -> simpleName(tag.getExceptionName()))
            .collect(Collectors.toUnmodifiableSet());
    thrownNames.stream()
        .filter(name -> !thrownDocumented.contains(name))
        .forEach(
            name ->
                nearest(
                        (tag, doc) ->
                            tag instanceof final ThrowsTree thrown
                                && simpleName(thrown.getExceptionName()).equals(name))
                    .ifPresent(
                        found -> printInheritedTag("**@throws** `%s` ".formatted(name), found)));

    tags.stream()
        .filter(SeeTree.class::isInstance)
        .map(SeeTree.class::cast)
        .forEach(
            see -> {
              paragraph();
              append("**@see** ");
              scan(see.getReference(), null);
            });
  }

  private void printTag(
      final String header, final DocTree tag, final List<? extends DocTree> description) {
    paragraph();
    append(header);
    scanTag(tag, description);
  }

  private void printInheritedTag(final String header, final Found found) {
    paragraph();
    append(header);
    printFound(found);
  }

  // Picks an overridden doc's @param for the parameter at `index`: by position, as names vary.
  private BiPredicate<DocTree, MethodDoc> paramAt(final int index) {
    return (tag, doc) ->
        tag instanceof final ParamTree param
            && !param.isTypeParameter()
            && doc.paramNames().indexOf(param.getName().toString()) == index;
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
    if (currentTag == null) {
      printInheritedBody();
    } else {
      nearest(this::matches).ifPresent(this::printFound);
    }

    return null;
  }

  // The doc's main description or, when it has none, the nearest overridden doc's.
  private void printBody(final DocCommentTree tree) {
    if (tree.getFullBody().isEmpty()) {
      printInheritedBody();
    } else {
      scan(tree.getFullBody(), null);
    }
  }

  private void printInheritedBody() {
    for (int i = 0; i < inherited.size(); i++) {
      final Optional<MethodDoc> ancestor = docOf.apply(inherited.get(i));
      if (ancestor.isPresent() && !ancestor.get().tree().getFullBody().isEmpty()) {
        scanInherited(
            ancestor.get().tree().getFullBody(),
            null,
            ancestor.get(),
            inherited.subList(i + 1, inherited.size()));
        return;
      }
    }
  }

  // A block tag found in the nearest overridden doc that has it, and its place in the chain.
  private record Found(int position, MethodDoc doc, DocTree tag) {

    Found {
      ValidCheck.check().notNull(doc, "doc").notNull(tag, "tag").validate();
    }
  }

  // The first block tag `pick` accepts, with a description, in the nearest overridden doc that has
  // one; the docs are read in order and only as far as needed.
  private Optional<Found> nearest(final BiPredicate<DocTree, MethodDoc> pick) {
    for (int i = 0; i < inherited.size(); i++) {
      final Optional<MethodDoc> ancestor = docOf.apply(inherited.get(i));
      if (ancestor.isEmpty()) {
        continue;
      }

      final MethodDoc doc = ancestor.get();
      final Optional<DocTree> tag =
          doc.tree().getBlockTags().stream()
              .filter(candidate -> pick.test(candidate, doc))
              .filter(candidate -> !description(candidate).isEmpty())
              .map(DocTree.class::cast)
              .findFirst();
      if (tag.isPresent()) {
        return Optional.of(new Found(i, doc, tag.get()));
      }
    }

    return Optional.empty();
  }

  private void printFound(final Found found) {
    scanInherited(
        description(found.tag()),
        found.tag(),
        found.doc(),
        inherited.subList(found.position() + 1, inherited.size()));
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

    if (currentTag instanceof DeprecatedTree) {
      return tag instanceof DeprecatedTree;
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

    if (tag instanceof final DeprecatedTree d) {
      return d.getBody();
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
