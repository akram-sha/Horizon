# Project Horizon

An event-driven financial sentiment pipeline. It ingests financial news articles, runs them through FinBERT (a BERT model pre-trained on financial text), and writes per-ticker polarity scores to DynamoDB. The end goal is a serverless pipeline on AWS where financial news flows in via SQS, gets scored, and lands in a table you can query for sentiment signals.

The stack is Kotlin on the JVM for the pipeline, Python for the ML model export, and LocalStack to simulate AWS locally.

---

## Datasets

| Dataset | Details                                                                                                                    |
|---|----------------------------------------------------------------------------------------------------------------------------|
| Polygon Financial News | 5,548 articles bundled in `app/src/main/resources/polygon_news.json`                                                       |
| FinancialPhraseBank | 4,846 labeled sentences in `app/src/main/resources/FinancialPhraseBank/` — used by `export_finbert.py` for validation only |

---

## What's built

| Component | Status | Notes |
|---|---|---|
| Kotlin project structure | Done | `dto/`, `model/`, `storage/`, `handler/`, `App.kt` |
| `DTOs.kt` | Done | `PolygonArticle`, `Insight`, `Publisher` |
| `SentimentScorer.kt` | Done | `fun interface` — injectable, lambda-stub friendly |
| `PolarityStore.kt` | Done | `fun interface` — injectable, lambda-stub friendly |
| `SentimentML.kt` | Done | Real FinBERT ONNX inference — polarity = P(pos) − P(neg) ∈ [−1.0, +1.0] |
| `DynamoWriter.kt` | Done | Writes ticker, timestamp, polarity score, article count, TTL — env-var driven config |
| `App.kt` — `runLeaderboard` | Done | Orchestration with injected interfaces — scores each article once, aggregates per ticker |
| `LambdaHandler.kt` | Stub | Phase 2C |
| Polygon news dataset | Done | 5,548 articles in `resources/polygon_news.json` |
| LocalStack (Docker) | Done | All AWS services running locally on `localhost:4566` |
| DynamoDB table | Done | `horizon-sentiment`, PK=`ticker`, SK=`timestamp` |
| End-to-end local run | Done | Articles → FinBERT → polarity → DynamoDB write |
| FinBERT ONNX export | Done | `export_finbert.py`, 88.94% accuracy on FinancialPhraseBank, opset 18 |
| `finbert.onnx` | Local only | Not committed (400MB+) — generate with `export_finbert.py` then copy with `setup.sh` |
| Shadow JAR | Done | `./gradlew shadowJar` → `app/build/libs/app-all.jar` (Lambda-ready fat JAR) |
| Orchestration tests | Done | `RunLeaderboardTest` — 4 tests, no model needed, run in CI |
| Model integration tests | Done | `AppTest` — skips cleanly in CI when model absent; run locally after `setup.sh` |
| Sentence splitting + confidence threshold | Next | Phase 2B remaining — split text before inference, discard low-confidence sentences |
| `LambdaHandler.kt` impl | Todo | Phase 2C |
| Fat JAR + Lambda deploy | Todo | Phase 2D |
| SQS + API Gateway | Todo | Phase 2E |

---

## How the pipeline works

The polarity formula at the core:

```
polarity = P(Positive) − P(Negative)
```

FinBERT outputs three probabilities (positive, negative, neutral) that sum to 1. This collapses them to a single float in `[−1.0, +1.0]`. A score of +0.8 is strongly positive, -0.4 is mildly negative, 0.0 is neutral.

`runLeaderboard` in `App.kt` drives the full pipeline:

1. Loads all articles from `polygon_news.json`
2. Scores each article once via `SentimentML.computePolarity` (FinBERT ONNX forward pass)
3. Distributes each score to every ticker tagged on that article
4. Filters to tickers with ≥5 article mentions (reduces noise)
5. Averages scores per ticker, sorts descending, takes the top 10
6. Writes each top-10 ticker with its average polarity to DynamoDB

Both `SentimentML` and `DynamoWriter` are injected as interfaces (`SentimentScorer`, `PolarityStore`) so `runLeaderboard` is fully testable with lambda stubs and no real model.

---

## Repo structure

