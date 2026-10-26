#!/usr/bin/env python3
"""
Regenerates src/pixy/meta/exif/makernote/MakerNoteTagNames.java from a checkout of
metadata-extractor (https://github.com/drewnoakes/metadata-extractor, Apache-2.0).

Usage: python3 -I tools/gen_makernote_tags.py <metadata-extractor checkout> <output .java> [revision]

The optional revision (e.g. the git commit of the checkout) is recorded in the generated header.

Only the tag-id -> tag-name tables are taken over. Each *MakernoteDirectory.java declares its tags
as "public static final int TAG_X = <expr>;" constants (sometimes inside nested classes with an
OFFSET base) and registers names with "_tagNameMap.put(TAG_X, "Name");".
"""
import re
import sys
from pathlib import Path

# metadata-extractor directory class -> MakerNoteDirectory enum constant
DIRECTORIES = [
    ("AppleMakernoteDirectory", "APPLE"),
    ("CanonMakernoteDirectory", "CANON"),
    ("CasioType1MakernoteDirectory", "CASIO_TYPE1"),
    ("CasioType2MakernoteDirectory", "CASIO_TYPE2"),
    ("DjiMakernoteDirectory", "DJI"),
    ("FujifilmMakernoteDirectory", "FUJIFILM"),
    ("KyoceraMakernoteDirectory", "KYOCERA"),
    ("LeicaMakernoteDirectory", "LEICA"),
    ("LeicaType5MakernoteDirectory", "LEICA_TYPE5"),
    ("NikonType1MakernoteDirectory", "NIKON_TYPE1"),
    ("NikonType2MakernoteDirectory", "NIKON_TYPE2"),
    ("OlympusMakernoteDirectory", "OLYMPUS"),
    ("OlympusEquipmentMakernoteDirectory", "OLYMPUS_EQUIPMENT"),
    ("OlympusCameraSettingsMakernoteDirectory", "OLYMPUS_CAMERA_SETTINGS"),
    ("OlympusRawDevelopmentMakernoteDirectory", "OLYMPUS_RAW_DEVELOPMENT"),
    ("OlympusRawDevelopment2MakernoteDirectory", "OLYMPUS_RAW_DEVELOPMENT_2"),
    ("OlympusImageProcessingMakernoteDirectory", "OLYMPUS_IMAGE_PROCESSING"),
    ("OlympusFocusInfoMakernoteDirectory", "OLYMPUS_FOCUS_INFO"),
    ("OlympusRawInfoMakernoteDirectory", "OLYMPUS_RAW_INFO"),
    ("PanasonicMakernoteDirectory", "PANASONIC"),
    ("PentaxMakernoteDirectory", "PENTAX"),
    ("RicohMakernoteDirectory", "RICOH"),
    ("SamsungType2MakernoteDirectory", "SAMSUNG_TYPE2"),
    ("SanyoMakernoteDirectory", "SANYO"),
    ("SigmaMakernoteDirectory", "SIGMA"),
    ("SonyType1MakernoteDirectory", "SONY_TYPE1"),
    ("SonyType6MakernoteDirectory", "SONY_TYPE6"),
]

CONST_RE = re.compile(r"(?:public|private|protected)?\s*(?:static\s+final|final\s+static)\s+int\s+(\w+)\s*=\s*([^;]+);")
CLASS_RE = re.compile(r"\bclass\s+(\w+)")
PUT_RE = re.compile(r'_tagNameMap\.put\(\s*([\w.]+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)')
NAME_RE = re.compile(r'getName\(\)\s*\{\s*return\s+"([^"]+)"', re.S)


