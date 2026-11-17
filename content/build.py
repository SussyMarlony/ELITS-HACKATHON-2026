#!/usr/bin/env python3
"""Turn downloaded public-domain sources + curated.json into the app's medical content.

Every medicine excerpt must appear word for word in its FDA label, and every English red-flag
phrase in its cited source; otherwise the build fails. Nothing is paraphrased. Outputs:
  patnub-ai/app/src/main/assets/content/{knowledge,medicines,manifest}.json
  patnub-ai/app/src/main/java/ai/patnub/assistant/RedFlagRules.kt   (generated)
  content/REVIEW.md   (sign-off checklist for the clinical reviewer)
Usage: python3 content/build.py [cache_dir]
"""
import glob, hashlib, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CACHE = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/patnub-build/content")
SRC = os.path.join(CACHE, "sources")
ASSETS = os.path.join(ROOT, "patnub-ai/app/src/main/assets/content")
KOTLIN = os.path.join(ROOT, "patnub-ai/app/src/main/java/ai/patnub/assistant/RedFlagRules.kt")
os.makedirs(ASSETS, exist_ok=True)

curated = json.load(open(os.path.join(HERE, "curated.json")))
norm = lambda s: re.sub(r"\s+", " ", s).strip()
errors = []

def load(source_id):
    d = json.load(open(os.path.join(SRC, source_id + ".json")))
    full = d["text"] if "text" in d else "\n\n".join(d["sections"].values())
    return d, norm(full)

def must_contain(where, haystack, needle, loose=False):
    h, n = (haystack.lower(), norm(needle).lower()) if loose else (haystack, norm(needle))
    if loose:
        h, n = h.replace(" ", ""), n.replace(" ", "")
    if n not in h:
        errors.append(f"{where}: not found in source: {needle!r}")

# ---------- knowledge passages: MedlinePlus summaries, verbatim, split at question headings ----------
knowledge = []
for path in sorted(glob.glob(os.path.join(SRC, "mplus-*.json"))):
    d = json.load(open(path))
    sid = os.path.basename(path)[:-5]
    paras = [p.strip() for p in d["text"].split("\n\n") if p.strip()]
    chunks, cur = [], []
    for p in paras:
        heading = p.endswith("?") and len(p) < 120
        if cur and (heading or sum(len(x) for x in cur) + len(p) > 900):
            chunks.append(cur); cur = []
        cur.append(p)
    if cur: chunks.append(cur)
    release = d["release"].split()[0]  # MM/DD/YYYY
    m, dd, y = release.split("/")
    for i, c in enumerate(chunks):
        knowledge.append({
            "chunk_id": f"{sid}-{i + 1}", "source_id": sid,
            "source_title": f"MedlinePlus: {d['title']}", "publisher": "MedlinePlus (U.S. National Library of Medicine)",
            "version": f"{y}-{m}-{dd}", "topic": d["title"].lower(), "content_text": "\n\n".join(c),
            "source_url": d["url"], "reviewed_by": None,
        })

# ---------- medicines: FDA labels, verbatim excerpts ----------
medicines = []
for med in curated["medicines"]:
    d, text = load(med["source"])
    where = med["name"]
    for field in ("uses", "side_effects", "warnings", "storage", "directions"):
        for x in med[field]:
            must_contain(f"{where}.{field}", text, x)
    must_contain(f"{where}.class", text, med["class"], loose=True)
    for s in med["strengths"]:
        must_contain(f"{where}.strength", text, s, loose=True)
    for f in med["forms"]:
        must_contain(f"{where}.form", text, f, loose=True)
    medicines.append({
        "med_id": "fda-" + med["name"].lower().replace(" ", "-"), "name": med["name"], "alias": med["alias"],
        "drug_class": med["class"], "strengths": med["strengths"], "forms": med["forms"],
        "uses": med["uses"], "side_effects": med["side_effects"], "warnings": med["warnings"],
        "storage_text": " ".join(med["storage"]), "label_instructions": " ".join(med["directions"]),
        "source_citation": f"U.S. FDA drug label (DailyMed set {d['set_id']}, version {d['version']}, effective {d['effective_time']}). US product; check the Philippine pack.",
        "source_url": d["url"], "reviewed_by": None,
    })

