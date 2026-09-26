#!/usr/bin/env python3
# RootForge OS — second brain CLI
# Victorious Framework | Origin Source Labs
#
# A local, plaintext PARA-method note vault with semantic search and
# RAG-style Q&A, backed by Ollama's embeddings/generate API. Everything
# stays on-device unless the user explicitly picks --provider claude for
# `ask`, which shells out to the already-installed Claude Code CLI instead.
#
# Stdlib only (urllib for the Ollama HTTP API, sqlite3 for the index,
# argparse for the CLI) — no extra packages baked into the ISO for this.

import argparse
import json
import math
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

VAULT = Path(os.environ.get("BRAIN_VAULT", str(Path.home() / "second-brain")))
DB_PATH = VAULT / ".brain" / "index.db"
OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://localhost:11434").rstrip("/")
EMBED_MODEL = os.environ.get("BRAIN_EMBED_MODEL", "nomic-embed-text")
CHAT_MODEL = os.environ.get("BRAIN_CHAT_MODEL", "hermes3")

PARA_DIRS = {
    "inbox": "Inbox",
    "projects": "Projects",
    "areas": "Areas",
    "resources": "Resources",
    "archives": "Archives",
}

CHUNK_CHARS = 1500


def die(msg):
    print(f"brain: error: {msg}", file=sys.stderr)
    sys.exit(1)


def warn(msg):
    print(f"brain: warning: {msg}", file=sys.stderr)


# --- vault helpers -----------------------------------------------------

def ensure_vault():
    if not VAULT.exists():
        die(
            f"vault not found at {VAULT}\n"
            f"       Create it with: mkdir -p {VAULT} (or set BRAIN_VAULT)"
        )


def slugify(title):
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return slug or "untitled"


def iter_notes():
    for path in sorted(VAULT.rglob("*.md")):
        if ".brain" in path.parts:
            continue
        yield path


# --- Ollama API ----------------------------------------------------------

def ollama_post(path, payload, timeout=120):
    req = urllib.request.Request(
        f"{OLLAMA_HOST}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # An HTTP error carries a body that usually names the real problem
        # ("model not found", ...) — far more useful than the status alone.
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace").strip()
        except Exception:
            pass
        die(f"Ollama returned HTTP {e.code} for {path}" + (f": {detail}" if detail else ""))
    except urllib.error.URLError as e:
        die(
            f"couldn't reach Ollama at {OLLAMA_HOST} ({e.reason})\n"
            f"       Is it running? Try: sudo systemctl start ollama"
        )
    except TimeoutError:
        # A read timeout surfaces as socket.timeout (TimeoutError), which is
        # NOT a URLError — so it used to escape as a raw traceback.
        die(
            f"Ollama at {OLLAMA_HOST} did not respond within {timeout}s.\n"
            f"       A first request after `ollama pull` can be slow while the model loads;\n"
            f"       retry, or raise the timeout."
        )
    except json.JSONDecodeError as e:
        die(f"Ollama returned a response that isn't JSON ({e}) — is {OLLAMA_HOST} really Ollama?")


def ollama_embed(text, model=EMBED_MODEL):
    result = ollama_post("/api/embeddings", {"model": model, "prompt": text})
    embedding = result.get("embedding")
    if not embedding:
        die(
            f"Ollama returned no embedding for model '{model}'.\n"
            f"       Pull it first: ollama pull {model}"
        )
    return embedding


def ollama_generate(prompt, model=CHAT_MODEL):
    result = ollama_post(
        "/api/generate",
        {"model": model, "prompt": prompt, "stream": False},
        timeout=300,
    )
    response = result.get("response")
    if response is None:
        die(
            f"Ollama returned no response for model '{model}'.\n"
            f"       Pull it first: ollama pull {model}"
        )
    return response


def claude_generate(prompt):
    try:
        result = subprocess.run(
            ["claude", "-p", prompt],
            capture_output=True,
            text=True,
            timeout=300,
        )
    except FileNotFoundError:
        die("the 'claude' CLI isn't on PATH — install it or use --provider ollama")
    if result.returncode != 0:
        die(f"claude CLI failed: {result.stderr.strip() or 'unknown error'}")
    return result.stdout.strip()


# --- index (sqlite) ------------------------------------------------------

def open_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS chunks (
            id INTEGER PRIMARY KEY,
            path TEXT NOT NULL,
            mtime REAL NOT NULL,
            chunk_index INTEGER NOT NULL,
            text TEXT NOT NULL,
            embedding TEXT NOT NULL
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path)")
    conn.commit()
    return conn


def _split_oversized(para):
    """Break a single paragraph longer than CHUNK_CHARS into pieces.

    Paragraph-level packing alone never split a paragraph that was itself
    over the limit — a long table, a minified line or a pasted log became
    one enormous chunk, which the embedding model silently truncates (so
    the tail of the note is unsearchable) or rejects outright. Prefer a
    line boundary, then a sentence boundary, and only fall back to a hard
    character cut when neither exists.
    """
    if len(para) <= CHUNK_CHARS:
        return [para]

    pieces, current = [], ""
    for unit in re.split(r"(?<=\n)|(?<=[.!?]\s)", para):
        if not unit:
            continue
        while len(unit) > CHUNK_CHARS:
            if current:
                pieces.append(current)
                current = ""
            pieces.append(unit[:CHUNK_CHARS])
            unit = unit[CHUNK_CHARS:]
        if current and len(current) + len(unit) > CHUNK_CHARS:
            pieces.append(current)
            current = unit
        else:
            current += unit
    if current:
        pieces.append(current)
    return [p.strip() for p in pieces if p.strip()]


def chunk_text(text):
    if not text.strip():
        return []
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    chunks, current = [], ""
    for para in paragraphs:
        for piece in _split_oversized(para):
            if current and len(current) + len(piece) + 2 > CHUNK_CHARS:
                chunks.append(current)
                current = piece
            else:
                current = f"{current}\n\n{piece}" if current else piece
    if current:
        chunks.append(current)
    return chunks or [text.strip()]


def cosine(a, b):
    # zip() stops at the shorter sequence, so vectors of different lengths
    # used to produce a plausible-looking score computed over a prefix.
    # That happens for real whenever BRAIN_EMBED_MODEL changes without a
    # `brain index --force`, and the resulting rankings are meaningless.
    # Let the caller detect it instead of silently ranking on nonsense.
    if len(a) != len(b):
        raise ValueError(f"embedding dimension mismatch: {len(a)} vs {len(b)}")
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb) if na and nb else 0.0


