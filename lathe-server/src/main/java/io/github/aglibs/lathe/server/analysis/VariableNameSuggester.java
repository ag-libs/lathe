package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;

// Variable-name candidates shared by Extract Variable (takes the first) and declaration-name
// completion (offers all). The type arrives as an already-resolved simple name, so this stays free
// of javac type plumbing — each caller derives that name from its own context.
public final class VariableNameSuggester {

  private static final String DEFAULT_NAME = "value";

  private VariableNameSuggester() {}

  // Expression-derived names lead type-derived ones; never empty, so callers can take the first.
  public static List<String> suggest(
      final String typeSimpleName, final ExpressionTree expression, final Set<String> taken) {
    final List<String> candidates =
        Stream.concat(expressionNames(expression).stream(), typeNames(typeSimpleName).stream())
            .map(VariableNameSuggester::legalize)
            .distinct()
            .toList();
    return makeUnique(candidates.isEmpty() ? List.of(DEFAULT_NAME) : candidates, taken);
  }

  // A constant or enum member (Color.RED) is not a variable name; only a lowercase-led member read
  // (obj.config) is used, everything else falls through to the type.
  private static List<String> expressionNames(final ExpressionTree expression) {
    if (expression instanceof final MethodInvocationTree inv) {
      final String method = invocationName(inv);
      return method == null ? List.of() : List.of(stripAccessorPrefix(method));
    }

    if (expression instanceof final MemberSelectTree ms) {
      final String member = ms.getIdentifier().toString();
      if (!member.isEmpty() && Character.isLowerCase(member.charAt(0))) {
        return List.of(member);
      }
    }

    return List.of();
  }

  // ConnectionString -> connectionString, string.
  private static List<String> typeNames(final String typeSimpleName) {
    if (typeSimpleName == null || typeSimpleName.isEmpty()) {
      return List.of();
    }

    final String primary = Strings.decapitalize(typeSimpleName);
    final String lastWord = Strings.decapitalize(lastCamelWord(typeSimpleName));
    return primary.equals(lastWord) ? List.of(primary) : List.of(primary, lastWord);
  }

  private static String invocationName(final MethodInvocationTree inv) {
    final ExpressionTree select = inv.getMethodSelect();
    if (select instanceof final IdentifierTree id) {
      return id.getName().toString();
    }

    if (select instanceof final MemberSelectTree ms) {
      return ms.getIdentifier().toString();
    }

    return null;
  }

  // getFoo() -> foo, isReady() -> ready; only when a capitalized remainder follows the prefix.
  private static String stripAccessorPrefix(final String method) {
    return Stream.of("get", "is")
        .filter(prefix -> hasAccessorPrefix(method, prefix))
        .findFirst()
        .map(prefix -> Strings.decapitalize(method.substring(prefix.length())))
        .orElse(method);
  }

  private static boolean hasAccessorPrefix(final String method, final String prefix) {
    return method.length() > prefix.length()
        && method.startsWith(prefix)
        && Character.isUpperCase(method.charAt(prefix.length()));
  }

  // HttpClient -> Client, ConnectionString -> String.
  private static String lastCamelWord(final String name) {
    for (int i = name.length() - 1; i > 0; i--) {
      if (Character.isUpperCase(name.charAt(i)) && !Character.isUpperCase(name.charAt(i - 1))) {
        return name.substring(i);
      }
    }

    return name;
  }

  private static String legalize(final String base) {
    if (base.isEmpty() || !SourceVersion.isIdentifier(base) || SourceVersion.isKeyword(base)) {
      return DEFAULT_NAME;
    }

    return base;
  }

  private static List<String> makeUnique(final List<String> candidates, final Set<String> taken) {
    final var result = new ArrayList<String>();
    final var used = new HashSet<>(taken);
    for (final String candidate : candidates) {
      final String unique = uniquify(candidate, used);
      used.add(unique);
      result.add(unique);
    }

    return List.copyOf(result);
  }

  private static String uniquify(final String base, final Set<String> used) {
    if (!used.contains(base)) {
      return base;
    }

    // At most used.size() candidates can collide, so 1..size+1 always yields a free name.
    return IntStream.rangeClosed(1, used.size() + 1)
        .mapToObj(suffix -> base + suffix)
        .filter(candidate -> !used.contains(candidate))
        .findFirst()
        .orElseThrow();
  }
}
