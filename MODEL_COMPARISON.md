# Model Comparison: Horizon (ProsusAI/finbert) vs Seb (yiyanghkust/finbert-tone)

> End goal: use sentiment scores as a trading signal — specifically to inform which tickers
> to enter, exit, or avoid based on the market's emotional reaction to news.

---

## What each model actually is

Both models are **BERT-base-uncased** fine-tuned on financial text. The architecture is
identical — 12 transformer layers, 110M parameters, 512-token max input. The difference
is training data composition and how each is being used.

| | Horizon (ProsusAI/finbert) | Seb (yiyanghkust/finbert-tone) |
|---|---|---|
| Base model | BERT-base-uncased | BERT-base-uncased |
| Fine-tuning data | ~10k Reuters financial news sentences + analyst reports | ~10k financial news sentences (similar corpus, different sampling) |
| Validated accuracy | 88.94% on FinancialPhraseBank (all-data.csv) | Not independently validated in this project |
| Label space | positive / negative / neutral | Positive / Negative / Neutral |
| Output format | Continuous polarity: P(pos) − P(neg) ∈ [−1.0, +1.0] | Hard label + confidence score (e.g. POSITIVE / 0.93) |
| Input | `title + description` from Polygon articles | Headline-only from Yahoo Finance / CNBC RSS |
| Ticker association | Yes — via `PolygonArticle.tickers` | No |
| News domain | Company-level: earnings, M&A, bankruptcy, revenue | Macro: Fed policy, geopolitics, unemployment |
| Inference runtime | ONNX (production Kotlin) | HuggingFace pipeline (Python Colab) |
| Confidence filter | Planned at 0.65 (per sentence after splitting) | 0.85 applied post-inference |

---

## What each model tracks

### Horizon tracks company-level micro events

The news Polygon provides — and the sentences FinancialPhraseBank was built from — are
company-specific financial events:

- **Earnings:** "Revenue beat expectations by 12%, driven by strong cloud growth"
- **M&A:** "The acquisition of XYZ Corp was completed at a 30% premium"
- **Credit events:** "Moody's downgraded the company's debt to junk status"
- **Operations:** "The plant closure will result in 2,000 job cuts"
- **Guidance:** "Management raised full-year guidance above consensus estimates"

Each article carries a list of tickers it pertains to. The model scores each article,
those scores accumulate per ticker, and the leaderboard surfaces which companies have
the most positive or negative news coverage over the current window.

### Seb's model tracks macro regime signals

The RSS feeds Seb consumes (Yahoo Finance top news, CNBC Economy) carry macro events:

- **Monetary policy:** "Federal Reserve signals it may pause interest rate hikes"
- **Geopolitics:** "Tensions escalate in the South China Sea as naval exercises begin"
- **Economic data:** "Unemployment dropped significantly, signaling a strengthening economy"
- **Commodity shocks:** "Oil prices surge 5% following pipeline disruption in the Middle East"

These are market-wide events that affect risk appetite broadly — not a single company.

---

## Why the domain mismatch matters for both models

Both `ProsusAI/finbert` and `yiyanghkust/finbert-tone` were trained on the same category
of data: short, clear financial phrases from Reuters news and analyst reports. They are
well-calibrated for company-level micro finance.

When you feed either model macro or geopolitical text, two specific failures appear:

### Failure 1: Inverted domain polarity

"Unemployment **dropped** significantly" → the model has seen "dropped" hundreds of times
in training data where it meant earnings dropped, revenue dropped, stock price dropped —
all negative. It applies the same weight here, scoring the sentence negative when the
economic interpretation is positive (falling unemployment = strengthening labour market).

This is not a bug in the model. It is the model generalising correctly from its training
distribution to an out-of-distribution input.

### Failure 2: Lexical bias ("this is promising")

"Despite a 40% revenue decline, the cost-cutting measures look **promising**"

