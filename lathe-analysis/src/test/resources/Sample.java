import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

@SuppressWarnings("unused")
public class Sample {

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Logged {
    String value() default "";
  }

  enum Status {
    ACTIVE,
    INACTIVE
  }

  private static final String PREFIX = "item-";

  private final String name;
  private int count;

  public Sample(String name) {
    this.name = name;
    this.count = 0;
  }

  public String getName() {
    return name;
  }

  public void increment() {
    count++;
  }

  public int getCount() {
    return count;
  }

  public String run(final String value) {
    Objects.requireNonNull(value);
    return format("result: %s", value);
  }

  public String localized() {
    return "hello".toUpperCase(ENGLISH);
  }

  public List<String> upper(List<String> items) {
    return items.stream().map(s -> s.toUpperCase()).toList();
  }

  public String overloaded(final String s) {
    return s;
  }

  public int overloaded(final int n) {
    return n;
  }

  public <T> T identity(final T value) {
    return value;
  }

  public void useOverloads() {
    overloaded("hello");
    overloaded(42);
    identity("text");
  }

  @Logged("summarize")
  public Map<String, Long> summarize(List<String> items) {
    return items.stream()
        .filter(s -> s.startsWith(PREFIX))
        .map(String::toUpperCase)
        .collect(Collectors.groupingBy(s -> s.substring(0, 1), Collectors.counting()));
  }

  public static String staticHelper(String input) {
    var trimmed = input.strip();
    final String upper = trimmed.toUpperCase(Locale.ROOT);
    return PREFIX + upper;
  }

  public Optional<String> findFirst(List<String> items, String prefix) {
    return items.stream()
        .filter(s -> s.startsWith(prefix))
        .map(s -> s.substring(prefix.length()))
        .findFirst();
  }

  public Status getStatus() {
    return Status.ACTIVE;
  }

  @Deprecated
  public String oldFormat(String value) {
    return format("old: %s", value);
  }

  public String useDeprecated(String value) {
    return oldFormat(value);
  }

  /** Utility for hover Javadoc tests. */
  public static class DocHelper {

    /** The maximum number of items allowed. */
    public static final int MAX = 100;

    /**
     * Returns a greeting message.
     *
     * @return the greeting
     */
    public static String greet() {
      return "Hello";
    }

    /**
     * Repeats the given value the specified number of times.
     *
     * @param <T> the value type
     * @param value the value to repeat
     * @param count how many times to repeat it
     * @return a list of repeated values
     */
    public static <T> List<T> repeat(final T value, final int count) {
      return java.util.Collections.nCopies(count, value);
    }

    public void exercises() {
      var instance = new DocHelper();
      var max = MAX;
      var greeting = greet();
      var items = repeat("hi", 3);
      var upper = repeat("hi", 3).stream().map(s -> greet()).toList();
    }
  }
}
