# Offline GGUF Chat — Models Directory

Place your `.gguf` model files in this folder (or any folder on your phone storage):

```
/mnt/sdcard/MT2/apk/OfflineGGUFChat/app/src/main/assets/models/
```

## Where to get GGUF models

The official model conversions are hosted by the community at:
https://github.com/ggml-org/llama.cpp/tree/master/models

Popular compatible models (search Hugging Face for "GGUF"):

| Model | Size | Quant | RAM needed | Use case |
|-------|------|-------|------------|----------|
| TinyLlama-1.1B | ~0.7 GB | Q4_K_M | 2 GB | Fast, low-RAM phones |
| Phi-3 Mini 3.8B | ~2.3 GB | Q4_K_M | 4 GB | General chat |
| Llama-3.2 1B | ~0.6 GB | Q4_K_M | 2 GB | Lightweight |
| Llama-3.2 3B | ~1.8 GB | Q4_K_M | 4 GB | Balanced |
| Llama-3.1 8B | ~4.9 GB | Q4_K_M | 8 GB | Capable assistant |
| Llama-3.1 8B | ~7.8 GB | Q8_0 | 12 GB | High quality |
| Qwen2.5 7B | ~4.4 GB | Q4_K_M | 8 GB | Multilingual, code |
| Mistral-7B | ~4.4 GB | Q4_K_M | 8 GB | Reasoning |
| LLaVA-1.5 7B | ~4.6 GB | Q4_K_M | 8 GB | **Vision** (image attach) |
| BakLLaVA 1B | ~0.6 GB | Q4_K_M | 2 GB | **Vision**, tiny |

## How to add a model

1. Download a `.gguf` file from Hugging Face (search for model name + "GGUF")
2. Copy it to your phone's storage (any folder, Downloads works)
3. Open the app → tap **Model Folder** → select that folder
4. Tap **Load Model** on the one you want

## Offline-only

This app does NOT download models automatically. You bring your own `.gguf` file. No internet, no API keys, no cloud.
