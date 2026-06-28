# Project Horizon — Session Handoff

> **Status:** Phase 1 complete. Phase 2 (ONNX model) pending.
> **Resume prompt at the bottom of this file.**

---

## 1. What Horizon Is

An event-driven, serverless alternative data sentiment pipeline. It ingests unstructured financial news, processes text through a compiled ML model, and writes quantitative polarity telemetry to a time-series store. Designed as a portfolio companion to Meridian (a Swift navigation app), demonstrating low-latency, math-centric, cloud-native backend architecture typical of enterprise fintech firms like JPMorgan, Revolut, and Wise.

The core formula throughout the entire pipeline:

```
Polarity Score  =  P(Positive) − P(Negative)  ∈  [−1.0, +1.0]
```

This transforms a discrete 3-class classifier output into a continuous scalar suitable for quantitative analysis and time-series storage.

---

## 2. Build Status

| Component | Status | Notes |
|---|---|---|
| WSL2 + Java 21 Temurin | ✅ Done | SDKMAN-managed, stored in `~/projects/Horizon` |
| Kotlin 1.9.23 + Gradle 9.6 | ✅ Done | Kotlin DSL, Application plugin, kotlin.test |
| Project package structure | ✅ Done | `dto/`, `model/`, `storage/`, `handler/`, `App.kt` |
| `DTOs.kt` | ✅ Done | `PolygonArticle`, `Insight`, `Publisher` with `@SerialName` |
| `SentimentML.kt` | ✅ Done | `computePolarity()` + `writeLeaderboard()` wired to DynamoWriter |
| `DynamoWriter.kt` | ✅ Done | AWS SDK v2, writes `ticker`, `timestamp`, `polarity_score`, `article_count`, `ttl` |
| Polygon JSON dataset | ✅ Done | 5,548 articles in `resources/polygon_news.json` |
| FinancialPhraseBank CSV | ✅ Done | 4,845 labeled sentences in `resources/` for model validation |
| LocalStack via Docker | ✅ Done | All AWS services on `http://localhost:4566` |
| DynamoDB table | ✅ Done | `horizon-sentiment`, PK=`ticker`, SK=`timestamp`, PAY_PER_REQUEST |
| End-to-end local run | ✅ Done | Articles load → polarity computed → written to DynamoDB |
| `LambdaHandler.kt` | ⬜ Todo | SQS event handler — empty stub exists |
| FinBERT ONNX export (Python) | ⬜ Todo | PyCharm project `HorizonML` — not yet written |
| ONNX inference in Kotlin | ⬜ Todo | Replace count-based polarity with real FinBERT forward pass |
| Fat JAR / Lambda packaging | ⬜ Todo | `shadowJar` plugin, GraalVM native target |
| SQS queue + Lambda deploy | ⬜ Todo | LocalStack Lambda + SQS trigger wiring |
| API Gateway ingestion endpoint | ⬜ Todo | `POST /ingest` → SQS → Lambda |
| `QuartrTranscriptResponse` DTOs | ⬜ Todo | Mirror Quartr schema for production ingestion |

---

## 3. Architectural Decisions

### Why Kotlin
Kotlin is the standard language at enterprise fintech firms. JPMorgan, Revolut, and Wise all run Kotlin or Java on the JVM for backend services. It signals awareness of production fintech standards on a portfolio. It also enables GraalVM native compilation — critical for Lambda cold-start performance that Python cannot match.

### Why DynamoDB over PostgreSQL, MongoDB, or InfluxDB

| Decision | Choice | Why |
|---|---|---|
| Time-series store | DynamoDB | Zero-ops, serverless, sub-millisecond writes. Free tier: 25GB + 200M requests/month. No idle server cost. |
| Key schema | PK=`ticker`, SK=`timestamp` | Composite key enables range queries by ticker over time windows without a secondary index. |
| TTL eviction | 48-hour TTL attribute | Native DynamoDB TTL drops items automatically. Preserves rolling window at zero cost. No maintenance required. |
| Data minimization | 5 lean fields only | Raw transcript text is discarded after inference. Only `ticker`, `timestamp`, `polarity_score`, `article_count`, `ttl` are persisted. Privacy by design. |

InfluxDB is purpose-built for time-series but requires a running server. PostgreSQL needs schema migrations and an idle RDS instance. DynamoDB is the only option that is genuinely serverless, requires zero infrastructure management, and stays within free tier at development scale.