def parse(path):
    text = path.read_text(encoding="utf-8")
    consts = {}  # (scope, name) -> expr ; scope "" is the top-level class
    scope_stack = []  # (class name, brace depth at which it opened)
    depth = 0
    pending_class = None  # class declared on this or an earlier line, its "{" not seen yet
    for line in text.splitlines():
        m = CLASS_RE.search(line.split("//")[0])
        if m:
            pending_class = m.group(1)
        if pending_class and "{" in line:
            scope_stack.append((pending_class, depth))
            pending_class = None
        for cm in CONST_RE.finditer(line):
            inner = [s for s, _ in scope_stack[1:]]
            consts[(".".join(inner), cm.group(1))] = cm.group(2).strip()
        depth += line.count("{") - line.count("}")
        while scope_stack and depth <= scope_stack[-1][1]:
            scope_stack.pop()

    def resolve(scope, ident, seen=()):
        key = (scope, ident)
        if "." in ident:
            sc, name = ident.rsplit(".", 1)
            key = (sc, name)
            scope = sc
        elif key not in consts:
            key = ("", ident)
            scope = ""
        if key not in consts or key in seen:
            raise KeyError(ident)
        expr = consts[key]
        def sub(m):
            tok = m.group(0)
            if re.fullmatch(r"0[xX][0-9a-fA-F]+|\d+", tok):
                return str(int(tok, 0))
            return str(resolve(scope, tok, seen + (key,)))
        py = re.sub(r"0[xX][0-9a-fA-F]+|\d+|[A-Za-z_][\w.]*", sub, expr)
        return int(eval(py, {"__builtins__": {}}, {}))

    names = {}
    for pm in PUT_RE.finditer(text):
        names[resolve("", pm.group(1))] = pm.group(2)
    dir_name = NAME_RE.search(text).group(1)
    return dir_name, dict(sorted(names.items()))


def main():
    src = Path(sys.argv[1]) / "Source/com/drew/metadata/exif/makernotes"
    out = Path(sys.argv[2])
    lines = []
    w = lines.append
    w("/**")
    w(" * GENERATED FILE - do not edit. Regenerate with tools/gen_makernote_tags.py.")
    w(" *")
    w(" * The maker note tag names below are taken from metadata-extractor")
    w(" * (https://github.com/drewnoakes/metadata-extractor), Copyright 2002-2019 Drew Noakes and")
    w(" * contributors, licensed under the Apache License, Version 2.0")
    w(" * (http://www.apache.org/licenses/LICENSE-2.0). See NOTICE for details.")
    if len(sys.argv) > 3:
        w(" * Generated from metadata-extractor revision %s." % sys.argv[3])
    w(" */")
    w("")
    w("package pixy.meta.exif.makernote;")
    w("")
    w("import java.util.Collections;")
    w("import java.util.HashMap;")
    w("import java.util.Map;")
    w("")
    w("final class MakerNoteTagNames {")
    w("")
    w("\tprivate MakerNoteTagNames() {}")
    w("")
    w("\tstatic String directoryName(MakerNoteDirectory directory) {")
    w("\t\tswitch(directory) {")
    tables = []
    for cls, const in DIRECTORIES:
        dir_name, names = parse(src / (cls + ".java"))
        tables.append((const, names))
        w('\t\t\tcase %s: return "%s";' % (const, dir_name))
    w('\t\t\tdefault: return "Unknown Makernote";')
    w("\t\t}")
    w("\t}")
    w("")
    w("\tstatic Map<Integer, String> tagNames(MakerNoteDirectory directory) {")
    w("\t\tswitch(directory) {")
    for const, _ in tables:
        w("\t\t\tcase %s: return %s();" % (const, const.lower().replace("_", "")))
    w("\t\t\tdefault: return Collections.emptyMap();")
    w("\t\t}")
    w("\t}")
    for const, names in tables:
        w("")
        w("\tprivate static Map<Integer, String> %s() {" % const.lower().replace("_", ""))
        w("\t\tMap<Integer, String> m = new HashMap<Integer, String>(%d);" % max(16, len(names) * 2))
        for tag, name in names.items():
            w('\t\tm.put(0x%04X, "%s");' % (tag, name))
        w("\t\treturn Collections.unmodifiableMap(m);")
        w("\t}")
    w("}")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("wrote %s: %d directories, %d tag names" % (out, len(tables), sum(len(n) for _, n in tables)))


if __name__ == "__main__":
    main()
