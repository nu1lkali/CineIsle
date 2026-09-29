import urllib.request, zipfile, os, sys

base = "https://repo1.maven.org/maven2/com/yinnho/upnpcast/upnpcast/1.1.2/"
outdir = r"D:\project\mpvEx-master\tools"
wanted = ["upnpcast-1.1.2-sources.jar", "upnpcast-1.1.2.jar"]
zpath = None
for fn in wanted:
    try:
        data = urllib.request.urlopen(base + fn, timeout=90).read()
        zpath = os.path.join(outdir, fn)
        open(zpath, "wb").write(data)
        print("DOWNLOADED " + fn + " " + str(len(data)))
        break
    except Exception as e:
        print("FAIL " + fn + " " + str(e))

if not zpath:
    print("NO JAR")
    sys.exit(1)

z = zipfile.ZipFile(zpath)
names = z.namelist()
print("ENTRIES: " + str(len(names)))
for n in names:
    if n.endswith(".kt"):
        print("KT: " + n)

for n in names:
    if n.endswith(".kt") and ("DLNACast" in n or "UPnPException" in n or "Device" in n or "PlaybackState" in n or "/State" in n):
        print("\n========== " + n + " ==========")
        try:
            txt = z.read(n).decode("utf-8", "replace")
            print(txt)
        except Exception as e:
            print("read err " + str(e))
