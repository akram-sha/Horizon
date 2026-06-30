from transformers import BertTokenizer, BertForSequenceClassification
from transformers import pipeline

# Step 3: Load FinBERT (The Pre-trained Financial Brain)
# We are downloading a model trained by "ProsusAI" specifically for finance
finbert = BertForSequenceClassification.from_pretrained('yiyanghkust/finbert-tone', num_labels=3)
tokenizer = BertTokenizer.from_pretrained('yiyanghkust/finbert-tone')

# Step 4: Create the pipeline (The wrapper that makes it easy to use)
nlp = pipeline("sentiment-analysis", model=finbert, tokenizer=tokenizer)

# Step 5: Test it on a "Tricky" Sentence
# A sentence that sounds bad ("drop", "unemployment") but might be interpreted differently.
test_sentence = "Unemployment dropped significantly, signaling a strengthening economy."

results = nlp(test_sentence)

print("--- RESULTS ---")
print(f"Sentence: {test_sentence}")
print(f"Sentiment: {results[0]['label']}")
print(f"Confidence Score: {results[0]['score']:.4f}")

# --- STEP 2: BATCH ANALYSIS ---

# 1. We create a "List" of simulated headlines.
# In the future, this list will come from an API (Reuters/Bloomberg).
headlines = [
    "Federal Reserve signals it may pause interest rate hikes next month.",
    "Tensions escalate in the South China Sea as naval exercises begin.",
    "Oil prices surge 5% following pipeline disruption in the Middle East.",
    "European Central Bank warns that inflation remains too high.",
    "Tech companies report record profits despite global supply chain issues.",
    "Peace talks in Eastern Europe stall, creating market uncertainty."
]

print(f"Analyzing {len(headlines)} headlines...\n")

# 2. Loop through every headline in our list
for headline in headlines:
    # Run the headline through the NLP model
    result = nlp(headline)[0]

    # Extract the label (Positive/Negative/Neutral) and the score (0-1)
    sentiment = result['label']
    score = result['score']

    # 3. Print the result in a readable format
    # We use f-strings to format the output nicely
    print(f"NEWS: {headline}")
    print(f"---> Sentiment: {sentiment} | Confidence: {score:.4f}")
    print("-" * 50) # Prints a separator line

# --- STEP 3: LIVE DATA INGESTION ---

import feedparser
import pandas as pd # We use pandas to make the data look like a nice table

# 2. Define our Data Source (RSS Feed)
# This is the Yahoo Finance "Top News" feed.
rss_url = "https://finance.yahoo.com/news/rssindex"

print(f"Connecting to: {rss_url} ...\n")

# 3. Parse the Feed
feed = feedparser.parse(rss_url)

print(f"Found {len(feed.entries)} articles.\n")

# 4. Loop through the feed and run Sentiment Analysis
data = [] # A list to store our results

# Let's look at the first 5 articles to save time
for entry in feed.entries[:5]:
    headline = entry.title

    # Run FinBERT
    result = nlp(headline)[0]

    # Store the data
    data.append({
        'Date': entry.published,
        'Headline': headline,
        'Sentiment': result['label'],
        'Score': result['score']
    })

# 5. Convert to a Pandas DataFrame (A nice table)
df = pd.DataFrame(data)
df

# --- STEP 4: INTELLIGENT FILTERING & CONTEXT INJECTION ---

# 1. FIX THE VIEW: Tell pandas to show the full text (no more '...')
pd.set_option('display.max_colwidth', None)

# 2. DEFINE OUR "LOGIC WRAPPER"
def process_headline(headline):
    headline_lower = headline.lower()

    # --- FILTER 1: REMOVE CLICKBAIT ---
    # If these phrases appear, we skip the article entirely.
    junk_triggers = ["stocks to buy", "is the best", "zacks", "motley fool", "here's when", "why you should"]
    for trigger in junk_triggers:
        if trigger in headline_lower:
            return None, "SKIPPED (Clickbait)"

    # --- FILTER 2: CONTEXT INJECTION (The Geopolitics Fix) ---
    # If we see war words, we FORCE the model to understand the market risk.
    geo_triggers = ["war", "tensions", "missile", "military", "conflict", "invasion"]
    for trigger in geo_triggers:
        if trigger in headline_lower:
            # We append a phrase that FinBERT understands as NEGATIVE
            enhanced_headline = f"{headline}. This creates major market uncertainty and risk."
            return enhanced_headline, "MODIFIED (Geo-Context Added)"

    # If no filters trigger, return the original headline
    return headline, "ORIGINAL"

print("Fetching and processing news...\n")

# 3. APPLY THE LOGIC
clean_data = []

# Re-fetch the feed to be fresh
feed = feedparser.parse(rss_url)

for entry in feed.entries: # Remove the [:5] limit to scan everything!
    headline = entry.title

    # Pass it through our Logic Wrapper
    processed_text, status = process_headline(headline)

    # If the filter returned None, we skip it (Clickbait)
    if processed_text is None:
        continue

    # Run FinBERT on the (possibly modified) text
    result = nlp(processed_text)[0]

    # Only keep it if Confidence is high (> 0.85) to reduce noise
    if result['score'] > 0.85:
        clean_data.append({
            'Headline': headline, # Show the original to the human
            'Sentiment': result['label'],
            'Score': result['score'],
            'Type': status # Show if we modified it
        })

# 4. SHOW THE RESULTS
df_clean = pd.DataFrame(clean_data)

# Sort by Sentiment so we see the "Actionable" stuff first
df_clean = df_clean.sort_values(by='Sentiment')

df_clean # Print the full table

print("Loading FinBERT (This takes a moment)...")
finbert = BertForSequenceClassification.from_pretrained('yiyanghkust/finbert-tone', num_labels=3)
tokenizer = BertTokenizer.from_pretrained('yiyanghkust/finbert-tone')
nlp = pipeline("sentiment-analysis", model=finbert, tokenizer=tokenizer)
pd.set_option('display.max_colwidth', None)
print("Environment restored. You may now run your feed scripts.")

# --- STEP 5: BETTER DATA SOURCE (CNBC) & SIGNAL SORTING ---

# We switch to CNBC Economy - typically more macro-focused, less clickbait
rss_url = "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258"

print(f"Connecting to CNBC Economy Feed...\n")
feed = feedparser.parse(rss_url)

print(f"Found {len(feed.entries)} articles. Filtering for Signal...\n")

significant_news = []

for entry in feed.entries:
    headline = entry.title

    # Run FinBERT
    result = nlp(headline)[0]
    label = result['label']
    score = result['score']

    # --- THE FILTER ---
    # 1. Discard "Neutral" stories (Noise)
    # 2. Discard "Low Confidence" stories (Confusing/Vague news)
    if label != 'Neutral' and score > 0.85:
        significant_news.append({
            'Headline': headline,
            'Sentiment': label,
            'Confidence': score,
            'Source': 'CNBC'
        })

# Convert to DataFrame
df_signal = pd.DataFrame(significant_news)

if not df_signal.empty:
    print("--- DETECTED MARKET SIGNALS ---")
    print(df_signal[['Sentiment', 'Confidence', 'Headline']].to_string())
else:
    print("No strong signals detected right now. The market is 'Quiet'.")