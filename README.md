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
- `sources.json` – feeds by beat. `schedule` is `daily` or a weekday; `max` caps items per run. Optional `lookbackDays` (a positive integer) replaces the default window of 36 hours for `daily` sources and 8 days for weekday ones; a bad value stops the gather and names the source. Items with the same URL appear once, under the first source listed. A feed excerpt is cut at 500 characters; when a description is under 80 characters and the item carries its full text (`content:encoded` or Atom `content`), the excerpt comes from that instead.
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
- Every `source` on a front-page story or section item that matches a `sources.json` name ignoring case and spacing is spelled exactly like it, because the reader finds the "Read via library" link by exact name. Other names pass.
- No second-person ownership language ("your portfolio", "you own").
- Every `url` in the edition is a `url` in that day's brief, under `candidates` or `moverNews`. A link found any other way is rejected.
- Each front-page, section and long-read `headline`, and the `listen` title, equals the `title` of the brief candidate with the same URL, exactly (outer whitespace aside). The headline is the source's own, not the editor's.
- Every figure written in digits in a story, mover, analyst note, bear case, deep dive, tip, long read or listen pick appears in the title and excerpt of that URL's candidate (for a mover, in the linked `moverNews` headline). "1,200" and "1200" count as the same figure. Deep-dive quotes must be verbatim substrings of that text. Figures in notes without a link (earnings, macro, calendar, week in review) must appear somewhere in the brief. The Learn piece is exempt from the figure check, since it explains its linked page, but its link must still be in the brief.
- Movers, their percentages, earnings dates, macro events, the as-of date and the learning track all equal the brief.
- No story appears twice; tagged tickers are on the watchlist; the edition is under 3,200 words.
- With `--check-urls`: no link returns 404 or 410 or points at an unreachable host.

## Caveats

- **Summaries are machine-written.** The checks cannot tell whether a summary restates its excerpt correctly, and numbers written as words ("two million") are not checked. Each item links to its source; read the original before relying on it.
- **Learn is model-written.** The Learn piece is an explainer written by a model from its linked source, and the reader says so. Its figures are not checked against the brief.
- **Mover headlines** come from the same Nasdaq data API (up to 4 published on or after the session date `asOfDate`, each naming the ticker) and appear in the brief as `moverNews`, so the editor can state why a stock moved. They are dated by day only. A failed lookup gives that ticker an empty list and a `problems` entry; it never fails the brief.
- **Live Well tips are optional.** The editor writes a tip only when a candidate supports it. The page shows the tips present, or "No sourced tips today." when there are none.
- **Future-dated feed items are dropped.** `Gather` ignores items stamped more than one hour after the run time, because some feeds carry wrong dates.
- **Prices are the official close of the latest session**, from Nasdaq's quote endpoint, which reports the close and its percentage change as soon as the market shuts. If the quote has no close, the price comes from the history endpoint, with the change computed from the two latest closes. The brief's `asOfDate` names the session. Tickers Nasdaq does not list (some over-the-counter names) have no price and are reported in the brief's `pricesMissing`.
- **The data API and many feeds are unofficial or unguaranteed.** Any of them can change or start refusing automated requests. A failed feed is listed in the brief and in the edition's footer; a failed price or earnings lookup becomes a `problems` entry and the edition omits that data instead of guessing.
- **Link checks are lenient on purpose.** Many news sites answer 403 to programs, so only "not found", "gone" and unreachable hosts block publication. A link behind a paywall or bot wall still passes.
- **The schedule is in UTC.** Run times shift by an hour, by the local clock, when daylight saving time starts and ends.
- **Paywalled outlets** contribute headlines and feed excerpts only. "Read via library" links go to a public library's sign-in page and need a library card.
- **Everything here is public,** including the watchlist. It deliberately records interests only.
- **`macro.json` expires.** It lists dates through June 2027 and must be extended by hand.
