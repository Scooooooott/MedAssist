package com.medassist.retrieval.application.generation;

/** Extracts decoded characters from the top-level JSON {@code answer} string as bytes arrive. */
final class JsonAnswerDeltaExtractor {
  private enum State {
    SEARCH_KEY,
    AFTER_ANSWER_KEY,
    BEFORE_ANSWER_VALUE,
    IN_ANSWER_VALUE,
    DONE
  }

  private State state = State.SEARCH_KEY;
  private final StringBuilder key = new StringBuilder();
  private boolean inKey;
  private boolean keyEscape;
  private boolean expectingKey;
  private int depth;
  private boolean answerEscape;
  private int unicodeDigits;
  private int unicodeValue;

  String feed(final String fragment) {
    final StringBuilder delta = new StringBuilder();
    for (int index = 0; index < fragment.length(); index++) {
      final char character = fragment.charAt(index);
      switch (state) {
        case SEARCH_KEY -> searchForKey(character);
        case AFTER_ANSWER_KEY -> afterAnswerKey(character);
        case BEFORE_ANSWER_VALUE -> beforeAnswerValue(character);
        case IN_ANSWER_VALUE -> readAnswerCharacter(character, delta);
        case DONE -> {
          // The remaining JSON contains citations and metadata, not user-visible answer text.
        }
      }
    }
    return delta.toString();
  }

  private void searchForKey(final char character) {
    if (inKey) {
      if (keyEscape) {
        key.append(character);
        keyEscape = false;
      } else if (character == '\\') {
        keyEscape = true;
      } else if (character == '"') {
        inKey = false;
        if ("answer".contentEquals(key)) {
          state = State.AFTER_ANSWER_KEY;
        }
      } else {
        key.append(character);
      }
      return;
    }
    if (character == '{') {
      depth++;
      expectingKey = depth == 1;
    } else if (character == '}') {
      depth--;
    } else if (character == ',' && depth == 1) {
      expectingKey = true;
    } else if (character == '"' && depth == 1 && expectingKey) {
      key.setLength(0);
      inKey = true;
      expectingKey = false;
    }
  }

  private void afterAnswerKey(final char character) {
    if (Character.isWhitespace(character)) {
      return;
    }
    if (character != ':') {
      throw new AnswerGenerationException();
    }
    state = State.BEFORE_ANSWER_VALUE;
  }

  private void beforeAnswerValue(final char character) {
    if (Character.isWhitespace(character)) {
      return;
    }
    if (character != '"') {
      throw new AnswerGenerationException();
    }
    state = State.IN_ANSWER_VALUE;
  }

  private void readAnswerCharacter(final char character, final StringBuilder delta) {
    if (unicodeDigits > 0) {
      final int digit = Character.digit(character, 16);
      if (digit < 0) {
        throw new AnswerGenerationException();
      }
      unicodeValue = unicodeValue * 16 + digit;
      unicodeDigits--;
      if (unicodeDigits == 0) {
        delta.append((char) unicodeValue);
        unicodeValue = 0;
      }
      return;
    }
    if (answerEscape) {
      answerEscape = false;
      switch (character) {
        case '"', '\\', '/' -> delta.append(character);
        case 'b' -> delta.append('\b');
        case 'f' -> delta.append('\f');
        case 'n' -> delta.append('\n');
        case 'r' -> delta.append('\r');
        case 't' -> delta.append('\t');
        case 'u' -> unicodeDigits = 4;
        default -> throw new AnswerGenerationException();
      }
      return;
    }
    if (character == '\\') {
      answerEscape = true;
    } else if (character == '"') {
      state = State.DONE;
    } else {
      delta.append(character);
    }
  }
}