# --- commands --------------------------------------------------------------

def cmd_init(args):
    for name in PARA_DIRS.values():
        (VAULT / name).mkdir(parents=True, exist_ok=True)
    (VAULT / "Daily").mkdir(parents=True, exist_ok=True)
    open_db().close()
    print(f"Vault ready at {VAULT}")


def cmd_new(args):
    ensure_vault()
    folder = PARA_DIRS.get(args.into, "Inbox")
    target_dir = VAULT / folder
    target_dir.mkdir(parents=True, exist_ok=True)
    slug = slugify(args.title)
    path = target_dir / f"{slug}.md"
    if path.exists():
        die(f"{path} already exists")
    created = time.strftime("%Y-%m-%d")
    path.write_text(
        f"---\ntitle: {args.title}\ncreated: {created}\ntags: []\n---\n\n# {args.title}\n\n"
    )
    print(str(path))


def cmd_daily(args):
    ensure_vault()
    daily_dir = VAULT / "Daily"
    daily_dir.mkdir(parents=True, exist_ok=True)
    today = time.strftime("%Y-%m-%d")
    path = daily_dir / f"{today}.md"
    if not path.exists():
        path.write_text(f"# {today}\n\n## Notes\n\n## Tasks\n\n## Links\n\n")
    print(str(path))


def cmd_index(args):
    ensure_vault()
    conn = open_db()
    seen_paths = set()
    indexed = skipped = 0
    for path in iter_notes():
        rel = str(path.relative_to(VAULT))
        seen_paths.add(rel)
        mtime = path.stat().st_mtime
        row = conn.execute(
            "SELECT mtime FROM chunks WHERE path = ? LIMIT 1", (rel,)
        ).fetchone()
        if row and row[0] == mtime and not args.force:
            skipped += 1
            continue
        conn.execute("DELETE FROM chunks WHERE path = ?", (rel,))
        text = path.read_text(errors="replace")
        for i, chunk in enumerate(chunk_text(text)):
            embedding = ollama_embed(chunk)
            conn.execute(
                "INSERT INTO chunks (path, mtime, chunk_index, text, embedding) "
                "VALUES (?, ?, ?, ?, ?)",
                (rel, mtime, i, chunk, json.dumps(embedding)),
            )
        conn.commit()
        indexed += 1
        print(f"  indexed: {rel}")
    # Drop chunks for notes that were deleted/moved since the last index
    all_paths = {row[0] for row in conn.execute("SELECT DISTINCT path FROM chunks")}
    for stale in all_paths - seen_paths:
        conn.execute("DELETE FROM chunks WHERE path = ?", (stale,))
    conn.commit()
    conn.close()
    print(f"Indexed {indexed} note(s), skipped {skipped} unchanged.")


