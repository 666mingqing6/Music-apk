"""把 classes.dex 追加进 aapt2 产出的 base.apk，保留原有条目的压缩方式。

aapt2 link 生成的 resources.arsc 必须是未压缩且可对齐的，因此不能直接
用 jar/普通 zip 工具整体重打包；这里逐条复制并沿用原 compress_type。
"""
import sys
import zipfile

base, dex, out = sys.argv[1:4]

with zipfile.ZipFile(base, "r") as zin:
    with zipfile.ZipFile(out, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.internal_attr = item.internal_attr
            zi.create_system = item.create_system
            zi.comment = item.comment
            zout.writestr(zi, data)
        zout.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)

with zipfile.ZipFile(out, "r") as z:
    names = z.namelist()
    arsc = z.getinfo("resources.arsc")
    print("entries=%d dex=%s arsc_compress=%d arsc_size=%d" % (
        len(names),
        "classes.dex" in names,
        arsc.compress_type,
        arsc.file_size,
    ))
