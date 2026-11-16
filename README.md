# The Morning Edition

A personal daily newspaper. A program gathers the raw material, a language model writes one JSON file, a second program checks it, and a static page renders it. Every edition stays in the archive.

## How a day runs

| Step | When (Pacific) | What | Where |
|---|---|---|---|
| 1. Gather | about 3:30 a.m. | `tools` → `brief/DATE.json`: feed items, closing prices, earnings dates, macro events, what recent editions already covered | GitHub Actions, `gather.yml` |
| 2. Edit | about 4:30 a.m. | The model follows `EDITOR.md`, reads the brief, writes `editions/YYYY/MM/DATE.json` and updates `editions/index.json` | Scheduled Claude routine |
| 3. Validate and publish | on that push | `Validate` checks the edition; if it passes, the site deploys | GitHub Actions, `publish.yml` |

If step 3 fails, nothing deploys and yesterday's page stays up. GitHub emails the failure.

The split is deliberate: code produces every number, date and piece of state; the model only chooses stories and writes about them.

## Files

- `EDITOR.md` – the model's standing instructions.
- `profile.json` – watchlist tiers, the weekly learning rotation, library links.
- `sources.json` – feeds by beat. `schedule` is `daily` or a weekday; `max` caps items per run.
- `macro.json` – Fed and BLS dates, maintained by hand from the official schedules.
- `schema/edition.schema.json` – the shape of an edition.
- `site/` – the reader: plain HTML, CSS and JavaScript, no build step.
- `tools/` – Java 21, Maven. `Gather` builds the brief, `Validate` is the gate.
- `brief/`, `editions/` – one file per day.

## Retuning the paper

- **Follow or drop a ticker:** edit `tickers` in `profile.json`. `focus` names get movers and earnings coverage; `general` names appear only in news.
- **Add or drop a source:** edit `sources.json`. The feed must be RSS or Atom over https with dated items.
- **Change emphasis, tone or length:** edit `EDITOR.md`.
- **Change the learning rotation:** edit `learnRotation` in `profile.json`.

## Running locally

```sh
mvn -f tools/pom.xml test                                                       # unit tests, no network
mvn -q -f tools/pom.xml compile exec:java -Dexec.mainClass=news.Gather   -Dexec.args="--root ."            # build today's brief
mvn -q -f tools/pom.xml compile exec:java -Dexec.mainClass=news.Validate -Dexec.args="--root . --check-urls"  # check the newest edition
sh tools/assemble.sh && python3 -m http.server 8765 --bind 127.0.0.1 --directory _site   # preview at http://127.0.0.1:8765
```

## What Validate enforces

- The edition matches the schema; the date matches the file name and weekday; the index is complete, sorted and free of duplicates.
- No second-person ownership language ("your portfolio", "you own").
- Movers, their percentages, earnings dates, macro events, the as-of date and the learning track all equal the brief.
- No story appears twice; tagged tickers are on the watchlist; the edition is under 3,200 words.
- With `--check-urls`: no link returns 404 or 410 or points at an unreachable host.

## Caveats

- **Summaries are machine-written.** No check can confirm that a summary states its article correctly. Each item links to its source; read the original before relying on it.
- **Prices are the prior close**, from Nasdaq's public data API, with the percentage change computed from the two latest closes. Tickers Nasdaq does not list (some over-the-counter names) have no price and are reported in the brief's `pricesMissing`.
- **The data API and many feeds are unofficial or unguaranteed.** Any of them can change or start refusing automated requests. A failed feed is listed in the brief and in the edition's footer; a failed price or earnings lookup becomes a `problems` entry and the edition omits that data instead of guessing.
- **Link checks are lenient on purpose.** Many news sites answer 403 to programs, so only "not found", "gone" and unreachable hosts block publication. A link behind a paywall or bot wall still passes.
- **The schedule is in UTC.** Run times shift by an hour, by the local clock, when daylight saving time starts and ends.
- **Paywalled outlets** contribute headlines and feed excerpts only. "Read via library" links go to a public library's sign-in page and need a library card.
- **Everything here is public,** including the watchlist. It deliberately records interests only.
- **`macro.json` expires.** It lists dates through June 2027 and must be extended by hand.