def search_chunks(query, n):
    ensure_vault()
    conn = open_db()
    rows = conn.execute("SELECT path, chunk_index, text, embedding FROM chunks").fetchall()
    conn.close()
    if not rows:
        die("index is empty — run: brain index")
    query_embedding = ollama_embed(query)
    scored = []
    mismatched = 0
    for path, chunk_index, text, embedding_json in rows:
        try:
            score = cosine(query_embedding, json.loads(embedding_json))
        except ValueError:
            mismatched += 1
            continue
        scored.append((score, path, chunk_index, text))

    if mismatched:
        if not scored:
            die(
                f"every indexed chunk was embedded with a different model than "
                f"'{EMBED_MODEL}' ({mismatched} chunk(s)).\n"
                f"       Re-embed the vault with: brain index --force"
            )
        warn(
            f"skipped {mismatched} chunk(s) embedded with a different model — "
            f"run 'brain index --force' to re-embed the whole vault"
        )
    scored.sort(key=lambda r: r[0], reverse=True)
    return scored[:n]


def cmd_search(args):
    results = search_chunks(args.query, args.n)
    if not results:
        print("No matches.")
        return
    for score, path, chunk_index, text in results:
        snippet = text.strip().replace("\n", " ")
        if len(snippet) > 200:
            snippet = snippet[:200] + "..."
        print(f"[{score:.3f}] {path} (chunk {chunk_index})\n    {snippet}\n")


def cmd_ask(args):
    results = search_chunks(args.question, args.n)
    if not results:
        die("index is empty — run: brain index")
    context = "\n\n".join(f"### {path}\n{text}" for _, path, _, text in results)
    prompt = (
        "Answer the question using only the following notes as context. "
        "If the notes don't contain the answer, say so plainly rather than guessing.\n\n"
        f"{context}\n\nQuestion: {args.question}"
    )
    if args.provider == "claude":
        answer = claude_generate(prompt)
    else:
        answer = ollama_generate(prompt, model=args.model or CHAT_MODEL)
    print(answer)
    if args.show_sources:
        print("\n--- sources ---")
        for score, path, chunk_index, _ in results:
            print(f"[{score:.3f}] {path} (chunk {chunk_index})")


def cmd_list(args):
    ensure_vault()
    folder = PARA_DIRS.get(args.folder) if args.folder else None
    base = VAULT / folder if folder else VAULT
    for path in sorted(base.rglob("*.md")):
        if ".brain" in path.parts:
            continue
        print(path.relative_to(VAULT))


def cmd_stats(args):
    ensure_vault()
    note_count = sum(1 for _ in iter_notes())
    if DB_PATH.exists():
        conn = open_db()
        chunk_count = conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        indexed_notes = conn.execute("SELECT COUNT(DISTINCT path) FROM chunks").fetchone()[0]
        conn.close()
    else:
        chunk_count = indexed_notes = 0
    print(f"Vault:         {VAULT}")
    print(f"Notes:         {note_count}")
    print(f"Indexed notes: {indexed_notes}")
    print(f"Chunks:        {chunk_count}")
    print(f"Embed model:   {EMBED_MODEL}")
    print(f"Chat model:    {CHAT_MODEL}")


# --- CLI -------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        prog="brain",
        description="RootForge second brain — local PARA-method notes with semantic search.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("init", help="create the vault structure and index db").set_defaults(func=cmd_init)

    p = sub.add_parser("new", help="create a new note")
    p.add_argument("title")
    p.add_argument("--into", choices=PARA_DIRS.keys(), default="inbox")
    p.set_defaults(func=cmd_new)

    sub.add_parser("daily", help="create/print today's daily note").set_defaults(func=cmd_daily)

    p = sub.add_parser("index", help="(re)embed changed notes — needs Ollama running")
    p.add_argument("--force", action="store_true", help="re-embed everything, not just changed notes")
    p.set_defaults(func=cmd_index)

    p = sub.add_parser("search", help="semantic search over the vault")
    p.add_argument("query")
    p.add_argument("-n", type=int, default=5, help="number of results (default 5)")
    p.set_defaults(func=cmd_search)

    p = sub.add_parser("ask", help="RAG: search the vault, then answer with an LLM")
    p.add_argument("question")
    p.add_argument("--provider", choices=["ollama", "claude"], default="ollama")
    p.add_argument("--model", help="override the default chat model")
    p.add_argument("-n", type=int, default=5, help="number of notes to use as context (default 5)")
    p.add_argument("--show-sources", action="store_true", help="print which notes were used")
    p.set_defaults(func=cmd_ask)

    p = sub.add_parser("list", help="list notes")
    p.add_argument("folder", nargs="?", choices=PARA_DIRS.keys())
    p.set_defaults(func=cmd_list)

    sub.add_parser("stats", help="vault and index stats").set_defaults(func=cmd_stats)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
