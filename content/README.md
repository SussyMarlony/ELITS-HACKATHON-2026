# content

The patient app's medical reference content, built only from public-domain sources and copied
word for word. `build.py` refuses any excerpt that is not found exactly in its source.

| What | Source | License |
| --- | --- | --- |
| Health passages (Assistant answers, 21 topics) | MedlinePlus health topic summaries, U.S. National Library of Medicine, dated XML release | Public domain ("Summaries on health topic pages", medlineplus.gov/about/using/usingcontent). Attribute MedlinePlus; do not imply NLM endorsement. |
| Medicines (11) | FDA drug labels via openFDA / DailyMed, pinned by set id | Public domain (US federal). US products and units: Philippine brands and strengths may differ. |
| Emergency phrases, verified | Words found in the MedlinePlus or FDA texts above | as above |
| Emergency phrases, unverified | Everyday English and Tagalog/Taglish wordings written by the app team | Ours. Pending review by a Tagalog-speaking clinician. They only make the emergency card show more often. |

Not used: MedlinePlus drug pages and medical encyclopedia (copyrighted, licensed to NLM only);
WHO publications (usually CC BY-NC-SA, non-commercial, conflicts with clinic subscriptions);
DOH Philippines CPGs and the Philippine National Formulary (government works, but commercial
use needs DOH's prior approval; add them here once that approval exists).

## Update

```sh
python3 content/fetch.py      # downloads sources into ~/.cache/patnub-build/content
python3 content/build.py      # verifies excerpts, writes app assets, RedFlagRules.kt, REVIEW.md
```

To refresh: change the MedlinePlus release date or FDA set ids in `sources.json`, fetch, build.
If a label's wording changed, the build fails and names the excerpt to update in `curated.json`.

## Clinical review

Copying text word for word stops invented content. It does not decide whether an excerpt is the
right one for patients in the Philippines. A licensed clinician must work through `REVIEW.md`
and record their name in `reviewed_by` (curated.json for medicines; passages carry it per chunk).
Until then the app labels this content "not yet reviewed by a clinician".