In financial analyst reports, "promising" almost exclusively appears in positive contexts.
The model assigns it near-certain positive weight, and the hedging phrase "despite a 40%
revenue decline" does not fully counteract it. The sentence is likely scored positive
when it should be negative or neutral.

This is the core issue Seb identified. It affects both models equally. It is partially
addressed by:
- Sentence splitting (the "promising" sentence becomes its own input without the
  negative context dragging it, but confidence thresholding will catch the uncertainty
  when the surrounding sentences contradict it)
- Confidence thresholding (a truly ambiguous sentence will produce a spread probability
  distribution — the model will be uncertain — and gets discarded)

### Failure 3: Missing causal chains (geopolitics)

"Tensions escalate in the South China Sea" → the model has no training examples linking
geopolitical events to market outcomes. The causal chain (military tension → supply chain
risk → commodity prices → equity volatility) exists nowhere in FinancialPhraseBank.

Neither model can reason about this. Seb's context injection workaround (appending
"This creates major market uncertainty and risk.") forces the model to see negative
financial language, but it fires on de-escalation headlines too ("US-China tensions ease
as trade deal is signed" → war trigger found → forced negative → wrong).

---

## Which is better for a trading signal

### For stock-level trade decisions: Horizon wins clearly

A trading signal needs to answer: **which specific security should I be long or short,
and by how much?**

Horizon answers this directly:
- Ticker-associated scores → you know the signal is about AAPL, not the market broadly
- Continuous polarity in [-1.0, +1.0] → you know if it's -0.85 (strong sell signal)
  vs -0.12 (mild noise, probably ignore)
- Article count per ticker → you know if the signal is backed by 30 articles or 5
- Aggregated over a time window → smooths one-article spikes

Seb's model cannot drive a stock-level trade. There is no ticker routing. A NEGATIVE
score on "Oil prices surge after pipeline disruption" tells you something about energy
sector risk but not whether to buy XOM or CVX or hedge with USO.

### For macro / market-timing decisions: Seb's approach is more relevant

If the trading strategy is:
- Risk-on / risk-off: go long SPY when macro sentiment is positive, move to cash when negative
- Sector rotation: energy positive → overweight XLE; financials positive → overweight XLF
- Hedging: add put protection when geopolitical tension score spikes

Then macro-level sentiment is the right signal, and Seb's pipeline is closer to the
right tool — though the implementation needs to be more rigorous than RSS + hard labels.

### The most robust trading signal combines both

```
Macro layer (Seb-style)          →  market regime: risk-on / risk-off
    +
Micro layer (Horizon-style)      →  stock selection within the regime
    =
Position: long AAPL (+0.81) in a risk-on regime, sized by polarity magnitude
```

This is how institutional quant desks structure news-based signals — macro sets the
regime, micro selects the names.

---

## Specific output differences and their trading implications

### Hard labels vs continuous polarity

Seb outputs: `POSITIVE | 0.93`  
Horizon outputs: `+0.73`

For trading, the continuous score matters significantly.

| Scenario | Hard label | Continuous polarity |
|---|---|---|
| Strong buy signal | POSITIVE | +0.81 → large position |
| Weak buy signal | POSITIVE | +0.14 → small position or ignore |
| Catastrophic event | NEGATIVE | -0.94 → immediate exit |
| Mild negative | NEGATIVE | -0.11 → hold, monitor |
| Ambiguous | NEUTRAL | -0.03 → no action |

A trading system that sizes positions by polarity magnitude will outperform one that
treats all positives identically. Hard labels throw away the information needed to
size trades.

### Confidence scores are not the same as polarity magnitude

Seb's confidence score (0.93) measures how certain the model is about its label
choice — it is not the same as how strongly positive or negative the news is.

A mildly positive sentence with unambiguous language ("The dividend was maintained")
might score POSITIVE / 0.97 — high confidence but low materiality.

A catastrophically negative event with complex language ("The regulatory investigation,
while not yet resulting in formal charges, raises significant questions about the
company's accounting practices") might score NEGATIVE / 0.71 — lower confidence but
far more market-moving.

Polarity magnitude (our [-1,1] score) captures materiality better than confidence.
Confidence is useful for filtering noise (discard predictions below 0.65), not for
sizing trades.

### No ticker routing is disqualifying for stock trading

Seb's pipeline produces:
```
NEWS: Tech companies report record profits despite global supply chain issues.
---> Sentiment: Positive | Confidence: 0.9121
```

This is unactionable. Which tech companies? AAPL? NVDA? A small-cap hardware supplier?
"Tech companies" is not a tradeable instrument.

Horizon's pipeline produces:
```
▲ NVDA  +0.743  (38 articles)
▲ AAPL  +0.681  (29 articles)
▼ INTC  -0.412  (22 articles)
```

These are direct trade candidates.

---

## What each model misses that matters for trading

### What both models miss

**Recency weighting** — a 2-week-old earnings miss has already been priced in. Sentiment
from stale news is not a trading signal. Neither pipeline currently weights by article
age or filters out news older than a given window. For live trading, only news from the
last 24–48 hours is typically relevant.

**Market cap context** — a bankruptcy filing from a $500M small-cap moves that stock 80%
but does not move the S&P. The same event from a $2T mega-cap is systemic. Neither model
weights the magnitude of the sentiment signal by the size of the company it refers to.

**Source credibility** — a Reuters earnings report and a Motley Fool opinion piece both
enter the model as text. Reuters content is more likely to reflect real information flow
(analysts, insiders, official filings). Opinion pieces are more likely to echo existing
sentiment rather than create it.

**Already-priced events** — if AAPL earnings were released yesterday and the stock is
already up 8%, the sentiment signal from today's coverage of those earnings is
retrospective, not predictive. Sentiment is most valuable before the price reaction,
not after.

### What Horizon specifically misses (and can add)

- Sentence splitting → currently the full article text is one inference call. After
  splitting, each financial sentence is scored independently, confidence-filtered, and
  averaged. This eliminates the lexical bias problem for most cases.
- Rolling baseline → is NVDA's current polarity of +0.74 high or low for NVDA? Without
  a historical baseline you cannot tell if the signal is elevated or normal. A ticker
  at +0.74 that is normally at +0.60 is a stronger buy signal than one that is normally
  at +0.80 and is now declining toward +0.74.
- Event deduplication → if 20 articles all cover the same earnings release, they are not
  20 independent signals. They are one event with 20 reporters. Deduplication by event
  (same ticker, same day, same event type) avoids over-weighting a single piece of news.

### What Seb's approach specifically misses

- Any ticker routing
- Magnitude information (collapsed to hard labels)
- Domain-appropriate model for macro content (FinBERT is micro-finance calibrated)
- A solution to context injection breaking on de-escalation headlines

---

## Summary: which to use for trading

| Criterion | Horizon | Seb |
|---|---|---|
| Stock-level signal | Yes — ticker-associated | No |
| Magnitude information | Yes — continuous [-1,1] | No — collapsed to label |
| Macro regime signal | No | Yes |
| Production-ready | Yes — ONNX, Kotlin | No — Colab notebook |
| Validated accuracy | 88.94% on FinancialPhraseBank | Not independently validated |
| Handles geopolitics | No (same limitation as Seb) | Partially (hacky injection) |
| Trading actionability | High for stock selection | Low without ticker routing |

For the stated goal — trading based on sentiment — Horizon's pipeline is the more
useful foundation. The continuous polarity score, ticker association, and article-count
backing make the output directly actionable for position sizing.

Seb's macro signals are a genuine complement, not a replacement. The right architecture
for a full trading system adds a macro sentiment layer on top: if the macro regime is
risk-off, reduce all position sizes derived from Horizon's micro signals, regardless of
individual ticker polarity.

The two models are not competitors for the same job. They track different things at
different scales. Both are needed for a complete picture.
