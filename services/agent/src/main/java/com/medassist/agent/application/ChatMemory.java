package com.medassist.agent.application;

import java.util.List;

/** Bounded conversation memory; implementations must retain only safe message content. */
public interface ChatMemory {
  List<ChatMessage> read(String conversationId);

  void append(String conversationId, ChatMessage message);

  void clear(String conversationId);
}
