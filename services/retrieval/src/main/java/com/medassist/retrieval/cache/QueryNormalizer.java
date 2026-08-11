package com.medassist.retrieval.cache;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class QueryNormalizer {
  private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  public String normalize(final String query) {
    if (query == null) {
      return "";
    }
    final String compatible = Normalizer.normalize(query, Normalizer.Form.NFKC);
    final String withoutPunctuation = PUNCTUATION.matcher(compatible).replaceAll(" ");
    return WHITESPACE.matcher(withoutPunctuation.toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
  }
}
