package com.medassist.agent.config;

import com.medassist.agent.application.ChatMemory;
import com.medassist.agent.application.InMemoryChatMemory;
import com.medassist.agent.application.QueryDeidentifier;
import com.medassist.agent.application.RejectingQueryDeidentifier;
import com.medassist.agent.checkpoint.CheckpointStore;
import com.medassist.agent.checkpoint.InMemoryCheckpointStore;
import com.medassist.agent.execution.AgentExecutionEngine;
import com.medassist.agent.execution.AgentExecutionLimits;
import com.medassist.agent.execution.AgentRouter;
import com.medassist.agent.execution.AgentToolExecutor;
import com.medassist.agent.execution.DefaultAgentExecutionEngine;
import com.medassist.agent.execution.DefaultAgentRouter;
import com.medassist.agent.execution.DefaultAgentToolExecutor;
import com.medassist.agent.execution.DraftGenerator;
import com.medassist.agent.execution.DraftVerifier;
import com.medassist.agent.execution.LlmDraftGenerator;
import com.medassist.agent.execution.StructuredDraftVerifier;
import com.medassist.agent.execution.ToolBackend;
import com.medassist.agent.llm.LlmGateway;
import com.medassist.agent.security.PromptInjectionDetector;
import com.medassist.agent.trajectory.InMemoryTrajectoryRecorder;
import com.medassist.agent.trajectory.TrajectoryRecorder;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
  RetrievalProperties.class,
  AgentExecutionProperties.class,
  ChatMemoryProperties.class
})
public class AgentConfiguration {
  @Bean
  @ConditionalOnMissingBean
  ChatMemory chatMemory(final ChatMemoryProperties properties) {
    return new InMemoryChatMemory(properties.maxMessages(), properties.maxCharacters());
  }

  @Bean(name = "agentToolExecutorExecutor", destroyMethod = "shutdown")
  ExecutorService agentToolExecutorExecutor(final AgentExecutionProperties properties) {
    return Executors.newFixedThreadPool(
        properties.parallelism(), Thread.ofPlatform().name("agent-tool-", 0).factory());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "agent.deid",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  QueryDeidentifier queryDeidentifier() {
    return new RejectingQueryDeidentifier();
  }

  @Bean
  @ConditionalOnMissingBean
  AgentRouter agentRouter() {
    return new DefaultAgentRouter();
  }

  @Bean
  @ConditionalOnMissingBean
  AgentToolExecutor agentToolExecutor(
      @Qualifier("retrievalToolBackend") final ObjectProvider<ToolBackend> retrievalBackend,
      @Qualifier("structuredQueryToolBackend")
          final ObjectProvider<ToolBackend> structuredQueryBackend,
      final RetrievalProperties properties,
      @Qualifier("agentToolExecutorExecutor") final ExecutorService executor) {
    return new DefaultAgentToolExecutor(
        new com.medassist.agent.routing.DefaultToolRegistry(),
        retrievalBackend.getIfAvailable(),
        structuredQueryBackend.getIfAvailable(),
        properties.timeout(),
        executor);
  }

  @Bean
  @ConditionalOnMissingBean
  PromptInjectionDetector promptInjectionDetector() {
    return new PromptInjectionDetector();
  }

  @Bean
  @ConditionalOnMissingBean
  DraftGenerator draftGenerator(
      final LlmGateway gateway, final PromptInjectionDetector promptInjectionDetector) {
    return new LlmDraftGenerator(gateway, promptInjectionDetector);
  }

  @Bean
  @ConditionalOnMissingBean
  DraftVerifier draftVerifier() {
    return new StructuredDraftVerifier();
  }

  @Bean
  @ConditionalOnMissingBean
  CheckpointStore checkpointStore() {
    return new InMemoryCheckpointStore();
  }

  @Bean
  @ConditionalOnMissingBean
  TrajectoryRecorder trajectoryRecorder() {
    return new InMemoryTrajectoryRecorder();
  }

  @Bean
  @ConditionalOnMissingBean
  AgentExecutionLimits agentExecutionLimits() {
    return AgentExecutionLimits.defaults();
  }

  @Bean
  @ConditionalOnMissingBean
  AgentExecutionEngine agentExecutionEngine(
      final AgentRouter router,
      final AgentToolExecutor toolExecutor,
      final DraftGenerator draftGenerator,
      final DraftVerifier draftVerifier,
      final CheckpointStore checkpointStore,
      final TrajectoryRecorder trajectoryRecorder,
      final AgentExecutionLimits limits) {
    return new DefaultAgentExecutionEngine(
        router,
        toolExecutor,
        draftGenerator,
        draftVerifier,
        checkpointStore,
        trajectoryRecorder,
        limits,
        Clock.systemUTC());
  }
}
