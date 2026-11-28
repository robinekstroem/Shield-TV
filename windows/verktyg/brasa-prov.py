"""
Brasan, prov: letar efter filmer av brasor med fri licens på Wikimedia Commons
och tar några bilder ur de bästa, till förhandsbilderna (GitHub Actions).
Skriver till mappen i första argumentet: alla.json (allt som hittades, med
licens och upphovsperson), val.json (de som togs bilder ur) och bilderna.
Inget här följer med appen.
"""
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
UA = "TvApp-forhandsbilder/1.0 (GitHub Actions; brasa-prov)"
API = "https://commons.wikimedia.org/w/api.php?"
WORDS = ("fireplace", "kamin", "hearth", "brasa", "yule", "log fire", "logs", "chimney", "open fire", "wood fire", "firewood")


def get_json(params):
    req = urllib.request.Request(API + urllib.parse.urlencode(params), headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def search(query, kind):
    params = {
        "action": "query", "format": "json", "generator": "search",
        "gsrsearch": f"{query} filetype:{kind}", "gsrnamespace": "6", "gsrlimit": "40",
        "prop": "videoinfo", "viprop": "url|size|mime|extmetadata|derivatives",
        "viextmetadatafilter": "LicenseShortName|Artist|ImageDescription|LicenseUrl|Credit",
    }
    try:
        data = get_json(params)
    except Exception as error:  # noqa: BLE001
        print("sökningen gick inte:", query, error)
        return []
    found = []
    for page in (data.get("query", {}).get("pages", {}) or {}).values():
        info = (page.get("videoinfo") or [{}])[0]
        meta = info.get("extmetadata", {}) or {}
        found.append({
            "title": page.get("title"),
            "url": info.get("url"),
            "page": info.get("descriptionurl"),
            "width": info.get("width") or 0,
            "height": info.get("height") or 0,
            "duration": info.get("duration") or 0,
            "size": info.get("size") or 0,
            "mime": info.get("mime"),
            "license": (meta.get("LicenseShortName") or {}).get("value"),
            "licenseUrl": (meta.get("LicenseUrl") or {}).get("value"),
            "artist": (meta.get("Artist") or {}).get("value"),
            "credit": (meta.get("Credit") or {}).get("value"),
            "desc": ((meta.get("ImageDescription") or {}).get("value") or "")[:300],
            "derivatives": [
                {"src": d.get("src"), "type": d.get("type"), "width": d.get("width"), "height": d.get("height"), "transcodekey": d.get("transcodekey")}
                for d in (info.get("derivatives") or [])
            ],
        })
    return found


videos = {}
for query in ("fireplace", "fireplace fire", "fire in fireplace", "kamin feuer", "kaminfeuer", "brasa", "yule log", "burning logs", "hearth fire", "wood fire"):
    for item in search(query, "video"):
        videos.setdefault(item["title"], item)
sounds = {}
for query in ("fireplace", "crackling fire", "fire crackling", "kaminfeuer", "campfire crackling"):
    for item in search(query, "audio"):
        sounds.setdefault(item["title"], item)

everything = sorted(videos.values(), key=lambda v: -(v["width"] * v["height"]))
json.dump({"videos": everything, "sounds": sorted(sounds.values(), key=lambda s: s["title"] or "")},
          open(os.path.join(OUT, "alla.json"), "w"), ensure_ascii=False, indent=1)
print(f"Filmer: {len(everything)}, ljud: {len(sounds)}")


def fits(v):
    text = ((v["title"] or "") + " " + (v["desc"] or "")).lower()
    return v["width"] >= 1280 and v["duration"] >= 8 and any(w in text for w in WORDS)


def best_source(v):
    """Den minsta versionen som ändå är minst 720 hög: snabb att hämta, skarp nog att bedöma."""
    options = [d for d in v["derivatives"] if d.get("src") and (d.get("height") or 0) >= 720]
    options.sort(key=lambda d: d.get("height") or 0)
    return options[0]["src"] if options else v["url"]


picked = [v for v in everything if fits(v)][:10]
chosen = []
for index, v in enumerate(picked):
    source = best_source(v)
    frames = []
    for share in (0.2, 0.55):
        at = max(1.0, v["duration"] * share)
        name = f"film{index}-{int(share * 100)}.jpg"
        command = ["ffmpeg", "-loglevel", "error", "-user_agent", UA, "-ss", f"{at:.1f}", "-i", source,
                   "-frames:v", "1", "-vf", "scale=1920:-2", "-q:v", "3", "-y", os.path.join(OUT, name)]
        try:
            subprocess.run(command, check=True, timeout=180)
            frames.append(name)
        except Exception as error:  # noqa: BLE001
            print("bilden gick inte:", v["title"], error)
    print(f"{index}: {v['title']} {v['width']}x{v['height']} {v['duration']:.0f}s {v['license']} -> {frames}")
    chosen.append({**v, "source": source, "frames": frames})

json.dump(chosen, open(os.path.join(OUT, "val.json"), "w"), ensure_ascii=False, indent=1)
