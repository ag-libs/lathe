package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class CallHierarchyOutgoingLocator extends SourceTreeLocator {

  private final Map<Element, List<Range>> callSites = new LinkedHashMap<>();

  private CallHierarchyOutgoingLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements) {
    super(trees, cu, content, target, types, elements);
  }

  static List<CallHierarchyOutgoingCall> scan(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final List<Path> sourceRoots,
      final DefinitionLocator definitionLocator)
      throws IOException {
    final var content = sourceContent(analysis);
    final var locator =
        new CallHierarchyOutgoingLocator(
            analysis.trees(),
            analysis.tree(),
            content,
            target,
            analysis.types(),
            analysis.elements());
    locator.scan(analysis.tree(), null);
    return locator.buildResults(sourceRoots, definitionLocator);
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    if (target.matches(element, types, elements) && node.getBody() != null) {
      scan(node.getBody(), null);
    }
    return null;
  }

  @Override
  public Void visitMethodInvocation(final MethodInvocationTree node, final Void ignored) {
    final var element = SourceLocator.elementAt(trees, getCurrentPath());
    if (element instanceof ExecutableElement) {
      final var sel = node.getMethodSelect();
      if (sel instanceof final MemberSelectTree mst) {
        final String name = mst.getIdentifier().toString();
        final long startOff =
            SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, mst), name);
        if (startOff >= 0) {
          addCallSite(element, rangeFor(startOff, name));
        }
      } else if (sel instanceof final IdentifierTree it) {
        addCallSite(element, rangeFor(positions.getStartPosition(cu, it), it.getName().toString()));
      }
    }
    return super.visitMethodInvocation(node, ignored);
  }

  @Override
  public Void visitNewClass(final NewClassTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    if (element instanceof ExecutableElement
        && !element.getEnclosingElement().getSimpleName().isEmpty()) {
      final var id = node.getIdentifier();
      final String name =
          id instanceof final MemberSelectTree mst
              ? mst.getIdentifier().toString()
              : id instanceof final IdentifierTree it ? it.getName().toString() : null;
      if (name != null) {
        final long startOff =
            SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, id), name);
        if (startOff >= 0) {
          addCallSite(element, rangeFor(startOff, name));
        }
      }
    }
    return super.visitNewClass(node, ignored);
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void ignored) {
    scan(node.getQualifierExpression(), null);
    final var element = trees.getElement(getCurrentPath());
    if (element instanceof ExecutableElement && !element.getSimpleName().contentEquals("<init>")) {
      final String name = node.getName().toString();
      final long startOff =
          SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
      if (startOff >= 0) {
        addCallSite(element, rangeFor(startOff, name));
      }
    }
    return null;
  }

  private void addCallSite(final Element element, final Range range) {
    callSites.computeIfAbsent(element, k -> new ArrayList<>()).add(range);
  }

  private Range rangeFor(final long startOff, final String name) {
    final var start = SourceLocator.offsetToPosition(cu, startOff);
    return new Range(start, new Position(start.getLine(), start.getCharacter() + name.length()));
  }

  private List<CallHierarchyOutgoingCall> buildResults(
      final List<Path> sourceRoots, final DefinitionLocator definitionLocator) {
    final var results = new ArrayList<CallHierarchyOutgoingCall>();
    for (final var entry : callSites.entrySet()) {
      final var calleeElement = entry.getKey();
      final var ranges = entry.getValue();
      DefinitionLocator.findSourceFile(calleeElement, sourceRoots)
          .ifPresent(
              file -> {
                final var pos = definitionLocator.parsePosition(file, calleeElement);
                final var pointRange = new Range(pos, pos);
                final var uri = file.toUri().toString();
                final var calleeItem =
                    CallHierarchyItemDataCodec.buildItem(
                        calleeElement, uri, pointRange, pointRange, types, elements);
                results.add(new CallHierarchyOutgoingCall(calleeItem, List.copyOf(ranges)));
              });
    }
    return List.copyOf(results);
  }
}
