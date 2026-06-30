# Project Horizon — Session Handoff 6

> **Status:** `main` is clean. All Phase 2B engineering debt from Handoff 5 has been addressed.
> **Next branch to open:** `feature/sentence-splitting`

---

## 1. What Changed Since Handoff 5

| Item | Change |
|---|---|
| `SentimentML.kt` | Implements `SentimentScorer`; `writeLeaderboard` removed; OnnxTensor `try/finally`; JVM shutdown hooks for session/tokenizer/env; tokenizer fallback path removed (fail-fast on missing classpath resource) |
| `DynamoWriter.kt` | Implements `PolarityStore`; endpoint/region/credentials from env vars; `DefaultCredentialsProvider` for real AWS; TTL and table name as named constants; JVM shutdown hook |
| `App.kt` | Owns all orchestration; `runLeaderboard(articles, scorer, store)` accepts interfaces — injectable and testable; scores each article once, distributes to tickers (fixes N-forward-passes bug); single timestamp per run |
| `SentimentScorer.kt` | New `fun interface` — enables lambda stubs in tests |
| `PolarityStore.kt` | New `fun interface` — enables lambda stubs in tests |
| `LambdaHandler.kt` | Phase 2C skeleton with correct `RequestHandler<SQSEvent, Unit>` signature |
| `AppTest.kt` | Two-tier strategy: contract tests always run; model-dependent tests skip via `assumeTrue(modelPresent)` which checks both `finbert.onnx` and `tokenizer.json` exist on classpath and on disk |
| `RunLeaderboardTest.kt` | 4 orchestration tests (no model needed): one-forward-pass-per-article, ≥5 filter, top-10 cap, descending order — all run in CI |
| `DTOs.kt` | Comment on `insights` field — intentionally unused |
| `build.gradle.kts` | Shadow JAR plugin (`io.github.goooler.shadow:8.1.8`); `application` plugin removed (was breaking shadow on Gradle 9 via `ShadowApplicationPlugin.mainClassName`); manual `JavaExec` run task |
| `libs.versions.toml` | Stale `guava` and `junit-jupiter-engine` entries removed; shadow plugin entry added |
| `gradle.properties` | `org.gradle.configuration-cache=true` removed — all commands used `--no-configuration-cache` anyway |
| `.env.example` | Documents `HF_TOKEN`, `DYNAMODB_ENDPOINT`, `AWS_REGION` |
| `computePolarity` | Input validation: punctuation-only / symbol-only text now returns `0.0` via `text.none { it.isLetterOrDigit() }` |
| `setup.sh` | Unchanged — copies model + tokenizer from `horizon-ml/` to `app/src/main/resources/` |

---

## 2. Current Architecture

```
polygon_news.json (classpath)
    │
    ▼
App.kt — main()
    │  decodes JSON → List<PolygonArticle>
    │
    ▼
runLeaderboard(articles, scorer=SentimentML, store=DynamoWriter)
    │
    ├── for each article:
    │     SentimentScorer.computePolarity(article)
    │       └── SentimentML.runInference(text)
    │             └── HuggingFaceTokenizer → OnnxTensor × 3 → OrtSession.run()
    │                   → softmax → P(pos) − P(neg) ∈ [−1.0, +1.0]
    │
    ├── aggregate polarity per ticker (Map<String, List<Double>>)
    ├── filter: ≥5 articles per ticker
    ├── average, sort descending, take top 10
    │
    └── for each top-10 ticker:
          PolarityStore.writePolarityScore(ticker, timestamp, score, articleCount)
            └── DynamoWriter → DynamoDbClient.putItem → horizon-sentiment table

LambdaHandler.kt — stub (Phase 2C)
```

**Python side (`horizon-ml/`):**
```
export_finbert.py
    ├── Step 1: load ProsusAI/finbert, sanity-check 3 sentences
    ├── Step 2: validate against FinancialPhraseBank all-data.csv (88.94% accuracy)
    └── Step 3: export finbert.onnx (opset 18, external data format) + tokenizer files
```

