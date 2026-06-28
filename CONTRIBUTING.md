# Contributing to Horizon

This document covers how we work together on the codebase — branching, commits, pull requests, and CI. Read it before opening your first PR.

We use **GitHub Flow**: `main` is always in a working state, all work happens on short-lived branches, and nothing lands on `main` without a PR and a passing build.

---

## The rules

- `main` is protected. You cannot push to it directly — everything goes through a PR.
- Every PR needs one approval before it can merge.
- CI must pass before merge. A red build blocks the PR, regardless of approval.
- No force pushes, ever. If you need to undo something on `main`, open a revert PR.
- Branches are deleted after merge. Keep the branch list clean.

---

## Branches

Branch from `main`. Always.

```bash
git checkout main
git pull
git checkout -b feature/your-branch-name
```

**Naming:**

| Prefix | When to use |
|---|---|
| `feature/` | New functionality |
| `fix/` | Bug fix |
| `chore/` | Maintenance — deps, config, README, CI |
| `test/` | Adding or fixing tests with no production code change |

Keep names short and lowercase with hyphens:

```
feature/phase-2b-onnx-inference
feature/lambda-handler
fix/dynamo-ttl-calculation
chore/update-readme
test/sentiment-edge-cases
```

**Keep branches short-lived.** A branch that lives longer than a few days is a sign the work scope is too large — split it into smaller PRs.

---

## Commits

Write commits that explain *why*, not just *what*. The diff already shows what changed.

**Format:**

```
<type>: <short summary in present tense, under 72 chars>

Optional body if the change needs more context. Explain the reasoning,
not the mechanics. Wrap at 72 chars.
```

**Types:**

| Type | When to use |
|---|---|
| `feat` | New feature or behaviour |
| `fix` | Bug fix |
| `chore` | Build, deps, config, tooling |
| `test` | Tests only |
| `docs` | Documentation only |
| `refactor` | Restructuring with no behaviour change |

**Examples:**

```
feat: add ONNX forward pass to SentimentML

Replaces the placeholder label-counting logic with a real FinBERT
forward pass via OrtEnvironment. Polarity is now P(pos) - P(neg)
from softmax logits rather than a ratio of pre-labeled tags.
```

```
fix: clamp polarity score to [-1.0, 1.0]

Softmax outputs can produce floating point values marginally outside
this range due to precision. DynamoDB writes were failing validation.
```

```
chore: pin onnxruntime to 1.19.2 in build.gradle.kts
```

**Commit discipline:**

- One logical change per commit. Don't bundle a bug fix and a new feature into a single commit.
- Don't commit commented-out code, debug prints, or `TODO: remove this` blocks.
- Run `./gradlew build` locally before committing. Don't push a broken build.

---

## Pull requests

Open a PR as soon as your branch is ready for review — don't wait until it's perfect. A PR that's 90% there with a note explaining what's left is better than a 400-line PR that lands all at once.

**PR title** follows the same format as a commit message:

```
feat: replace placeholder polarity with ONNX inference
fix: handle empty article list in computePolarity()
chore: add CI workflow
```

**PR description** — use the template (`.github/pull_request_template.md`):

```markdown
## What does this do?
Clear description of the change and why it exists.

## How to test it
Steps to verify the change works locally.

## Checklist
- [ ] `./gradlew build` passes locally
- [ ] `./gradlew test` passes locally
- [ ] LocalStack end-to-end run verified (if touching pipeline code)
```

**Size:** Keep PRs small and focused. A PR that touches one thing is easier to review, easier to revert if it causes a problem, and less likely to sit in review for days. If a PR is growing beyond ~300 lines of meaningful change, consider splitting it.

**Draft PRs:** If you want early feedback before something is ready to merge, open it as a Draft PR. This signals it's not ready for approval yet but is visible for discussion.

---

## Code review

**As the reviewer:**

- Review within 24 hours if possible. Blocking someone's branch for days slows the whole project down.
- Focus on correctness, clarity, and whether the change fits the architecture — not style preferences.
- If something is unclear, ask a question rather than assuming it's wrong.
- Approve when you're satisfied, even if you've left minor comments. If a comment is non-blocking, say so explicitly: `nit:` or `optional:` prefix.
- If you request changes, re-review promptly when the author pushes a fix.

**As the author:**

- Respond to every comment, even if just to say you've addressed it or you disagree and why.
- Don't resolve threads yourself — let the reviewer resolve them after confirming the fix.
- If a review drags past 48 hours without response, ping the reviewer directly.

---

## CI

Every PR and every push to `main` runs the CI pipeline automatically. It must be green before merge.

The pipeline (`.github/workflows/ci.yml`) runs:
1. `./gradlew build` — compiles and packages
2. `./gradlew test` — runs the test suite

**If CI fails on your PR:** fix it before asking for review. A PR with a red build should not be approved.

**If CI fails on `main`:** this is a priority fix. Whoever caused the failure opens a `fix/` branch immediately. Nobody merges new work to `main` while it's red.

---

## What lives in git and what doesn't

The `.gitignore` covers most of this, but worth being explicit:

| Never commit | Why |
|---|---|
| `horizon-ml/.env` | Contains `HF_TOKEN` |
| `*.onnx` / `*.onnx.data` | 400MB+, regenerate locally |
| `.venv/` | Python virtual environment |
| `build/` | Gradle build output |
| `.gradle/` | Gradle cache |
| `.idea/` | IDE config |
| `aws/` | AWS CLI internals |

If you accidentally commit something sensitive, tell the other person immediately. Don't try to quietly fix it — the token needs to be rotated.

---

## Local setup reminder

Before starting any work session:

```bash
# Make sure you're up to date
git checkout main
git pull

# Start LocalStack (Docker Desktop must be running)
localstack start -d
localstack status services

# Create the DynamoDB table if LocalStack was restarted
aws dynamodb create-table \
  --table-name horizon-sentiment \
  --attribute-definitions \
    AttributeName=ticker,AttributeType=S \
    AttributeName=timestamp,AttributeType=S \
  --key-schema \
    AttributeName=ticker,KeyType=HASH \
    AttributeName=timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:4566 \
  --profile localstack

# Verify your build is clean before branching
./gradlew build --no-configuration-cache
```

---

## GitHub setup (one-time, done by repo owner)

**Branch ruleset for `main`** — Settings → Rules → Rulesets → New ruleset:

- Target: `main`
- ✅ Require a pull request before merging
- ✅ Required approvals: 1
- ✅ Dismiss stale reviews when new commits are pushed
- ✅ Require status checks to pass → add `build` (the CI job name)
- ✅ Block force pushes
- ✅ Restrict deletions

**CI workflow** — create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: Build
        run: ./gradlew build --no-configuration-cache

      - name: Test
        run: ./gradlew test --no-configuration-cache
```

**PR template** — create `.github/pull_request_template.md`:

```markdown
## What does this do?

## How to test it

## Checklist
- [ ] `./gradlew build` passes locally
- [ ] `./gradlew test` passes locally
- [ ] LocalStack end-to-end run verified (if touching pipeline code)
```

Once the CI workflow is committed and has run at least once, go back to the ruleset and add `build` as a required status check.
