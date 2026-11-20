package dev.qb2.perception;

import java.util.*;
import java.util.regex.Pattern;

public final class Keywords {
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+(?::[a-z0-9_./-]+)?");

  private Keywords() {}

  public static List<String> extract(String text) {
    var out = new LinkedHashSet<String>();
    var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (m.find() && out.size() < 1000) if (m.group().length() > 2) out.add(m.group());
    return List.copyOf(out);
  }
}