---

## 3. Model Assessment

**Model:** ProsusAI/finbert — BERT-base fine-tuned on ~10k financial sentences from Reuters, earnings calls, and analyst reports.

**Label order (load-bearing):** `[positive=0, negative=1, neutral=2]` — this is what `LABEL_MAP` in `export_finbert.py` declares and what the softmax in `SentimentML` depends on. A mismatch here silently inverts all scores. It is documented only in a single comment on line 64 of `SentimentML.kt`. **Do not change this without verifying against the model's `config.json` label ordering.**

**ONNX export format:** The model is exported in external data format (`finbert.onnx` + `finbert.onnx.data`) because BERT's weight tensors exceed protobuf's 2GB limit when serialised inline. Both files must be present and co-located. `setup.sh` handles this correctly.

**Accuracy:** 88.94% on `FinancialPhraseBank/all-data.csv` (mixed-agreement sentences). The all-agree subset (`Sentences_AllAgree.txt`) would give a higher accuracy figure but is a harder ceiling to beat.

**Known model limitations:**
- FinBERT was trained on short financial sentences (~20–30 tokens). Feeding it a full article title + description (100–200+ tokens) degrades accuracy because it wasn't trained on multi-sentence inputs.
- The current pipeline feeds the full concatenated `title + " " + description` as a single inference call. Sentence splitting is the documented next step.
- No confidence threshold — a sentence scoring `[0.34, 0.33, 0.33]` contributes equally to the average as one scoring `[0.97, 0.01, 0.02]`. Low-certainty predictions dilute signal.

---

## 4. Engineering Assessment

Severity scale: **[CRITICAL]**, **[HIGH]**, **[MEDIUM]**, **[LOW]**

---

### 4.1 Testing

**[CRITICAL] Inference correctness never validated in CI**

The three model-dependent tests in `AppTest` skip in CI because `finbert.onnx` is not committed (400MB+). CI only proves the project compiles and that blank text returns `0.0`. A wrong label mapping or broken ONNX export would pass CI silently.

`RunLeaderboardTest` covers orchestration logic (four tests, no model needed) and runs in CI — this is good. But it cannot catch a model that scores positive sentences as negative.

**Resolution:** Export a dynamically quantised fixture model via `onnxruntime.quantization.quantize_dynamic` and commit it to `src/test/resources/`. Dynamic int8 quantisation typically reduces FinBERT from ~500MB to ~120–150MB — still too large for git. The practical path is further distillation or using a smaller financial-domain BERT (e.g. `yiyanghkust/finbert-tone`) as the test fixture. Once a fixture is committed, `modelPresent` returns `true` in CI automatically and the existing tests run without any code changes.

**[MEDIUM] `AppTest` model-dependent tests are sign tests only**

The three model tests verify directional correctness (positive/negative/zero). They do not verify:
- Output is in `[-1.0, 1.0]` — the `computePolarity returns a finite value in valid range` test does this but only if the model is present
- Scores degrade gracefully on edge inputs (all-caps, ticker symbols, numbers only)
- Confidence distribution is not degenerate (all 0.33)

These are lower priority but worth adding as the model is pushed harder by sentence splitting.

**[LOW] `AppTest` and `RunLeaderboardTest` are both in `package org.horizon`**

`RunLeaderboardTest` tests `runLeaderboard` which is a package-level function in `App.kt`. Keeping both test classes in the same package is correct. No action needed, noting for clarity.

---

### 4.2 Architecture / Coupling

**[MEDIUM] `SentimentML` and `DynamoWriter` are still Kotlin `object` singletons**

Both are global singletons with no explicit lifecycle. The JVM shutdown hooks added in this session ensure resources are closed at JVM exit, which is sufficient for `./gradlew run`. However, for Lambda:

