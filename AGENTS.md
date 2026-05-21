# AGENTS.md

This project uses `CLAUDE.md` as its primary AI context file. If your tool reads `AGENTS.md` natively rather than `CLAUDE.md`, treat this file as its equivalent — full project context is in `CLAUDE.md`.

## PR review workflow

Every PR should be reviewed with the project's review command before being marked ready:

```text
/review
```

The command is defined in `.claude/commands/review-pr.md`. Rules applied during review live in `.claude/pr-rules/`.

## Compounding principle

When a review catches a recurring mistake or confirms a non-obvious good pattern, the finding gets promoted into the relevant rules file or into `CLAUDE.md` so that future agents start from a higher baseline. This is the mechanism by which the codebase teaches its own conventions — see `.claude/pr-rules/common.md` for the current accumulated lessons.
