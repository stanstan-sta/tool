#!/usr/bin/env python3
"""JSONL worker for local embedding models.

The Node adapter keeps this process alive and sends one JSON request per line:

    {"id":1,"texts":["..."],"intent":"query","instruction":"...","dim":1024}

The worker returns one JSON response per line on stdout. Diagnostics go to stderr
so stdout stays machine-readable.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Iterable


DEFAULT_MODEL = "Qwen/Qwen3-Embedding-0.6B"
DEFAULT_QUERY_INSTRUCTION = (
    "Given a Minecraft player request, retrieve the most relevant bridge action example."
)


def get_detailed_instruct(task_description: str, query: str) -> str:
    return f"Instruct: {task_description}\nQuery:{query}"


def last_token_pool(last_hidden_states, attention_mask):
    import torch

    left_padding = attention_mask[:, -1].sum() == attention_mask.shape[0]
    if left_padding:
        return last_hidden_states[:, -1]
    sequence_lengths = attention_mask.sum(dim=1) - 1
    batch_size = last_hidden_states.shape[0]
    return last_hidden_states[
        torch.arange(batch_size, device=last_hidden_states.device), sequence_lengths
    ]


class LocalEmbeddingModel:
    def __init__(
        self,
        model_id: str,
        device: str = "auto",
        max_length: int = 8192,
        local_files_only: bool = False,
    ) -> None:
        import torch
        import torch.nn.functional as F
        from transformers import AutoModel, AutoTokenizer

        self.torch = torch
        self.F = F
        self.max_length = max_length

        resolved_device = device
        if resolved_device == "auto":
            resolved_device = "cuda" if torch.cuda.is_available() else "cpu"
        self.device = resolved_device

        print(
            f"[local-embedding] loading {model_id} on {self.device}",
            file=sys.stderr,
            flush=True,
        )
        self.tokenizer = AutoTokenizer.from_pretrained(
            model_id,
            padding_side="left",
            local_files_only=local_files_only,
        )
        self.model = AutoModel.from_pretrained(
            model_id,
            local_files_only=local_files_only,
        )
        self.model.to(self.device)
        self.model.eval()
        print("[local-embedding] model ready", file=sys.stderr, flush=True)

    def encode(
        self,
        texts: Iterable[str],
        intent: str = "document",
        instruction: str | None = None,
        dim: int | None = None,
    ) -> list[list[float]]:
        prepared = []
        for text in texts:
            value = "" if text is None else str(text)
            if intent == "query":
                prepared.append(
                    get_detailed_instruct(instruction or DEFAULT_QUERY_INSTRUCTION, value)
                )
            else:
                prepared.append(value)

        with self.torch.no_grad():
            batch = self.tokenizer(
                prepared,
                padding=True,
                truncation=True,
                max_length=self.max_length,
                return_tensors="pt",
            )
            batch = {k: v.to(self.device) for k, v in batch.items()}
            outputs = self.model(**batch)
            embeddings = last_token_pool(outputs.last_hidden_state, batch["attention_mask"])
            if dim is not None:
                if dim < 1:
                    raise ValueError("dim must be >= 1")
                embeddings = embeddings[:, :dim]
            embeddings = self.F.normalize(embeddings, p=2, dim=1)
            return embeddings.detach().cpu().float().tolist()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Local embedding JSONL worker")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--max-length", type=int, default=8192)
    parser.add_argument("--local-files-only", action="store_true")
    parser.add_argument("--check-deps", action="store_true")
    return parser.parse_args()


def check_deps() -> None:
    import importlib.metadata as metadata
    import importlib.util

    missing = [
        name
        for name in ("torch", "transformers")
        if importlib.util.find_spec(name) is None
    ]
    if missing:
        raise SystemExit("Missing Python packages: " + ", ".join(missing))
    print(
        json.dumps(
            {
                "ok": True,
                "torch": metadata.version("torch"),
                "transformers": metadata.version("transformers"),
            }
        )
    )


def write_response(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, separators=(",", ":")), flush=True)


def main() -> None:
    args = parse_args()
    if args.check_deps:
        check_deps()
        return

    model = LocalEmbeddingModel(
        model_id=args.model,
        device=args.device,
        max_length=args.max_length,
        local_files_only=args.local_files_only,
    )

    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            req = json.loads(line)
            req_id = req.get("id")
            texts = req.get("texts")
            single = False
            if isinstance(texts, str):
                texts = [texts]
                single = True
            elif isinstance(texts, list):
                texts = ["" if t is None else str(t) for t in texts]
            else:
                raise ValueError("texts must be a string or list of strings")

            dim = req.get("dim")
            if dim is not None:
                dim = int(dim)

            vectors = model.encode(
                texts,
                intent=str(req.get("intent") or "document"),
                instruction=req.get("instruction"),
                dim=dim,
            )
            write_response(
                {
                    "id": req_id,
                    "ok": True,
                    "embedding": vectors[0] if single else vectors,
                }
            )
        except Exception as exc:  # keep worker alive after per-request errors
            write_response(
                {
                    "id": req.get("id") if "req" in locals() else None,
                    "ok": False,
                    "error": f"{type(exc).__name__}: {exc}",
                }
            )


if __name__ == "__main__":
    main()
