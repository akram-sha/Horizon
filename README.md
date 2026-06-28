# Project Horizon

An event-driven financial sentiment pipeline. It ingests financial news articles, runs them through FinBERT (a BERT model pre-trained on financial text), and writes per-ticker polarity scores to DynamoDB. The end goal is a serverless pipeline on AWS where earnings call transcripts flow in via SQS, get scored, and land in a table you can query for sentiment signals.

The stack is Kotlin on the JVM for the pipeline, Python for the ML model export, and LocalStack to simulate AWS locally.

---

## Datsets

| Dataset                               | Link |
|---------------------------------------|--|
| Sentiment Analysis for Financial News | https://www.kaggle.com/datasets/ankurzing/sentiment-analysis-for-financial-news |
| Financial News with Ticker-Level Sentiment | https://www.kaggle.com/datasets/rdolphin/financial-news-with-ticker-level-sentiment?resource=download |

---

## What's built so far

| Component | Status | Notes |
|---|---|---|
| Kotlin project structure | ✅ Done | `dto/`, `model/`, `storage/`, `handler/`, `App.kt` |
| `DTOs.kt` | ✅ Done | `PolygonArticle`, `Insight`, `Publisher` |
| `SentimentML.kt` | ✅ Done | Placeholder polarity logic — to be replaced with ONNX in Phase 2B |
| `DynamoWriter.kt` | ✅ Done | Writes ticker, timestamp, polarity score, article count, TTL |
| `LambdaHandler.kt` | ✅ Done | Empty stub — Phase 2C |
| Polygon news dataset | ✅ Done | 5,548 articles in `resources/polygon_news.json` |
| FinancialPhraseBank | ✅ Done | 4,846 labeled sentences in `resources/` |
| LocalStack (Docker) | ✅ Done | All AWS services running locally on `localhost:4566` |
| DynamoDB table | ✅ Done | `horizon-sentiment`, PK=`ticker`, SK=`timestamp` |
| End-to-end local run | ✅ Done | Articles → polarity → DynamoDB write |
| FinBERT ONNX export | ✅ Done | `export_finbert.py`, 88.94% accuracy, opset 18 |
| `finbert.onnx` | ⚠️ Local only | Not committed (400MB+) — generate it yourself (see below) |
| ONNX inference in Kotlin | ⬜ Next | Phase 2B — replace placeholder with real FinBERT forward pass |
| `LambdaHandler.kt` impl | ⬜ Todo | Phase 2C |
| Fat JAR + Lambda deploy | ⬜ Todo | Phase 2D |
| SQS + API Gateway | ⬜ Todo | Phase 2E |

The polarity formula at the core of the pipeline is:

```
polarity = P(Positive) − P(Negative)
```

FinBERT outputs three probabilities (positive, negative, neutral) that sum to 1. This formula collapses them to a single float in `[−1.0, +1.0]`. Currently `SentimentML.kt` uses a placeholder that counts sentiment labels from the Polygon dataset instead — the formula is right, the input source changes once ONNX is wired in.

---

## Repo structure

```
Horizon/
  app/src/main/kotlin/org/horizon/
    dto/DTOs.kt                  — PolygonArticle, Insight, Publisher
    model/SentimentML.kt         — polarity engine (placeholder)
    storage/DynamoWriter.kt      — DynamoDB writes via AWS SDK v2
    handler/LambdaHandler.kt     — SQS handler stub
    App.kt                       — main(), wires everything together
  app/src/main/resources/
    polygon_news.json            — 5,548 Polygon articles
    FinancialPhraseBank/         — 4,846 labeled sentences
    finbert.onnx                 — NOT in git, generate locally (see below)
  app/src/test/kotlin/org/horizon/
    AppTest.kt
  app/build.gradle.kts
  gradle/libs.versions.toml

  horizon-ml/                    — Python subproject
    export_finbert.py            — loads FinBERT, validates, exports to ONNX
    requirements.txt
    .env                         — NOT committed, add your HF_TOKEN here
```

---

## Prerequisites (Mac)

Work through these in order. Each section tells you what you're installing and why.

---

