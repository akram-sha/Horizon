#!/bin/bash
# setup.sh — copy generated model files into resources
set -e

for f in horizon-ml/finbert.onnx horizon-ml/finbert.onnx.data horizon-ml/tokenizer.json horizon-ml/tokenizer_config.json; do
    [ -f "$f" ] || { echo "ERROR: $f not found. Run 'python export_finbert.py' in horizon-ml/ first."; exit 1; }
done

cp horizon-ml/finbert.onnx           app/src/main/resources/finbert.onnx
cp horizon-ml/finbert.onnx.data      app/src/main/resources/finbert.onnx.data
cp horizon-ml/tokenizer.json         app/src/main/resources/tokenizer.json
cp horizon-ml/tokenizer_config.json  app/src/main/resources/tokenizer_config.json
echo "Done — model and tokenizer copied to resources."