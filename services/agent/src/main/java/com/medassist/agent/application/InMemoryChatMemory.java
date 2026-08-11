package com.medassist.agent.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local bounded memory for M3; production persistence is intentionally deferred. */
public final class InMemoryChatMemory implements ChatMemory {
  private final int maxMessages;
  private final int maxCharacters;
  private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();

  public InMemoryChatMemory(final int maxMessages, final int maxCharacters) {
    if (maxMessages < 2 || maxCharacters < 256) {
      throw new IllegalArgumentException("chat memory limits are too small");
    }
    this.maxMessages = maxMessages;
    this.maxCharacters = maxCharacters;
  }

  @Override
  public List<ChatMessage> read(final String conversationId) {
    final Conversation conversation = conversations.get(requireConversationId(conversationId));
    return conversation == null ? List.of() : conversation.read();
  }

  @Override
  public void append(final String conversationId, final ChatMessage message) {
    Objects.requireNonNull(message, "message");
    conversations
        .computeIfAbsent(requireConversationId(conversationId), ignored -> new Conversation())
        .append(message);
  }

  @Override
  public void clear(final String conversationId) {
    conversations.remove(requireConversationId(conversationId));
  }

  private String requireConversationId(final String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value.matches("[A-Za-z0-9._:-]+")) {
      throw new IllegalArgumentException("conversationId must be a bounded safe identifier");
    }
    return value;
  }

  private final class Conversation {
    private final Deque<ChatMessage> messages = new ArrayDeque<>();
    private int characters;

    private synchronized void append(final ChatMessage message) {
      messages.addLast(message);
      characters += message.content().length();
      while (messages.size() > maxMessages || characters > maxCharacters) {
        characters -= messages.removeFirst().content().length();
      }
    }

    private synchronized List<ChatMessage> read() {
      return List.copyOf(new ArrayList<>(messages));
    }
  }
}
