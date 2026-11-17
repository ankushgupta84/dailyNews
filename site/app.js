"use strict";

// Reader for the daily edition. All content arrives as JSON written by a model
// from web sources, so every string is set with textContent and every link is
// checked to be https before it becomes an href.

// tools/assemble.sh places editions/ and profile.json beside this page.
const DATA_ROOT = new URL("./", location.href);

const SECTION_TITLES = {
  "markets": "Markets",
  "ai-datacenter": "AI & Data Centers",
  "security": "Security",
  "engineering": "Engineering",
  "health-tech": "Health Tech",
  "seattle": "Seattle",
  "world": "World",
  "india": "India",
  "career": "Career & Craft",
};

const PHONE = window.matchMedia("(max-width: 680px)");

let dates = [];          // newest first
let libraryLinks = {};   // source name -> library access URL
let current = null;      // date being shown

// ---------- small DOM helpers ----------

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function safeUrl(url) {
  if (typeof url !== "string") return null;
  let parsed;
  try { parsed = new URL(url); } catch { return null; }
  return parsed.protocol === "https:" ? parsed.href : null;
}

function link(text, url, className) {
  const href = safeUrl(url);
  if (!href) return el("span", className, text);
  const a = el("a", className, text);
  a.href = href;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  return a;
}

function sourceLine(item) {
  const line = el("div", "source");
  if (item.source) line.append(link(item.source + " ↗", item.url));
  const library = libraryLinks[item.source];
  if (library) line.append(link("Read via library ↗", library));
  for (const tag of item.tags || []) line.append(el("span", "tag", tag));
  return line;
}

function why(text) {
  const p = el("p", "why");
  p.append(el("b", null, "Why it matters "), document.createTextNode(text));
  return p;
}

function labelled(title, id) {
  const section = el("section");
  if (id) section.id = id;
  section.append(el("div", "label", title));
  return section;
}

function longDate(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined,
    { weekday: "long", year: "numeric", month: "long", day: "numeric" });
}

function shortDate(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
}

function pct(value) {
  const span = el("span", "pct " + (value < 0 ? "down" : "up"));
  span.textContent = (value < 0 ? "−" : "+") + Math.abs(value).toFixed(1) + "%";
  return span;
}

// ---------- rendering ----------

function renderWatchlist(w) {
  const band = el("div", "watchlist");
  band.id = "watchlist";

  const movers = labelled("Watchlist movers ");
  movers.firstChild.append(el("span", "asof", "· " + w.asOf));
  const grid = el("div", "movers");
  for (const m of w.movers) {
    const row = el("div", "row");
    row.append(link(m.ticker, m.url, "tk"), pct(m.changePct), document.createTextNode(" " + m.reason));
    grid.append(row);
  }
  if (!w.movers.length) grid.append(el("p", "empty", "No large moves."));
  movers.append(grid);

  const earnings = labelled("Earnings ahead");
  for (const e of w.earnings) {
    const row = el("div", "row");
    row.append(el("span", "tk", e.ticker), el("span", "when", shortDate(e.date)));
    if (e.note) row.append(document.createTextNode(" " + e.note));
    earnings.append(row);
  }

  const macro = labelled("Macro calendar");
  for (const m of w.macro) {
    const row = el("div", "row");
    row.append(el("span", "tk", shortDate(m.date)), document.createTextNode(" " + m.event));
    if (m.note) row.append(document.createTextNode(": " + m.note));
    macro.append(row);
  }
  if (w.analystAndInsider.length) {
    macro.append(el("div", "label subhead", "Analysts & insiders"));
    for (const a of w.analystAndInsider) {
      const row = el("div", "row");
      row.append(link(a.ticker, a.url, "tk"), document.createTextNode(" " + a.note));
      macro.append(row);
    }
  }

  band.append(movers, earnings, macro);

  if (w.bearCase) {
    const bear = labelled("The bear case");
    bear.append(el("p", "row", w.bearCase.subject + ": " + w.bearCase.summary), sourceLine(w.bearCase));
    band.append(bear);
  }
  return band;
}

function renderStory(story, lead) {
  const article = el("article", lead ? "story lead" : "story");
  const h = el("h2");
  h.append(link(story.headline, story.url));
  article.append(h, el("p", "summary", story.summary));
  if (story.whyItMatters) article.append(why(story.whyItMatters));
  article.append(sourceLine(story));
  return article;
}

