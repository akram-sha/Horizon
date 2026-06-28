"""
FinBERT ONNX Export Horizon Sentiment Pipeline.

Steps:
  1. Load ProsusAI/finbert and verify on 3 test sentences.
  2. Validate against FinancialPhraseBank (target: >85% accuracy).
  3. Export to ONNX and verify outputs match PyTorch.
"""

import logging
import os
import torch
import numpy as np
import pandas as pd
import onnxruntime as ort
import onnx
import warnings

from transformers    import AutoTokenizer, AutoModelForSequenceClassification
from sklearn.metrics import accuracy_score, classification_report
from dotenv          import load_dotenv

# Load .env for HF_TOKEN.
load_dotenv()

# Suppress torchvision registration logs — torchvision is not installed and not needed.
logging.getLogger("torch.onnx._internal.exporter._registration").setLevel(logging.ERROR)

# ── Config. ────────────────────────────────────────────────────────────────────

FINBERT_MODEL      = "ProsusAI/finbert"
CSV_PATH           = "../app/src/main/resources/FinancialPhraseBank/all-data.csv"
FINBERT_ONNX_PATH  = "finbert.onnx"
LABEL_MAP          = {0: "positive", 1: "negative", 2: "neutral"}
MAX_LENGTH         = 512

# ── Step 1: Load and Verify. ───────────────────────────────────────────────────

print("–" * 60)
print("STEP 1 — Loading ProsusAI/finbert")
print("–" * 60)

tokenizer = AutoTokenizer.from_pretrained(FINBERT_MODEL, token=os.environ.get("HF_TOKEN"))
model     = AutoModelForSequenceClassification.from_pretrained(
                FINBERT_MODEL, token=os.environ.get("HF_TOKEN"))
model.eval()

test_sentences = [
    ("Revenues increased by 20% year-on-year.", "positive"),
    ("The company filed for bankruptcy.", "negative"),
    ("The board held its annual general meeting.", "neutral"),
]

print("\nSanity check on 3 sentences:\n")
for text, expected in test_sentences:
    inputs = tokenizer(text, return_tensors="pt", truncation=True, max_length=MAX_LENGTH)
    with torch.no_grad():
        logits = model(**inputs).logits
    probs = torch.softmax(logits, dim=-1).squeeze().tolist()
    pred  = LABEL_MAP[int(torch.argmax(logits))]
    match = "✓" if pred == expected else "✗"
    print(f"  {match}  [{pred:>8}]  {text}")
    print(f"         pos={probs[0]:.4f}  neg={probs[1]:.4f}  neu={probs[2]:.4f}")
    print(f"         probs sum = {sum(probs):.6f}\n")

# ── Step 2: Validate against FinancialPhraseBank. ─────────────────────────────

print("–" * 60)
print("STEP 2 — Validating against FinancialPhraseBank")
print("–" * 60)

df = pd.read_csv(CSV_PATH, sep=",", header=None, names=["label", "text"],
                 encoding="latin-1", quotechar='"', on_bad_lines="skip")
df = df.dropna(subset=["label", "text"])
df["label"] = df["label"].astype(str).str.strip()
df["text"]  = df["text"].astype(str).str.strip()
df = df[df["label"].isin(["positive", "negative", "neutral"])]
print(f"\nLoaded {len(df)} sentences  |  label counts:\n{df['label'].value_counts()}\n")

def predict_batch(texts, batch_size=32):
    all_preds = []
    for i in range(0, len(texts), batch_size):
        batch  = texts[i : i + batch_size]
        inputs = tokenizer(batch, return_tensors="pt", truncation=True,
                           padding=True, max_length=MAX_LENGTH)
        with torch.no_grad():
            logits = model(**inputs).logits
        preds = torch.argmax(logits, dim=-1).tolist()
        all_preds.extend([LABEL_MAP[p] for p in preds])
        if (i // batch_size) % 5 == 0:
            print(f"  processed {min(i + batch_size, len(texts))}/{len(texts)} sentences...")
    return all_preds

predictions = predict_batch(df["text"].tolist())
accuracy    = accuracy_score(df["label"], predictions)

print(f"\nAccuracy: {accuracy * 100:.2f}%  (target >85%)")
print("\nClassification report:")
print(classification_report(df["label"], predictions, digits=4))

if accuracy < 0.85:
    print("ERROR – Accuracy below target — check label mapping or CSV encoding.")
else:
    print("OK – Accuracy target met. Proceeding to ONNX export.")

# ── Step 3: Export to ONNX. ────────────────────────────────────────────────────

print("\n" + "–" * 60)
print("STEP 3 — Exporting to ONNX")
print("–" * 60)

# FutureWarning originates inside torch's tracing internals (copyreg.py),
# not in our code — filter precisely by source module.
warnings.filterwarnings(
    "ignore",
    category = FutureWarning,
    module   = r"copyreg",
)

# UserWarning about shared 'seq' axis name is emitted by torch's internal
# _onnx_program.py when the same Dim object is reused across multiple inputs.
# The warning is a known cosmetic issue in torch 2.x's ONNX exporter internals, not the code.
warnings.filterwarnings(
    "ignore",
    message  = r".*axis name.*will not be used.*shares the same shape constraints.*",
    category = UserWarning,
    module   = r"torch\.onnx\._internal\.exporter\._onnx_program",
)

dummy          = tokenizer("Export test sentence.", return_tensors="pt",
                           truncation=True, max_length=MAX_LENGTH)
input_ids      = dummy["input_ids"]
attention_mask = dummy["attention_mask"]
token_type_ids = dummy.get("token_type_ids", torch.zeros_like(input_ids))

batch_dim = torch.export.Dim("batch")
seq_dim   = torch.export.Dim("seq")

dynamic_shapes = {
    "input_ids":      {0: batch_dim, 1: seq_dim},
    "attention_mask": {0: batch_dim, 1: seq_dim},
    "token_type_ids": {0: batch_dim, 1: seq_dim},
}

torch.onnx.export(
    model,
    (input_ids, attention_mask, token_type_ids),
    FINBERT_ONNX_PATH,
    input_names         = ["input_ids", "attention_mask", "token_type_ids"],
    output_names        = ["logits"],
    dynamic_shapes      = dynamic_shapes,
    opset_version       = 18,
    do_constant_folding = True,
)
print(f"\nExported → {FINBERT_ONNX_PATH}")
tokenizer.save_pretrained(".")

# Verify the ONNX model.
onnx_model = onnx.load(FINBERT_ONNX_PATH)
onnx.checker.check_model(onnx_model)
print("ONNX model check passed.")

# Compare PyTorch v. ONNX outputs.
session    = ort.InferenceSession(FINBERT_ONNX_PATH)
ort_inputs = {
    "input_ids":      input_ids.numpy(),
    "attention_mask": attention_mask.numpy(),
    "token_type_ids": token_type_ids.numpy(),
}
ort_logits = session.run(["logits"], ort_inputs)[0]

with torch.no_grad():
    pt_logits = model(input_ids, attention_mask, token_type_ids).logits.numpy()

max_diff = np.max(np.abs(pt_logits - ort_logits))
print(f"Max logit diff PyTorch v. ONNX: {max_diff:.2e}")
print("Result")
print("–" * 60)
if max_diff < 1e-4:
    print("OK – Outputs match. ONNX export is valid.")
else:
    print("ERROR – Large diff detected — check opset or model inputs.")