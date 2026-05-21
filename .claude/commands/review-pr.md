# Review PR

Review the current branch against main. Be direct. Cite `file:line`. Surface real issues — no padding.

## 1. Scope the diff

Run in order:

```
gh pr view --json number,title,body,headRefName 2>/dev/null || true
git fetch origin main
git log --no-merges origin/main..HEAD --oneline
git diff origin/main...HEAD --stat
git diff origin/main...HEAD
```

Read the PR description. Note the stated intent. Every change should trace to it — flag anything that does not.

## 2. Load rules

Always read `.claude/pr-rules/common.md`.

For changes touching the Android native bridge (`android/`), also read `CLAUDE.md` section "Capacitor Notes".

For non-trivial changes to any JS module, follow the module's import chain to confirm no cycles were introduced.

## 3. Apply lessons learned

Apply every entry under the "Lessons learned" section of `.claude/pr-rules/common.md` as an explicit check against the diff.

## 4. Output

Use exactly this format:

```
## Summary
<one paragraph: what the PR does, whether it matches the stated intent>

## Blocking
- [file:line] issue, why it blocks

## Should fix
- [file:line] issue

## Nice to have
- issue

## Verified
- what was checked and looks good
```

If nothing blocks, say so explicitly. Do not manufacture concerns.

If the diff surfaces a pattern worth remembering — a recurring mistake caught, or a non-obvious approach confirmed correct — suggest the bullet to add to `.claude/pr-rules/common.md` under "Lessons learned". Do not edit the file yourself; leave that to the human.
