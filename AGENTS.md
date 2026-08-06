# Agent Workflow

This project uses a two-agent workflow.

The main ChatGPT/Codex agent is responsible for high-judgment work:
- interpreting requirements
- architecture and design decisions
- logic analysis
- breaking features into implementation tasks
- reviewing implementation quality
- resolving tradeoffs
- final integration decisions
- deciding what tests or verification are required

The `deepseek` subagent is used for token-heavy bounded implementation work:
- implementing clearly scoped code blocks
- generating boilerplate
- writing straightforward functions, components, tests, or adapters
- refactoring within a narrow instruction
- summarizing large files or implementation areas when requested

## Delegation Rules

When a task includes substantial implementation work, the main agent should first decompose the work into small, explicit tasks, then delegate suitable implementation tasks to the `deepseek` subagent.

The main agent must not delegate final architectural judgment, requirement interpretation, security-sensitive decisions, public API design, data model decisions, migration strategy, or cross-module integration decisions to `deepseek`.

The `deepseek` subagent should receive narrow prompts with:
- exact files or modules in scope
- expected behavior
- constraints
- output format
- whether file edits are allowed

The main agent remains responsible for reviewing `deepseek` output before accepting it, integrating it, or presenting it as final.

## Safety

Prefer using `deepseek` for read-heavy exploration and clearly bounded implementation.
Use the main ChatGPT/Codex agent for ambiguous, risky, or high-impact changes.
If there is uncertainty, the main agent should analyze first, then delegate only after the task is well specified.