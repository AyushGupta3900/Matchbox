---
description: Commit current changes with a clear message, without a Claude co-author trailer
---

Commit the current working-tree changes.

Steps:
1. Run `git status` and `git diff --staged` and `git diff` to see what changed.
2. Stage everything relevant with `git add -A` (unless the user named specific files).
3. Write a concise Conventional-Commits style message that describes the actual change
   (e.g. `docs: add data model ERD`, `feat: limit order matching`, `fix: ...`).
   Use a short subject line; add a body with bullet points only if the change is large.
4. Commit.

Hard rules:
- Do NOT add a `Co-Authored-By: Claude ...` trailer.
- Do NOT add any "Generated with Claude Code" line.
- Do NOT add `--author` overrides; use the repo's configured git identity.
- If there are no changes to commit, say so and stop — do not create an empty commit.
- If git identity (user.name/user.email) is unset, tell the user the two `git config`
  commands to run and stop, rather than committing as an unknown author.

$ARGUMENTS