function renderFront(stories) {
  const front = el("section", "front");
  front.id = "front";
  front.append(el("div", "label", "Front page"));
  if (stories.length) front.append(renderStory(stories[0], true));
  const rest = el("div", "front-rest");
  for (const story of stories.slice(1)) rest.append(renderStory(story, false));
  front.append(rest);
  return front;
}

function renderRail(edition) {
  const rail = el("aside", "rail");

  const learn = labelled("Learn · " + edition.learn.track, "learn");
  learn.append(el("h2", null, edition.learn.title));
  for (const para of edition.learn.body.split("\n\n")) learn.append(el("p", null, para));
  learn.append(sourceLine({ source: "Go deeper", url: edition.learn.url }));
  learn.append(el("div", "source", "Explainer written by a model from the linked source."));

  const live = labelled("Live well", "live-well");
  // Each tip is optional: a category with no sourced tip is simply absent.
  for (const [name, key] of [["Health", "health"], ["Eating", "eating"], ["Productivity", "productivity"]]) {
    const entry = edition.liveWell[key];
    if (!entry) continue;
    const tip = el("div", "tip");
    tip.append(el("b", null, name), document.createTextNode(entry.tip + " "), link("Source ↗", entry.url, "source"));
    live.append(tip);
  }
  if (!live.querySelector(".tip")) live.append(el("p", "empty", "No sourced tips today."));

  const calendar = labelled("Calendar", "calendar");
  for (const c of edition.calendar) {
    const row = el("div", "row");
    row.append(el("span", "tk", shortDate(c.date)), document.createTextNode(" " + c.note));
    calendar.append(row);
  }

  rail.append(learn, live, calendar);
  return rail;
}

function renderSections(sections) {
  const grid = el("div", "sections");
  for (const section of sections) {
    const title = SECTION_TITLES[section.id];
    if (!title) throw new Error("Unknown section id: " + section.id);
    const details = el("details");
    details.id = section.id;
    details.open = !PHONE.matches;
    const summary = el("summary");
    summary.append(el("div", "label", title));
    details.append(summary);
    for (const item of section.items) {
      const article = el("article", "item");
      const h = el("h3");
      h.append(link(item.headline, item.url));
      article.append(h, el("p", null, item.summary));
      if (item.whyItMatters) article.append(why(item.whyItMatters));
      article.append(sourceLine(item));
      details.append(article);
    }
    if (!section.items.length) details.append(el("p", "empty", "Nothing notable today."));
    grid.append(details);
  }
  // On wide screens the sections are always open; a click must not collapse them.
  grid.addEventListener("click", (event) => {
    if (!PHONE.matches && event.target.closest("summary")) event.preventDefault();
  });
  return grid;
}

function renderWeekend(weekend) {
  const block = el("section", "weekend");
  block.id = "weekend";
  block.append(el("div", "label", "The week in review"), el("p", null, weekend.weekInReview));
  for (const read of weekend.longReads || []) {
    const article = el("article", "item");
    const h = el("h3");
    h.append(link(read.headline, read.url));
    article.append(h, el("p", null, read.summary), sourceLine(read));
    block.append(article);
  }
  if (weekend.listen && weekend.listen.title) {
    block.append(el("div", "label subhead", "Worth your commute"));
    const p = el("p");
    p.append(link(weekend.listen.title, weekend.listen.url), document.createTextNode(" " + weekend.listen.summary));
    block.append(p);
  }
  return block;
}

