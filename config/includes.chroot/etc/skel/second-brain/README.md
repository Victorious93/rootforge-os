# Second Brain

Victorious Framework | Origin Source Labs

A local, plaintext, git-friendly knowledge vault using the **PARA method**
(Projects / Areas / Resources / Archives — Tiago Forte's organizing scheme),
plus daily notes and semantic search backed by a local embedding model
through Ollama.

## Layout

- **Inbox/** — quick capture, unsorted. Dump anything here; sort it later.
- **Projects/** — active efforts with a defined goal and an end state.
- **Areas/** — ongoing responsibilities with no end date (health, finances,
  a device you maintain long-term, this OS itself).
- **Resources/** — reference material and topics of interest, not tied to a
  specific project.
- **Archives/** — anything from the three folders above that's no longer
  active. Move it here instead of deleting it.
- **Daily/** — one note per day, for a running log/journal.

Everything is plain Markdown. Organize however you actually work — the
folders above are a starting point, not a schema `brain` enforces.

## The `brain` CLI

`brain` indexes this vault locally and can answer questions against it —
no note ever leaves the machine unless you explicitly choose the Claude
backend for `ask`.

```
brain new "Title" [--in inbox|projects|areas|resources]   # new note
brain daily                                                # today's daily note (prints its path)
brain index                                                # (re)embed changed notes — needs Ollama running
brain search "query" [-n 5]                                # semantic search over the vault
brain ask "question" [--provider ollama|claude] [-n 5]     # RAG: search + answer
brain list [projects|areas|resources|archives|inbox]       # list notes
brain stats                                                # vault + index stats
```

First-time setup, once Ollama is running (`systemctl start ollama` if it
isn't already):

```
ollama pull nomic-embed-text   # embedding model brain search/ask needs
ollama pull hermes3            # (or any model you prefer) for brain ask --provider ollama
brain index
```

`brain ask --provider claude` shells out to the `claude` CLI instead
(`setup_ai_tools.sh add anthropic` first) — useful when a question needs a
stronger model than what's practical to run locally.

Nothing here is required reading to use the vault — write notes as
Markdown files in whichever folder fits, and `brain index && brain search`
picks them up. See `brain --help` for the full command reference.
