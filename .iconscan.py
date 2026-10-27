import zipfile, io, re, sys

aar = r"D:\project\mpvEx-master\.gh\caches\modules-2\files-2.1\androidx.compose.material\material-icons-extended-android\1.7.8\7499b6142a798c77a178b1098319c0b90595ab84\material-icons-extended-release.aar"
want = ["AutoAwesome", "CleaningServices", "DeleteSweep", "CalendarMonth", "Build", "CreateNewFolder", "ChevronRight", "FolderSpecial", "Handyman", "DriveFileMove", "SortByAlpha"]

with zipfile.ZipFile(aar) as z:
    jar_bytes = z.read("classes.jar")
with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jz:
    names = jz.namelist()

out = []
for w in want:
    hits = [n for n in names if re.search(r"filled/%sKt\.class$" % w, n)]
    out.append("%-18s %s" % (w, hits[0] if hits else "MISSING"))

open(r"D:\project\mpvEx-master\.icon.txt", "w", encoding="utf-8").write("\n".join(out))
print("ok")
