#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
termux-setup-storage || true
mkdir -p "$HOME/storage/downloads/PrometheusModels"
find "$HOME/local-ai/models" -maxdepth 1 -type f -iname '*.gguf' -print
printf 'Copying 4B GGUF into Android Downloads (requires ~3 GB free space)...\n'
model=$(find "$HOME/local-ai/models" -maxdepth 1 -type f -iname '*4B*.gguf' -print -quit)
if [ -z "$model" ]; then echo 'No 4B model found; use an Android Files picker with your GGUF.'; exit 1; fi
cp -n -- "$model" "$HOME/storage/downloads/PrometheusModels/"
echo 'Copy complete. Open native Prometheus > Model > Choose GGUF > Downloads > PrometheusModels.'
