---
name: github-pr
description: Create GitHub pull requests via gh CLI. Requires gh authenticated. Draft by default. Use when: create PR, pull request, github PR, open PR, draft PR.
---

# GitHub PR

Create GitHub pull requests via `gh` CLI.

## Usage

- `/github-pr` — ask for source/target, then create draft PR
- `/github-pr <target-branch>` — create draft PR targeting specified branch
- `/github-pr <target-branch> --ready` — create PR as ready (not draft)

## Prerequisites

- `gh` CLI installed and authenticated (`gh auth status` succeeds)
- Current directory is a git repo with a GitHub remote

## Workflow

### 1. Gather context

```bash
git branch --show-current
git log <target>..HEAD --oneline
git diff <target>...HEAD --stat
```

### 2. Ensure branch is pushed

```bash
git push -u origin $(git branch --show-current)
```

Run automatically. If push fails, stop and report.

### 3. Check for existing PR

```bash
gh pr list --head $(git branch --show-current) --state open --json url
```

If response is a non-empty array → PR already exists. Stop. Report the PR URL. Do not create a new one.

### 4. Propose title and body

Show the proposed title and body to the user. Wait for approval before creating.

**Title format**

```
<type>(<scope>): <short description>
```

Same rules as commit messages: lowercase, imperative, under 70 chars.

**Body format**

Two sections only. No checklists, no file lists, no templates.

```
### Background

1-2 paragraphs explaining the problem and the high-level approach.
Write naturally, like explaining to a colleague.

### Key Decisions

1. **Decision title**: brief explanation of the choice.
2. **Another decision**: what was done, stated as a fact.
```

### 5. Create PR

Build the body in a heredoc and pass to `gh pr create`. Draft by default.

```bash
gh pr create \
  --base <target_branch> \
  --head $(git branch --show-current) \
  --title "<title>" \
  --body-file /tmp/github_pr_body.md \
  --draft
```

Drop `--draft` only when `--ready` flag is used.

### 6. Report

`gh pr create` prints the new PR URL to stdout. Report it to the user.

## Error handling

- `gh auth status` fails → stop with clear message to run `gh auth login`
- `git remote get-url origin` is not GitHub → stop, skill only works with GitHub
- Push fails → stop, report error
- Existing PR found → show URL, do not create

## Style rules

- No AI mentions — never reference agents, Claude, copilot, or AI assistance
- No Co-Authored-By — never add Co-Authored-By trailers
- No emojis in title or body
- Human voice — write like a developer wrote it
- No file lists or changelogs — GitHub shows that in "Files changed" tab
- Fluid prose in Background
- Key Decisions stated as facts — one sentence each, no justifying alternatives

## Language rules

- Structural elements in English: title (`<type>(<scope>): <short description>`), and the section headers `### Background` and `### Key Decisions`.
- Body content in Portuguese: the prose under Background and each Key Decision item is written in Portuguese, matching how the user communicates.
- Match the user's language. If the user writes in English, write the body in English. If Portuguese, Portuguese. The section headers stay English regardless.

## Triggers

"create PR", "pull request", "github PR", "open PR", "draft PR" — user mentions GitHub pull request creation.
