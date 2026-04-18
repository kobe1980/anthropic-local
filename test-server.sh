#!/data/data/com.termux/files/usr/bin/bash

export MODEL_PATH="$HOME/storage/downloads/gemma-4-E2B-it.litertlm"
export BIN_DIR="$HOME/litert"
export LD_LIBRARY_PATH="$BIN_DIR:$LD_LIBRARY_PATH"

"$BIN_DIR/litert_lm_main" \
  --backend=cpu \
  --model_path="$MODEL_PATH" \
  --input_prompt="Ecris une fonction Kotlin qui additionne deux Int."