### Why LocalStack over real AWS
- The AWS account is no longer eligible for the free tier due to prior account association.
- LocalStack emulates the full AWS API surface locally via Docker. DynamoDB, Lambda, SQS, and API Gateway all respond identically to the real SDK.
- Switching to real AWS later requires changing **one line**: the endpoint URL in `DynamoWriter.kt` from `http://localhost:4566` to the regional AWS endpoint.
- Professional fintech teams use LocalStack or a dedicated dev account for local development. It is a legitimate and recognised practice.

### Why ONNX over calling a Python API or cloud ML endpoint
- **Latency:** a network call to a Python Flask inference server or SageMaker adds 50–200ms per paragraph. ONNX runs in the same JVM process in under 5ms.
- **Cost:** SageMaker endpoints cost money. A local ONNX binary costs nothing at runtime.
- **Portability:** the `.onnx` file is a self-contained binary. It deploys inside the Lambda ZIP with no external dependencies.
- **Free tier alignment:** the entire pipeline including ML inference runs inside Lambda's 512MB memory limit with no external service calls.

### Why the current polarity formula is a placeholder
`SentimentML.computePolarity()` currently counts pre-labeled sentiment tags from the Polygon dataset rather than performing real inference. This was intentional — it validates the entire pipeline plumbing (JSON parsing, DynamoDB writes, TTL, data flow) before the ML layer is introduced. The formula is architecturally correct; only the input source changes when FinBERT replaces the label counting.

---

## 4. Current File Structure

```
~/projects/Horizon/
  app/src/main/kotlin/org/horizon/
    dto/DTOs.kt              ← PolygonArticle, Insight, Publisher
    model/SentimentML.kt     ← computePolarity(), writeLeaderboard()
    storage/DynamoWriter.kt  ← AWS SDK v2 DynamoDB PutItem
    handler/LambdaHandler.kt ← empty stub, next to implement
    App.kt                   ← thin main(), wires everything
  app/src/main/resources/
    polygon_news.json        ← 5,548 Polygon articles (mock Quartr data)
    FinancialPhraseBank/     ← 4,845 labeled sentences for model validation
  app/src/test/kotlin/org/horizon/
    AppTest.kt               ← unit tests for computePolarity()
  app/build.gradle.kts       ← Kotlin 1.9.23, serialization, AWS SDK v2, slf4j
  gradle/libs.versions.toml  ← version catalog

~/projects/HorizonML/        ← PyCharm project (to be created)
  export_finbert.py          ← load FinBERT, validate, export to .onnx
  validate_model.py          ← test model against FinancialPhraseBank CSV
  finbert.onnx               ← output → copy to Horizon/resources/ after export
```

---

## 5. The ML Model

### Do not train your own model
Training a financial sentiment model from scratch requires millions of labeled sentences, weeks of GPU compute, and deep NLP expertise. The industry standard is to start from a pretrained foundation model and either use it directly or fine-tune it on domain-specific data.

### Use ProsusAI/finbert
- FinBERT is a BERT-base model pretrained on 1.8 million financial news articles from Reuters and Bloomberg, then fine-tuned on the Financial PhraseBank dataset — the same dataset already in your `resources/` folder.
- It outputs exactly the 3-class distribution `[Positive, Neutral, Negative]` that the Horizon polarity formula requires.
- It is the de facto standard for financial NLP in academic papers and production fintech systems. Using it is the correct engineering choice, not a shortcut.
- HuggingFace hosts it at `ProsusAI/finbert` and it downloads in one line of Python.

### What a real hedge fund actually does
Tier 1 funds (Citadel, Two Sigma, Renaissance) use a layered approach:

1. **Foundation layer** — FinBERT or a custom BERT variant fine-tuned on proprietary earnings call transcripts, SEC filings, and analyst reports.
2. **Signal layer** — the raw polarity score feeds into a factor model alongside price momentum, volume anomalies, and options flow. It is never used in isolation.
3. **Ensemble layer** — multiple models vote: a sentiment model, a topic model, and a named entity recognition model identifying which executives are speaking.
4. **Alpha decay tracking** — sentiment signals decay rapidly (hours to days). The 48-hour TTL in Horizon's DynamoDB schema mirrors this directly.

For a portfolio project, implementing the foundation layer correctly and articulating awareness of the signal and ensemble layers is sufficient and impressive. Do not try to build all three.

### PyCharm setup — step by step

Create a new PyCharm project at `~/projects/HorizonML` with a virtual environment. In the terminal:

```bash
pip install torch transformers onnx onnxruntime pandas scikit-learn
```

Write `export_finbert.py` in three sections:

**Step 1 — Load and verify**
Load `ProsusAI/finbert` from HuggingFace. Run 3 test sentences (one positive, one negative, one neutral). Print the softmax probabilities and confirm they sum to 1.0.

