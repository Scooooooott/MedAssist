package com.medassist.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryChatMemoryTest {
  @Test
  void retainsOnlyTheNewestMessagesAndReturnsAnImmutableSnapshot() {
    final InMemoryChatMemory memory = new InMemoryChatMemory(2, 256);
    memory.append("conversation-1", new ChatMessage("user", "first"));
    memory.append("conversation-1", new ChatMessage("assistant", "second"));
    memory.append("conversation-1", new ChatMessage("user", "third"));

    final List<ChatMessage> messages = memory.read("conversation-1");

    assertEquals(
        List.of(new ChatMessage("assistant", "second"), new ChatMessage("user", "third")),
        messages);
    assertThrows(
        UnsupportedOperationException.class, () -> messages.add(new ChatMessage("user", "fourth")));
  }

  @Test
  void isolatesConversationsAndRejectsUnsafeIdentifiers() {
    final InMemoryChatMemory memory = new InMemoryChatMemory(4, 256);
    memory.append("conversation-1", new ChatMessage("user", "safe"));

    assertTrue(memory.read("conversation-2").isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> memory.append("conversation/with/path", new ChatMessage("user", "unsafe")));
  }
}
