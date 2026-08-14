package com.medassist.agent.api;

import com.medassist.agent.api.dto.AgentRequest;
import com.medassist.agent.api.dto.AgentResponse;
import com.medassist.agent.application.AgentEntryService;
import com.medassist.common.context.AuthenticatedRequestContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
  private final AgentEntryService entryService;

  public AgentController(final AgentEntryService entryService) {
    this.entryService = entryService;
  }

  @PostMapping("/answer")
  public AgentResponse answer(@RequestBody final AgentRequest request) {
    return entryService.execute(request, AuthenticatedRequestContext.requireCurrent());
  }
}