### 1. Homebrew

Homebrew is the standard package manager for Mac — it installs and manages most of the other tools below. If you've used it before, skip this.

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

After it finishes, follow any instructions it prints about adding Homebrew to your PATH (this varies by chip — Apple Silicon Macs need an extra step).

Verify:

```bash
brew --version
```

---

### 2. Git

You almost certainly have this already. Check:

```bash
git --version
```

If not: `brew install git`

Then configure your identity so commits are attributed correctly:

```bash
git config --global user.name "Sebastian"
git config --global user.email "your@email.com"
```

---

### 3. Cursor

Cursor is the IDE for this project — it's VS Code with AI built in, and it works well with Claude Code.

Download it from [cursor.com](https://cursor.com) and drag it to your Applications folder. When you first open it, sign in and install the recommended extensions if prompted.

For this project, once Cursor is open:
- Open the `Horizon/` folder via **File → Open Folder**
- Install the **Kotlin** extension (search in the Extensions panel: `mathiasfrohlich.Kotlin`)
- Install the **Gradle for Java** extension (`vscjava.vscode-gradle`)

---

### 4. Java 21

The Kotlin pipeline runs on Java 21 (Temurin build). The easiest way to manage Java versions on Mac is SDKMAN, which lets you switch between versions without breaking anything.

**Install SDKMAN:**

```bash
curl -s "https://get.sdkman.io" | bash
```

Close and reopen your terminal, then:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

**Install Java 21:**

```bash
sdk install java 21.0.5-tem
sdk use java 21.0.5-tem
```

Verify:

```bash
java -version
# should say: openjdk version "21.x.x"
```

> Gradle (the build tool) is included in the repo as a wrapper (`./gradlew`) — you don't need to install it separately.

---

### 5. Python 3.11+

Python is used for the ML side — exporting the FinBERT model to ONNX format. Check what you have:

```bash
python3 --version
```

Anything 3.11 or above is fine. If you need to install or upgrade:

```bash
brew install python@3.12
```

After installing, verify the right version is on your PATH:

```bash
python3 --version   # should say 3.11 or 3.12
```

---

### 6. Docker Desktop

LocalStack (our local AWS simulator) runs inside Docker. You need Docker Desktop running in the background whenever you work on the project.

1. Download [Docker Desktop for Mac](https://www.docker.com/products/docker-desktop/) — choose the right chip (Apple Silicon or Intel)
2. Open the `.dmg`, drag Docker to Applications, and launch it
3. Wait for the Docker icon in your menu bar to show "Docker Desktop is running"

Verify from the terminal:

```bash
docker --version
docker ps   # should return an empty table, not an error
```

---

### 7. LocalStack

LocalStack runs AWS services (DynamoDB, SQS, Lambda, API Gateway) on your machine so you don't need a real AWS account to develop. Everything talks to `http://localhost:4566` instead of the real AWS endpoints.

```bash
brew install localstack
```

Verify:

```bash
localstack --version
```

> LocalStack runs as a Docker container under the hood. Docker Desktop must be running before you start LocalStack.

---

### 8. AWS CLI

The AWS CLI is how you interact with LocalStack from the terminal — creating tables, querying data, checking what's running. Even though we're not using real AWS yet, the CLI is the same tool.

```bash
brew install awscli
```

Verify:

```bash
aws --version
```

**Configure a LocalStack profile.** LocalStack doesn't validate credentials, so you just need something in place. Run:

```bash
aws configure --profile localstack
```

Enter these exactly:

```
AWS Access Key ID:     test
AWS Secret Access Key: test
Default region:        us-east-1
Output format:         json
```

This creates a named profile called `localstack` that all the commands in this README use. It won't touch any real AWS account.

---

### 9. HuggingFace account

HuggingFace hosts the FinBERT model. You need a free account and an access token to download it.

1. Sign up at [huggingface.co](https://huggingface.co)
2. Go to **Settings → Access Tokens → New token**
3. Name it anything (e.g. `horizon-dev`), set the role to **Read**, and create it
4. Copy the token — you'll use it in the Python setup below

---

## Setup

Once all prerequisites are installed, do this once to get the project running.

### Clone the repo

```bash
git clone https://github.com/akram-sha/Horizon.git
cd Horizon
```

### Start LocalStack

Make sure Docker Desktop is running first, then:

```bash
localstack start -d
```

The `-d` flag runs it in the background. Wait a few seconds, then check it's up:

```bash
localstack status services
```

You should see `dynamodb` listed as `running`. If services show as `starting`, wait another 10 seconds and run it again.

### Create the DynamoDB table

This only needs to be done once. LocalStack doesn't persist state between restarts by default, so you'll need to re-run this after each `localstack stop` / `localstack start` cycle.

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

### Run the Kotlin pipeline

```bash
./gradlew run --no-configuration-cache
```

Gradle will download dependencies on first run — this takes a couple of minutes. After that it should be fast. You'll see articles being processed and polarity scores written to DynamoDB.

Verify the writes landed:

```bash
aws dynamodb scan \
  --table-name horizon-sentiment \
  --endpoint-url http://localhost:4566 \
  --profile localstack
```

You should see a list of items with `ticker`, `timestamp`, `polarity_score`, and `article_count` fields.

---

## Generating finbert.onnx

The ONNX model file is too large to commit to git (400MB+). You generate it once locally from HuggingFace, then copy it into the project resources. This step is only required for Phase 2B — if you're just running the current pipeline, skip it for now.

### Set up the Python environment

```bash
cd horizon-ml
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

The `requirements.txt` pins all dependencies. The install will take a few minutes on first run as it downloads PyTorch and the transformer libraries.

### Add your HuggingFace token

```bash
echo "HF_TOKEN=your_token_here" > .env
```

Replace `your_token_here` with the token you created in the HuggingFace step above. This file is in `.gitignore` — never commit it.

### Run the export

```bash
python export_finbert.py
```

This will:
1. Download `ProsusAI/finbert` from HuggingFace (~440MB, cached after first run)
2. Validate it against FinancialPhraseBank — expect ~88.9% accuracy
3. Export to `horizon-ml/finbert.onnx` using ONNX opset 18
4. Verify the ONNX output matches PyTorch (logit diff should be around 6e-06)

The whole thing takes 5–10 minutes depending on your connection.

### Copy it into the project

```bash
cp finbert.onnx ../app/src/main/resources/finbert.onnx
cd ..
```

---

## Key commands

```bash
# LocalStack — run these with Docker Desktop open
localstack start -d
localstack status services
localstack stop

# DynamoDB
aws dynamodb create-table ...          # see Setup section above
aws dynamodb scan \
  --table-name horizon-sentiment \
  --endpoint-url http://localhost:4566 \
  --profile localstack

# Kotlin pipeline
./gradlew run --no-configuration-cache
./gradlew build --no-configuration-cache
./gradlew test

# Python (always activate the venv first)
cd horizon-ml
source .venv/bin/activate
python export_finbert.py
```

---

## What's next (Phase 2B)

The current `SentimentML.kt` fakes polarity by counting sentiment labels from the Polygon dataset. Phase 2B replaces this with a real FinBERT forward pass:

- Add `onnxruntime` and DJL tokenizers to `build.gradle.kts`
- Load `finbert.onnx` from the classpath via `OrtEnvironment`
- Tokenize each article, run it through the model, apply softmax, compute `P(pos) − P(neg)`

If you want to pick this up, `SentimentML.kt` is the file to work in. The `computePolarity()` function is where the ONNX forward pass goes.

---

## Architecture (where this is heading)

```
Quartr API
    │
    ▼
POST /ingest  (API Gateway)
    │
    ▼
SQS queue  (horizon-ingest)
    │
    ▼
Lambda  (LambdaHandler.kt)
    │  tokenize → FinBERT ONNX → softmax → polarity
    ▼
DynamoDB  (horizon-sentiment)
    │  ticker + timestamp + polarity_score
    ▼
Query layer  (to be built)
```

Everything currently runs locally via LocalStack. Switching to real AWS is a one-line endpoint URL change — the AWS SDK calls are identical.