function renderJump(edition) {
  const jump = document.getElementById("jump");
  jump.replaceChildren();
  const targets = [["watchlist", "Watchlist"], ["front", "Front page"]];
  for (const section of edition.sections) targets.push([section.id, SECTION_TITLES[section.id]]);
  targets.push(["learn", "Learn"], ["live-well", "Live well"], ["calendar", "Calendar"]);
  for (const [id, title] of targets) {
    const a = el("a", null, title);
    a.href = "#" + id;
    a.addEventListener("click", (event) => {
      event.preventDefault(); // keep the URL hash free for the edition date
      const target = document.getElementById(id);
      if (target.tagName === "DETAILS") target.open = true;
      target.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    jump.append(a);
  }
}

function renderEdition(edition) {
  const paper = document.getElementById("paper");
  const top = el("div", "top");
  top.append(renderFront(edition.frontPage), renderRail(edition));
  const parts = [renderWatchlist(edition.watchlist), top];
  if (edition.weekend) parts.push(renderWeekend(edition.weekend));
  parts.push(renderSections(edition.sections));
  paper.replaceChildren(...parts);
  renderJump(edition);

  document.getElementById("edition-date").textContent = longDate(edition.date);
  document.getElementById("edition-meta").textContent = longDate(edition.date);
  document.title = "The Morning Edition · " + shortDate(edition.date);

  const r = edition.runReport;
  const generated = new Date(edition.generatedAt).toLocaleString();
  let report = `Generated ${generated}. Sources reached: ${r.sourcesReached}. Full articles read: ${r.fullReads}.`;
  if (r.fetchesFailed.length) report += ` Could not reach: ${r.fetchesFailed.join(", ")}.`;
  if (r.sectionsShort.length) report += ` Short sections: ${r.sectionsShort.join(", ")}.`;
  report += " Summaries are machine-written; follow the links for the original reporting.";
  document.getElementById("footer").textContent = report;
}

// ---------- loading and navigation ----------

async function getJson(path) {
  const response = await fetch(new URL(path, DATA_ROOT), { cache: "no-cache" });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return response.json();
}

function editionPath(date) {
  const [y, m] = date.split("-");
  return `editions/${y}/${m}/${date}.json`;
}

function todayIso() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function showError(error) {
  const box = document.getElementById("error");
  box.textContent = "Could not load the edition. " + error.message;
  box.hidden = false;
  console.error(error);
}

async function show(date) {
  document.getElementById("error").hidden = true;
  const edition = await getJson(editionPath(date));
  current = date;
  renderEdition(edition);

  const index = dates.indexOf(date);
  document.getElementById("next").disabled = index === 0;
  document.getElementById("prev").disabled = index === dates.length - 1;
  document.getElementById("archive").value = date;
  if (location.hash.slice(1) !== date) history.replaceState(null, "", "#" + date);

  const notice = document.getElementById("notice");
  notice.hidden = !(index === 0 && date < todayIso());
  notice.textContent = "Today's edition is not yet published. Showing the latest, from " + longDate(date) + ".";
  window.scrollTo(0, 0);
}

function step(offset) {
  const target = dates[dates.indexOf(current) + offset];
  if (target) show(target).catch(showError);
}

function initTheme() {
  let saved = null;
  try { saved = localStorage.getItem("theme"); } catch { /* storage unavailable: follow the system theme */ }
  if (saved === "light" || saved === "dark") document.documentElement.dataset.theme = saved;
  document.getElementById("theme-toggle").addEventListener("click", () => {
    const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const now = document.documentElement.dataset.theme || (systemDark ? "dark" : "light");
    const next = now === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem("theme", next); } catch { /* choice lasts for this visit only */ }
  });
}

async function init() {
  initTheme();
  const [index, profile] = await Promise.all([getJson("editions/index.json"), getJson("profile.json")]);
  dates = index.dates;
  libraryLinks = profile.libraryLinks;
  if (!dates.length) {
    // Not an error: nothing has been published yet. Show a notice and render nothing else.
    const notice = document.getElementById("notice");
    notice.textContent = "No edition has been published yet.";
    notice.hidden = false;
    return;
  }

  const archive = document.getElementById("archive");
  for (const date of dates) {
    const option = el("option", null, shortDate(date) + ", " + date.slice(0, 4));
    option.value = date;
    archive.append(option);
  }
  archive.addEventListener("change", () => show(archive.value).catch(showError));
  document.getElementById("prev").addEventListener("click", () => step(1));
  document.getElementById("next").addEventListener("click", () => step(-1));
  window.addEventListener("hashchange", () => {
    const wanted = location.hash.slice(1);
    if (dates.includes(wanted) && wanted !== current) show(wanted).catch(showError);
  });

  const wanted = location.hash.slice(1);
  await show(dates.includes(wanted) ? wanted : dates[0]);
}

init().catch(showError);
