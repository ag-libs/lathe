package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * Where the JavaBeans property name of a field or accessor that no Java code uses is still text: a
 * template's {@code ${owner.city}}, a framework that reads accessors by name, a test's {@code
 * hasProperty("city")}. Javac resolves none of them, so they are leads to check, not uses. The
 * property is empty for a symbol that is no field or accessor, or that Java code uses; the list is
 * capped, the total is of all.
 */
public record LathePropertyMentions(String property, List<LatheLocation> mentions, int total) {

  public static final LathePropertyMentions NONE = new LathePropertyMentions("", List.of(), 0);

  public LathePropertyMentions {
    ValidCheck.check()
        .notNull(property, "property")
        .notNull(mentions, "mentions")
        .assertTrue(total >= mentions.size(), "total")
        .validate();
    mentions = List.copyOf(mentions);
  }
}
