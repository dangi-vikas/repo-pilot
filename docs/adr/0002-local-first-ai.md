# ADR 0002 — Local-first AI behind a provider abstraction

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** RepoPilot maintainers

## Context

The most interesting RepoPilot features — AI investigation of a hotspot, explanation of a
symbol, summarising a set of findings — need a language model. The obvious choices are a
hosted API (open weights served remotely) or a local runtime (Ollama, llama.cpp, vLLM).

RepoPilot is positioned as a **local-first, open-source** tool. Its promise is that a
developer can analyse a proprietary codebase without shipping source code to a third
party, without an account, and without a per-token bill. That promise is broken the moment
a paid, remote API is a hard dependency.

At the same time, the AI is not the product. It is one way of presenting evidence. Every
AI output must be traceable to something the backend actually observed (a file, a line, a
symbol, a profiler sample). An AI that is unavailable must degrade the experience, never
break it.

## Decision

1. **All model access goes through a provider abstraction** (`AiProvider`), never through
   a specific SDK at a call site. The rest of the system depends on the interface plus a
   registry that reports availability, not on Ollama's HTTP shape.
2. **Ollama is the default and only provider required to run RepoPilot.** It runs on the
   same host, over loopback, with no API key. A build that has Ollama installed is
   feature-complete; a build that does not is still fully usable for the deterministic
   capabilities (indexing, static analysis, runtime profiling).
3. **No paid API is required, ever, for the default experience.** Additional providers may
   be *supported* behind the same interface, but the product must never require one.
4. **Availability is probed and reported truthfully.** The registry exposes
   `enabled`, `reachable`, and `usable`. The API surfaces these (see the overview's `ai`
   block) so the UI can say "no model runtime detected" instead of failing a request.
5. **Embeddings are optional and off by default.** Vector search is only justified once
   there is a concrete use case that keyword and symbol search cannot serve; if enabled,
   it must remain optional so the default install stays dependency-free.

## Consequences

**Positive**

- Source code can be analysed without leaving the machine; verifiable by inspection
  (loopback-only model traffic) rather than by policy.
- No vendor lock-in and no key management in the default path.
- The app's deterministic core keeps working with `repopilot.ai.enabled=false`, which is
  what the test suite runs under.

**Negative / risks**

- Local models are smaller and weaker than frontier hosted models; output quality is
  bounded by what fits on the user's machine. This is accepted as the cost of the
  local-first promise.
- The provider interface must be general enough not to leak Ollama specifics; that is a
  standing design constraint, not a one-time cost.

## Alternatives considered

- **Require a hosted API.** Rejected: breaks the local-first, no-cost, no-account promise
  and makes proprietary-code analysis a data-export decision.
- **Bundle a model runtime.** Rejected: enormous install size and platform-specific
  packaging for a capability that is optional to the core.
- **Hard-code Ollama everywhere.** Rejected: couples the domain to one runtime and makes
  any future provider a rewrite.