**Step 2 — Validate against FinancialPhraseBank**
Load `all-data.csv` from your Kotlin resources folder. Run all 4,845 sentences through FinBERT. Compute accuracy against the ground-truth labels. Target is above 85% — FinBERT achieves ~88% on this dataset out of the box.

**Step 3 — Export to ONNX**
Use `torch.onnx.export()` with dynamic axes for variable sequence length. Output as `finbert.onnx`. Verify the exported model with `onnxruntime` to confirm outputs match the PyTorch version.

After export, copy `finbert.onnx` into `app/src/main/resources/` in your Kotlin project. The model file is approximately 400MB — add it to `.gitignore` and document in the README that it must be generated locally before running.

### Should you fine-tune?
Optional but worth considering. Fine-tuning FinBERT on the FinancialPhraseBank CSV with a custom train/validation split would push accuracy from ~88% to 91–93% and gives you something concrete to measure and document. It requires only a CPU and takes 20–30 minutes using HuggingFace `Trainer`. Only attempt this after the base export and validation are working correctly.

---

## 6. Remaining Build Sequence

Complete these in order. Do not skip ahead.

### Phase 2A — ONNX model (PyCharm)
- [ ] Write `export_finbert.py` — load, validate, export
- [ ] Run `validate_model.py` against FinancialPhraseBank — confirm accuracy above 85%
- [ ] Copy `finbert.onnx` to `Horizon/app/src/main/resources/`
- [ ] Optional: write `finetune_finbert.py` and document accuracy improvement

### Phase 2B — ONNX inference in Kotlin
- [ ] Add `com.microsoft.onnxruntime:onnxruntime:1.17.3` to `build.gradle.kts`
- [ ] In `SentimentML.kt`: load `finbert.onnx` via `OrtEnvironment`, replace counting logic with a real forward pass
- [ ] Tokenize input text using a Java BERT tokenizer
- [ ] Run `./gradlew run` and confirm polarity scores reflect real inference

### Phase 2C — Lambda handler
- [ ] Implement `LambdaHandler.kt` as `RequestHandler<SQSEvent, Unit>`
- [ ] Add `aws-lambda-java-core` and `aws-lambda-java-events` dependencies
- [ ] Parse SQS message body as `QuartrTranscriptResponse` JSON
- [ ] For each paragraph: tokenize → ONNX inference → compute polarity → write to DynamoDB
- [ ] Test locally with a mock SQS event JSON

### Phase 2D — Fat JAR and LocalStack Lambda deploy
- [ ] Add `shadowJar` plugin to `build.gradle.kts`
- [ ] Run `./gradlew shadowJar` to produce a self-contained deployable
- [ ] Deploy to LocalStack Lambda with `aws lambda create-function`
- [ ] Create SQS queue in LocalStack and wire as Lambda trigger
- [ ] Send a test message and verify end-to-end flow to DynamoDB

### Phase 2E — API Gateway ingestion layer
- [ ] Create LocalStack API Gateway `POST /ingest` endpoint
- [ ] Wire to SQS queue as integration target
- [ ] Send a mock Quartr transcript payload
- [ ] Confirm full flow: API Gateway → SQS → Lambda → ONNX → DynamoDB

### Phase 3 — Portfolio documentation
- [ ] Write `README.md` with architecture diagram, setup instructions, polarity formula
- [ ] Document LocalStack vs real AWS switch (one endpoint URL change)
- [ ] Add to GitHub with `.gitignore` covering `finbert.onnx`, `build/`, `.gradle/`, AWS credentials

---

## 7. Key Commands

```bash
# LocalStack
localstack start -d
localstack status services
aws dynamodb scan --table-name horizon-sentiment --endpoint-url http://localhost:4566

# Kotlin project
cd ~/projects/Horizon
./gradlew run --no-configuration-cache
./gradlew build --no-configuration-cache
./gradlew shadowJar                          # Phase 2D

# Python model project
cd ~/projects/HorizonML
python export_finbert.py
python validate_model.py
cp finbert.onnx ../Horizon/app/src/main/resources/
```

---

## 8. Resume Prompt

Paste this into the next Claude session:

> I am building Project Horizon — an event-driven fintech sentiment pipeline in Kotlin on WSL2. The pipeline ingests financial news, runs FinBERT ONNX inference, and writes polarity scores to a DynamoDB table. Phase 1 is complete: DTOs, SentimentML engine, DynamoWriter, and LocalStack are all working end-to-end. I need to implement Phase 2A: export ProsusAI/finbert to ONNX in PyCharm, validate it against the FinancialPhraseBank dataset, and prepare finbert.onnx for integration into the Kotlin ONNX runtime layer. See attached handoff document for full context.
