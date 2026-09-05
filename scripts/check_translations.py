#!/usr/bin/env python3
"""Check completeness and formatting of the three release languages."""

import collections
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RESOURCES = Path(__file__).resolve().parents[1] / "i18n/src/commonMain/moko-resources"
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")


def placeholders(text):
    return sorted(PLACEHOLDER.findall(text.replace("%%", "")))


def read(locale, filename):
    root = ET.parse(RESOURCES / locale / filename).getroot()
    names = [item.attrib["name"] for item in root]
    duplicates = [name for name, count in collections.Counter(names).items() if count > 1]
    if duplicates:
        raise ValueError(f"{locale}/{filename}: duplicate keys: {duplicates}")
    return {item.attrib["name"]: item for item in root if item.get("translatable") != "false"}


def check():
    errors = []
    for filename in ("strings.xml", "plurals.xml"):
        base = read("base", filename)
        for locale in ("zh-rCN", "ja"):
            translated = read(locale, filename)
            for key, source in base.items():
                target = translated.get(key)
                if target is None:
                    errors.append(f"{locale}/{filename}: missing {key}")
                    continue
                if source.tag == "plurals":
                    source_forms = {item.get("quantity"): "".join(item.itertext()) for item in source}
                    if not any(item.get("quantity") == "other" for item in target):
                        errors.append(f"{locale}/{filename}: {key} has no other form")
                    pairs = [(source_forms.get(item.get("quantity"), source_forms["other"]),
                              "".join(item.itertext())) for item in target]
                else:
                    pairs = [("".join(source.itertext()), "".join(target.itertext()))]
                for original, translation in pairs:
                    if not translation.strip():
                        errors.append(f"{locale}/{filename}: empty {key}")
                    if placeholders(original) != placeholders(translation):
                        errors.append(f"{locale}/{filename}: incompatible placeholders in {key}")
            print(f"{locale}/{filename}: {len(base.keys() & translated.keys())}/{len(base)} entries")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Chinese, English and Japanese release resources are complete.")
    return 0


if __name__ == "__main__":
    sys.exit(check())