```
Horizon/
  app/src/main/kotlin/org/horizon/
    dto/DTOs.kt                      — PolygonArticle, Insight, Publisher
    model/SentimentScorer.kt         — fun interface SentimentScorer
    model/SentimentML.kt             — FinBERT ONNX inference engine
    storage/PolarityStore.kt         — fun interface PolarityStore
    storage/DynamoWriter.kt          — DynamoDB writes via AWS SDK v2
    handler/LambdaHandler.kt         — SQS handler stub (Phase 2C)
    App.kt                           — main(), runLeaderboard()
  app/src/main/resources/
    polygon_news.json                — 5,548 Polygon articles (committed)
    FinancialPhraseBank/             — 4,846 labeled sentences (committed, used by export_finbert.py)
    finbert.onnx                     — NOT in git — generate locally (see below)
    finbert.onnx.data                — NOT in git — ONNX external data file, same generation step
    tokenizer.json                   — NOT in git — copied by setup.sh
    tokenizer_config.json            — NOT in git — copied by setup.sh
  app/src/test/kotlin/org/horizon/
    AppTest.kt                       — model integration tests, skip cleanly when model absent
    RunLeaderboardTest.kt            — orchestration tests, always run in CI
  app/build.gradle.kts
  gradle/libs.versions.toml

  horizon-ml/                        — Python subproject
    export_finbert.py                — loads ProsusAI/finbert, validates on FinancialPhraseBank, exports ONNX
    requirements.txt                 — GPU (CUDA) dependencies
    requirements-cpu.txt             — CPU dependencies (Mac / no GPU)
    .env                             — NOT committed — put your HF_TOKEN here

  setup.sh                           — copies model + tokenizer from horizon-ml/ into app resources
  .env.example                       — template for required environment variables
```

---

## Prerequisites (Mac)

Work through these in order.

---

### 1. Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

After it finishes, follow any instructions it prints about adding Homebrew to your PATH (Apple Silicon Macs need an extra step).

Verify: `brew --version`

---

### 2. Git

```bash
git --version   # likely already installed
```

Configure your identity:

```bash
git config --global user.name "Sebastian"
git config --global user.email "your@email.com"
```

---

### 3. Cursor