- Lambda handlers must be classes that implement `RequestHandler`, instantiated once at cold-start and reused across warm invocations
- A Kotlin `object` is fine for warm reuse (it's effectively a singleton), but it cannot be closed and re-opened if the session needs to be reset (e.g. ORT session error)
- Without constructor injection, `LambdaHandler` has no way to swap in a different scorer or store for integration testing without touching source code

**This is acceptable for Phase 2C** given the `fun interface` contracts are in place. Flag for Phase 2D: when the Lambda is deployed, convert `SentimentML` and `DynamoWriter` to classes instantiated in `LambdaHandler`'s constructor, injected as interfaces.

**[LOW] `runLeaderboard` is a public top-level function**

It's the right design for testability, but its public visibility makes it part of the module's API surface. Mark it `internal` if this module ever becomes a library dependency of another module.

---

### 4.3 Pipeline Correctness

**[HIGH] No per-article error handling — one failure crashes the run**

In `runLeaderboard`, if `scorer.computePolarity(article)` throws (e.g. ORT session error, corrupted token), the entire run crashes at that article and no subsequent articles are scored. For 5,548 articles this has not been a problem, but once real SQS ingestion is live, a single malformed article should be logged and skipped, not kill the Lambda invocation.

```kotlin
// Current — crash on first bad article
val polarity = scorer.computePolarity(article)

// Better — catch, log, continue
val polarity = try {
    scorer.computePolarity(article)
} catch (e: Exception) {
    logger.error("Inference failed for article '{}': {}", article.title, e.message)
    return@forEach
}
```

**[HIGH] No sentence splitting before inference**

FinBERT's maximum effective input is ~20–30 tokens (one financial sentence). The current code concatenates `title + description` (often 60–200+ tokens) and feeds it as a single input. The tokenizer truncates at 512 tokens but the model's attention pattern is tuned for short inputs. Article-level scores are noisier than sentence-averaged scores.

This is the explicitly planned next feature (`feature/sentence-splitting`) and is the correct next step.

**[MEDIUM] No confidence threshold**

Sentences where the model is uncertain (max class probability < 0.65) should not contribute to the ticker average. Without this filter, a genuinely ambiguous sentence drags the score toward 0.0 with the same weight as a high-confidence sentence. Implement alongside sentence splitting.

**[MEDIUM] No deduplication / idempotency**

Every `./gradlew run` re-scores all 5,548 articles and writes to DynamoDB. Running twice writes 20 items instead of 10 (different timestamps). In production, the pipeline should check if an article has already been scored before running inference. A simple guard: hash the article URL or `published_utc` + `ticker`, store it in a processed-articles table, skip on match.

---

### 4.4 Resource & Performance

**[MEDIUM] DynamoDB writes are synchronous and sequential**

`DynamoWriter.writePolarityScore` calls `client.putItem()` synchronously, one ticker at a time. For 10 tickers this is negligible. Under real Lambda load (many articles, many tickers), batching writes using `DynamoDbClient.batchWriteItem()` would be more efficient. DynamoDB's `BatchWriteItem` supports up to 25 `PutRequest` operations per call. Flag for Phase 2D.

**[LOW] `OrtEnvironment` is eagerly initialised**

`private val env: OrtEnvironment = OrtEnvironment.getEnvironment()` runs when the `SentimentML` object is first accessed, including during tests that only call `computePolarity("")` (which returns early). This wastes a small amount of initialisation time per test run. Low impact for now.

---

### 4.5 Configuration & Deployment

**[HIGH] External data format requires co-located files for Lambda**

The ONNX model was exported with `external_data` format (`finbert.onnx` + `finbert.onnx.data`). `env.createSession(path)` uses a filesystem path — ONNX Runtime loads both files from the same directory. In a Lambda deployment, both files must be extracted from the JAR to a writable path (typically `/tmp`) before `createSession` can run. The current code assumes both files are on the filesystem as a classpath path — this works locally but will fail in Lambda unless the extraction step is added to `LambdaHandler`.

```kotlin
// What's needed in LambdaHandler cold-start (Phase 2C)
val modelPath = extractResourceToTmp("/finbert.onnx")
extractResourceToTmp("/finbert.onnx.data")  // must be in same directory
val session = env.createSession(modelPath, OrtSession.SessionOptions())
```

**[MEDIUM] Fat JAR is 519MB**

The shadow JAR includes ONNX Runtime native libraries (CPU + all platform variants), DJL tokenizer natives, and the AWS SDK. Lambda's unzipped deployment limit is 250MB. Options for Phase 2D:
1. Use a Lambda container image (10GB limit) — simplest, no code changes
2. Strip ONNX Runtime to CPU-only natives and filter unused AWS SDK HTTP clients (reduces to ~80–120MB)
3. Use Lambda layers to separate the ONNX model from the runtime code

**[LOW] `--no-configuration-cache` in CI commands**

`ci.yml` still passes `--no-configuration-cache` on both build and test commands. The property was removed from `gradle.properties` so the flag is now redundant (it was only needed to override the setting). Remove from CI commands when convenient.

---

### 4.6 Python / ML Pipeline

**[MEDIUM] `requirements.txt` is GPU-only**

`requirements.txt` pins CUDA packages (`cuda-toolkit 13.0.2`, `nvidia-*`, `triton`). On a CPU-only machine or Mac, `pip install -r requirements.txt` will fail or install unusable packages. `requirements-cpu.txt` exists for this but the README setup section points to `requirements.txt`. The README should make the CPU vs GPU choice explicit up front.

**[LOW] `export_finbert.py` validates against `all-data.csv`**

`all-data.csv` is a mixed-agreement file (50%–100% annotator agreement). Validation accuracy of 88.94% is against this full set. A reader might assume this is the all-agree subset (which FinBERT typically achieves 97%+ on). The validation step should note which file it's using and why.

**[LOW] No model versioning in DynamoDB records**

When `finbert.onnx` is regenerated (e.g. after `export_finbert.py` changes), existing DynamoDB records were produced by a different model. There is no model version tag in the schema. For backtesting and auditing, a `model_version` attribute (e.g. the SHA256 from `finbert.onnx.sha256`) would identify which model produced which scores.

---

### 4.7 Documentation Drift

**[LOW] PR template is missing `./gradlew test` checklist item**

The `chore/setup-additions` commit removed `./gradlew test` from `.github/pull_request_template.md`. `CONTRIBUTING.md` still documents all three checklist items. One of these needs to be updated to match the other.

**[LOW] README `./gradlew run` command is missing `DYNAMODB_ENDPOINT`**

The README Key Commands section shows `./gradlew run --no-configuration-cache` without the required `DYNAMODB_ENDPOINT=http://localhost:4566` prefix. Running it without the env var will attempt real AWS and fail with a credentials error.

**[LOW] README repo structure section is stale**

It still mentions `FinancialPhraseBank/` under `app/src/main/resources/` and lists it as a resource — these files still exist in main resources but the test-resource copies were deleted. The structure diagram also doesn't reflect `SentimentScorer.kt`, `PolarityStore.kt`, or `RunLeaderboardTest.kt`.

---

## 5. Summary Table

| Area | Issue | Severity | Status |
|---|---|---|---|
| Testing | Inference never validated in CI | CRITICAL | Open |
| Testing | Sign tests only — no edge case coverage | MEDIUM | Open |
| Pipeline | No per-article error handling | HIGH | Open |
| Pipeline | No sentence splitting before inference | HIGH | Open (next feature) |
| Pipeline | No confidence threshold | MEDIUM | Open (with sentence splitting) |
| Pipeline | No deduplication / idempotency | MEDIUM | Open |
| Deployment | External data format needs extraction in Lambda | HIGH | Open (Phase 2C) |
| Deployment | Fat JAR 519MB exceeds Lambda ZIP limit | MEDIUM | Open (Phase 2D) |
| Architecture | Singletons — no constructor injection for Lambda | MEDIUM | Acceptable until Phase 2C |
| Performance | DynamoDB writes sequential, not batched | MEDIUM | Open (Phase 2D) |
| Python | `requirements.txt` is GPU-only | MEDIUM | Open |
| Python | No model version in DynamoDB records | LOW | Open |
| Docs | PR template missing `./gradlew test` | LOW | Open |
| Docs | README `run` command missing env var | LOW | Open |
| Docs | README repo structure stale | LOW | Open |

---

## 6. Recommended Work Order

### Immediate (before `feature/sentence-splitting`)
1. Fix error handling in `runLeaderboard` — catch per-article inference failures, log and continue
2. Fix README: add `DYNAMODB_ENDPOINT=http://localhost:4566` to the `run` command
3. Fix PR template: restore `./gradlew test` checklist item (or align CONTRIBUTING.md to the current template)
4. Fix `requirements.txt` / README to make CPU vs GPU dependency choice explicit

### `feature/sentence-splitting` (next branch)
5. Split `title + description` into sentences using a sentence boundary detector (e.g. simple regex on `.`, `!`, `?` or the `opennlp-tools` sentence detector)
6. Run `computePolarity` on each sentence independently
7. Apply confidence threshold: discard sentences where `max(softmax output) < 0.65`
8. Average remaining sentence scores as the article polarity
9. Add tests for: empty sentence list → 0.0, all-below-threshold → 0.0, mixed confidence → only high-confidence sentences averaged

### `feature/lambda-handler` (Phase 2C)
10. Implement `LambdaHandler.handleRequest`: parse SQS event, extract article JSON, invoke `runLeaderboard`
11. Add resource extraction helper: copy `finbert.onnx` + `finbert.onnx.data` to `/tmp` at cold start
12. Convert `SentimentML` and `DynamoWriter` from `object` to classes instantiated in handler constructor
13. Add error handling for malformed SQS payloads (DLQ strategy)

### Before Phase 2D (Lambda deploy)
14. Decide on deployment packaging: container image (simplest) vs stripped fat JAR (more complex)
15. Add `model_version` attribute to DynamoDB writes (use `finbert.onnx.sha256`)
16. Switch `DynamoWriter` to `BatchWriteItem` for bulk writes
17. Add `processed-articles` deduplication table or use DynamoDB conditional writes

### CI inference gap (can be done in parallel with any of the above)
18. In `horizon-ml/`: `quantize_dynamic("finbert.onnx", "finbert-test.onnx", weight_type=QuantType.QUInt8)` — check output size
19. If still too large, export a smaller financial BERT (e.g. `yiyanghkust/finbert-tone`) as the test fixture
20. Commit to `src/test/resources/` — `AppTest.modelPresent` will return `true` in CI automatically, no other code changes needed

---

## 7. Features to Build

### Phase 2B Remaining
| Feature | Description | Branch |
|---|---|---|
| Sentence splitting | Split article text → per-sentence inference → averaged polarity | `feature/sentence-splitting` |
| Confidence threshold | Discard sentences with max class prob < 0.65 | Same branch |

### Phase 2C
| Feature | Description | Branch |
|---|---|---|
| `LambdaHandler` impl | Parse SQS event → article → `runLeaderboard` | `feature/lambda-handler` |
| ONNX model extraction | Copy model from JAR to `/tmp` at cold start | Same branch |
| SQS input validation | Validate payload schema before processing | Same branch |

### Phase 2D
| Feature | Description | Branch |
|---|---|---|
| Lambda deployment | Shadow JAR or container image to real AWS | `feature/lambda-deploy` |
| IaC | CloudFormation / Terraform for DynamoDB, SQS, Lambda, API Gateway | `chore/infrastructure` |

### Phase 2E and Beyond
| Feature | Description | Notes |
|---|---|---|
| Signal aggregation | Rolling baseline, relative sentiment vs 7-day average | Seb's domain |
| Source credibility weighting | Tier-1 sources (Reuters, FT, Bloomberg) weighted higher | Seb's `chore/source-credibility-tiers` — note: no tier-1 sources in current Polygon dataset |
| Query API | GET /sentiment?ticker=AAPL returns recent polarity history | API Gateway + Lambda |
| Deduplication | Skip articles already scored — hash by `published_utc`+`ticker` | Prerequisite for production |
| Model versioning | Tag DynamoDB records with model SHA256 | Required for backtesting integrity |
| CI fixture model | Quantised test model for inference validation in CI | Unblocks CRITICAL gap |
| Backtesting framework | `fetch_scores.py` — Seb's planned feature, currently blocked by sentence-splitting | Hold until sentence-splitting merges |

---

## 8. Seb's Open Tasks

| Task | Branch | Status |
|---|---|---|
| Source credibility tiers JSON | `chore/source-credibility-tiers` | Unblocked — note: tier-1 publishers absent from current dataset |
| Rolling baseline schema design | `chore/baseline-schema-design` | Unblocked |
| Backtesting framework | `feature/backtesting-framework` | Hold until `feature/sentence-splitting` merges |

---

## 9. Key Commands

```bash
# Setup (after clone or after running export_finbert.py)
bash setup.sh

# Kotlin pipeline
DYNAMODB_ENDPOINT=http://localhost:4566 ./gradlew run
./gradlew build
./gradlew test
./gradlew shadowJar      # produces app/build/libs/app-all.jar (519MB)

# Python — model export (GPU)
cd horizon-ml
source .venv/bin/activate
python export_finbert.py

# Python — CPU (Mac)
pip install -r requirements-cpu.txt
python export_finbert.py

# LocalStack
localstack start -d
aws dynamodb create-table \
  --table-name horizon-sentiment \
  --attribute-definitions AttributeName=ticker,AttributeType=S AttributeName=timestamp,AttributeType=S \
  --key-schema AttributeName=ticker,KeyType=HASH AttributeName=timestamp,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:4566 \
  --profile localstack
aws dynamodb scan --table-name horizon-sentiment \
  --endpoint-url http://localhost:4566 --profile localstack

# Model checksum
sha256sum app/src/main/resources/finbert.onnx
cat horizon-ml/finbert.onnx.sha256
```

---

## 10. Resume Prompt

> I am building Project Horizon — an event-driven fintech sentiment pipeline in Kotlin on WSL2.
> Phase 2B is complete: real FinBERT ONNX inference (onnxruntime 1.19.2, DJL tokenizers), full
> engineering refactor (loose coupling via interfaces, SLF4J, env-var config, shadow JAR,
> resource management). All handoff-5 engineering debt is resolved.
>
> The full engineering assessment is in `misc/HORIZON_HANDOFF_6.md`.
>
> The two highest-priority items before opening `feature/sentence-splitting` are:
> (1) Add per-article error handling in `runLeaderboard` — catch inference failures, log, continue;
> (2) Fix the README `./gradlew run` command to include `DYNAMODB_ENDPOINT=http://localhost:4566`.
>
> The next feature branch is `feature/sentence-splitting`: split article `title + description`
> into sentences, run `computePolarity` on each, discard sentences with max class probability
> below 0.65, and average the remaining scores as the article polarity. This is the most
> impactful accuracy improvement available before Phase 2C.
>
> A CRITICAL CI gap remains: inference tests skip in CI because `finbert.onnx` is not committed.
> Closing it requires exporting a small quantised fixture model in `horizon-ml/` (Python task)
> and committing it to `src/test/resources/`. No Kotlin changes needed — `AppTest.modelPresent`
> handles it automatically.