# ---------- red flags ----------
rules = {}
for rf in curated["red_flags"]:
    d, text = load(rf["source"])
    must_contain(f"red flag {rf['source']} evidence", text, rf["evidence"])
    for p in rf["phrases"]:
        if norm(p).lower() not in text.lower():
            errors.append(f"red flag {rf['source']}: phrase not in source: {p!r}")
        rules.setdefault(rf["category"], []).append((p.lower(), rf["source"], d.get("url", "")))
unverified = {k: v for k, v in curated["unverified_wordings"].items() if not k.startswith("_")}

if errors:
    print("Content build FAILED. Fix curated.json (copy text exactly from the source):", *errors, sep="\n  ")
    sys.exit(1)

# ---------- write outputs ----------
def dump(name, obj):
    s = json.dumps(obj, ensure_ascii=False, indent=1)
    open(os.path.join(ASSETS, name), "w").write(s + "\n")
    return s

h = hashlib.sha256()
h.update(dump("knowledge.json", knowledge).encode())
h.update(dump("medicines.json", medicines).encode())
version = h.hexdigest()[:16]
dump("manifest.json", {
    "content_version": version,
    "sources": {
        "medlineplus": json.load(open(os.path.join(HERE, "sources.json")))["medlineplus"]["xml"],
        "fda_labels": [m["set_id"] for m in json.load(open(os.path.join(HERE, "sources.json")))["fda_labels"]],
    },
    "clinical_review": "pending",
})

kt = ["// GENERATED by content/build.py from content/curated.json. Do not edit by hand.",
      "package ai.patnub.assistant", "",
      "/** Emergency phrases. VERIFIED: found word for word in the cited public-domain source. */",
      "internal object RedFlagRules {",
      f"    const val CONTENT_VERSION = \"{version}\"", "",
      "    val verified: Map<RedFlagCategory, List<String>> = mapOf("]
for cat, items in rules.items():
    seen = []
    for p, src, url in items:
        if p not in seen: seen.append(p)
    kt.append(f"        RedFlagCategory.{cat} to listOf(" + ", ".join(json.dumps(p) for p in seen) + "),")
kt += ["    )", "",
       "    /** Everyday and Tagalog/Taglish wordings: app-team translations, NOT in any source. Pending clinician review. */",
       "    val unverified: Map<RedFlagCategory, List<String>> = mapOf("]
for cat, items in unverified.items():
    kt.append(f"        RedFlagCategory.{cat} to listOf(" + ", ".join(json.dumps(p.lower(), ensure_ascii=False) for p in items) + "),")
kt += ["    )", "}", ""]
open(KOTLIN, "w").write("\n".join(kt))

# ---------- review checklist ----------
r = ["# Clinical review checklist", "",
     f"Content version `{version}`. Generated by `content/build.py`; do not edit by hand.", "",
     "Every item below is copied word for word from a public-domain source (the build fails",
     "otherwise). Copying removes invented text; it does not make the choice of excerpt clinically",
     "right for patients in the Philippines. A licensed clinician must review each item, then record",
     "their name in `reviewed_by` (curated.json) before real patients use the app.", "",
     "## Emergency phrases (show the emergency card, no AI)", ""]
for cat, items in rules.items():
    r.append(f"### {cat}")
    for p, src, url in items:
        r.append(f"- [ ] `{p}` · source `{src}` {url}")
    r.append("")
r += ["### Unverified wordings (app-team translations)", ""]
for cat, items in unverified.items():
    r.append(f"- [ ] {cat}: " + ", ".join(f"`{p}`" for p in items))
r += ["", "## Medicines (FDA labels: US products and US units)", ""]
for m in medicines:
    r.append(f"- [ ] **{m['name']}**{' (' + m['alias'] + ')' if m['alias'] else ''} · {m['source_url']}")
    r.append(f"  strengths {', '.join(m['strengths']) or '—'} · forms {', '.join(m['forms'])} · {len(m['uses']) + len(m['side_effects']) + len(m['warnings'])} excerpts")
r += ["", f"## Health passages ({len(knowledge)} passages from MedlinePlus)", ""]
for t in sorted({k['source_title'] for k in knowledge}):
    r.append(f"- [ ] {t}")
open(os.path.join(HERE, "REVIEW.md"), "w").write("\n".join(r) + "\n")

print(f"OK: {len(knowledge)} passages, {len(medicines)} medicines, {sum(len(v) for v in rules.values())} verified + "
      f"{sum(len(v) for v in unverified.values())} unverified red-flag phrases. Version {version}.")
