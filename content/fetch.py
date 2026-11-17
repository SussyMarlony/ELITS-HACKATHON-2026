#!/usr/bin/env python3
"""Download the public-domain sources the app's medical content is copied from.

Sources (all US federal works, public domain; see README.md for the license notes):
  - MedlinePlus health topic summaries (NLM), one dated XML release
  - FDA drug labels via openFDA, pinned by set_id
Writes plain text per source into <cache>/sources/ for build.py to check excerpts against.
Usage: python3 content/fetch.py [cache_dir]   (default ~/.cache/patnub-build/content)
"""
import html, json, os, re, sys, urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/patnub-build/content")
SOURCES = os.path.join(CACHE, "sources")
os.makedirs(SOURCES, exist_ok=True)
manifest = json.load(open(os.path.join(HERE, "sources.json")))
UA = {"User-Agent": "patnub-content-fetch"}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()

def text(fragment):
    """HTML fragment -> plain text with paragraph breaks kept as blank lines."""
    t = re.sub(r"(?i)</p>|<br\s*/?>|</li>|</h\d>", "\n\n", fragment)
    t = re.sub(r"(?i)<li[^>]*>", "\n\n", t)
    t = html.unescape(re.sub(r"<[^>]+>", " ", t))
    t = re.sub(r"[ \t ]+", " ", t)
    return re.sub(r"\n\s*\n+", "\n\n", t).strip()

# MedlinePlus
mp = manifest["medlineplus"]
xml_path = os.path.join(CACHE, os.path.basename(mp["xml"]))
if not os.path.exists(xml_path):
    open(xml_path, "wb").write(get(mp["xml"]))
root = ET.parse(xml_path).getroot()
topics = {t.get("title"): t for t in root.findall("health-topic") if t.get("language") == "English"}
for title in mp["topics"]:
    t = topics[title]  # KeyError = the topic was renamed upstream; fix sources.json
    summary = text(t.findtext("full-summary"))
    out = {"title": title, "url": t.get("url"), "release": root.get("date-generated"), "text": summary}
    json.dump(out, open(os.path.join(SOURCES, "mplus-" + re.sub(r"\W+", "-", title.lower()).strip("-") + ".json"), "w"), indent=1)

# openFDA labels
for med in manifest["fda_labels"]:
    data = json.loads(get("https://api.fda.gov/drug/label.json?search=set_id:%22" + med["set_id"] + "%22&limit=1"))
    r = data["results"][0]
    sections = {k: text(" ".join(v)) for k, v in r.items() if isinstance(v, list) and v and isinstance(v[0], str)}
    out = {"name": med["name"], "set_id": r["set_id"], "version": r.get("version"), "effective_time": r.get("effective_time"),
           "url": "https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=" + r["set_id"], "sections": sections}
    json.dump(out, open(os.path.join(SOURCES, "fda-" + med["name"].lower().replace(" ", "-") + ".json"), "w"), indent=1)

print("sources in", SOURCES)
