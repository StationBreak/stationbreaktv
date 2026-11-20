# Agent instructions

Coding agents follow [CLAUDE.md](CLAUDE.md). This file only points there, so `AGENTS.md` and `CLAUDE.md` stay one set of rules.

Ponytail is registered for Claude Code in [`.claude/settings.json`](.claude/settings.json) (`ponytail@ponytail`). Its mode rules come from that plugin. They do not replace `CLAUDE.md`.

## Model routing and session hygiene
- The orchestrator (opus) plans, delegates and reviews. It does not edit code directly.
- Every Task/subagent spawn sets an explicit model. Never inherit.
  - haiku: read-only work (search, grep, file reading, log and CI triage, summaries).
  - sonnet: writing code, tests and docs, normal PR work.
  - opus: only when the issue is labeled `needs-opus`, or after a sonnet attempt failed twice.
- Mechanical edits (renames, string or format sweeps, boilerplate) go to aider/qwen when it is available.
- One issue per session, in its own git worktree (`git worktree add ../<repo>-<issue> -b issue-<n>`). Start a fresh session for the next issue instead of continuing a long one.
- Standing rules: merge on green CI, and always work in your own worktree. They live here, so prompts don't need to repeat them.
