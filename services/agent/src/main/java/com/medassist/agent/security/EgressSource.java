package com.medassist.agent.security;

public enum EgressSource {
  SYSTEM_PROMPT,
  USER_QUERY,
  RETRIEVED_CHUNK,
  TOOL_OUTPUT,
  HISTORY,
  UNKNOWN
}