Download from [cursor.com](https://cursor.com) and drag to Applications. Once open, install:
- **Kotlin** extension (`mathiasfrohlich.Kotlin`)
- **Gradle for Java** extension (`vscjava.vscode-gradle`)

Open the `Horizon/` folder via **File → Open Folder**.

---

### 4. Java 21

**Install SDKMAN:**

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

**Install Java 21:**

```bash
sdk install java 21.0.5-tem
sdk use java 21.0.5-tem
java -version   # should say openjdk version "21.x.x"
```

Gradle is bundled in the repo as `./gradlew` — you don't need to install it separately.

---

### 5. Python 3.11+

Python is only needed for the ML export step. Check: `python3 --version`

If needed: `brew install python@3.12`

---

### 6. Docker Desktop

LocalStack runs inside Docker. Download [Docker Desktop for Mac](https://www.docker.com/products/docker-desktop/), install, and launch it. Wait for the menu bar icon to show "Docker Desktop is running".

Verify:

```bash
docker --version
docker ps   # should return an empty table, not an error
```

---

### 7. LocalStack

```bash
brew install localstack
localstack --version
```

LocalStack runs as a Docker container. Docker Desktop must be running before you start LocalStack.

---

### 8. AWS CLI

```bash
brew install awscli
aws --version
```

**Configure a LocalStack profile:**

```bash
aws configure --profile localstack
```

Enter:
```
AWS Access Key ID:     test
AWS Secret Access Key: test
Default region:        us-east-1
Output format:         json
```

This creates a `localstack` named profile. It won't touch any real AWS account.

---

### 9. HuggingFace account

Needed to download ProsusAI/finbert during the ONNX export step.

1. Sign up at [huggingface.co](https://huggingface.co)
2. **Settings → Access Tokens → New token** — Read access, name it `horizon-dev`
3. Copy the token — you'll use it in the Python setup below

---

## Setup

### Clone and configure

```bash
git clone https://github.com/akram-sha/Horizon.git
cd Horizon
cp .env.example .env   # edit this file — add your HF_TOKEN etc. if needed
```

### Start LocalStack

Make sure Docker Desktop is running, then:

```bash
localstack start -d
localstack status services   # wait until dynamodb shows "running"
```

### Create the DynamoDB table

Run once. You'll need to re-run this after each `localstack stop` / `localstack start` cycle since LocalStack doesn't persist state by default.

```bash
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
```

### Run the pipeline

```bash
DYNAMODB_ENDPOINT=http://localhost:4566 ./gradlew run
```

`DYNAMODB_ENDPOINT` is required — without it the app targets real AWS and will fail with a credentials error. Gradle downloads dependencies on first run (takes a couple of minutes).

Verify the writes landed:

```bash
aws dynamodb scan \
  --table-name horizon-sentiment \
  --endpoint-url http://localhost:4566 \
  --profile localstack
```

You should see up to 10 items with `ticker`, `timestamp`, `polarity_score`, and `article_count` fields.

---

## Generating finbert.onnx

The ONNX model is too large to commit (~400MB + external data file). Generate it once locally.

### Set up the Python environment

```bash
cd horizon-ml
python3 -m venv .venv
source .venv/bin/activate

# GPU (CUDA) — Linux with NVIDIA GPU:
pip install -r requirements.txt

# CPU — Mac or no GPU:
pip install -r requirements-cpu.txt
```

### Add your HuggingFace token

```bash
echo "HF_TOKEN=your_token_here" > .env
```

This file is gitignored — never commit it.

### Run the export

```bash
python export_finbert.py
```

This will:
1. Download `ProsusAI/finbert` from HuggingFace (~440MB, cached after first run)
2. Validate it against FinancialPhraseBank — expect ~88.9% accuracy
3. Export to `horizon-ml/finbert.onnx` using ONNX opset 18 with external data format
4. Verify ONNX outputs match PyTorch (max logit diff ~6e-06)

Takes 5–10 minutes on first run.

### Copy into the project

```bash
cd ..
bash setup.sh
```

`setup.sh` copies `finbert.onnx`, `finbert.onnx.data`, `tokenizer.json`, and `tokenizer_config.json` from `horizon-ml/` into `app/src/main/resources/`. It checks all source files exist before copying.

---

## Key commands

```bash
# LocalStack (Docker Desktop must be running)
localstack start -d
localstack status services
localstack stop

# DynamoDB
aws dynamodb create-table ...   # see Setup section above
aws dynamodb scan --table-name horizon-sentiment \
  --endpoint-url http://localhost:4566 --profile localstack

# Kotlin pipeline
DYNAMODB_ENDPOINT=http://localhost:4566 ./gradlew run
./gradlew build
./gradlew test
./gradlew shadowJar   # fat JAR at app/build/libs/app-all.jar

# Python — ML export (activate venv first)
cd horizon-ml && source .venv/bin/activate
python export_finbert.py
```

---

## What's next (Phase 2B remaining)

The core FinBERT inference pipeline is complete. The remaining Phase 2B work improves accuracy:

- **Sentence splitting** — FinBERT was trained on short financial sentences (~20-30 tokens), not multi-sentence paragraphs. Splitting article `title + description` before inference and averaging per-sentence scores significantly improves signal quality.
- **Confidence threshold** — discard sentences where the model's max class probability is below 0.65. Low-certainty predictions (where the model is spread ~0.34/0.33/0.33 across classes) dilute the average without adding signal.

After that, Phase 2C wires up `LambdaHandler.kt` to consume SQS events and feed the full pipeline end-to-end.

---

## Architecture (where this is heading)

```
Financial news API  (Polygon, Reuters, etc.)
    │
    ▼
POST /ingest  (API Gateway)
    │
    ▼
SQS queue  (horizon-ingest)
    │
    ▼
Lambda  (LambdaHandler.kt)
    │
    ├── tokenize → FinBERT ONNX → softmax → polarity per sentence
    ├── average sentence scores (confidence-filtered)
    └── aggregate per ticker
    │
    ▼
DynamoDB  (horizon-sentiment)
    │  ticker + timestamp + polarity_score + article_count
    ▼
Query layer  (to be built)
```

Everything currently runs locally via LocalStack. Switching to real AWS is an env-var change — the AWS SDK calls are identical.
