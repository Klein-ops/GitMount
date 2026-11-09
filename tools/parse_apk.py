#!/usr/bin/env python3
"""Parse binary AndroidManifest.xml inside an APK to extract versionCode/versionName."""
import zipfile, struct, sys

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
data = z.read('AndroidManifest.xml')

def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102

chunks = []
pos = 8
while pos < len(data):
    ctype = u16(data, pos)
    hsize = u16(data, pos + 2)
    csize = u32(data, pos + 4)
    chunks.append((pos, ctype, hsize, csize))
    pos += csize

strings = []
for (p, ct, hs, cs) in chunks:
    if ct == RES_STRING_POOL_TYPE:
        stringCount = u32(data, p + 8)
        flags = u32(data, p + 16)
        stringsStart = u32(data, p + 20)
        offsets = [u32(data, p + 28 + 4 * i) for i in range(stringCount)]
        is_utf8 = flags & 0x100

        def read_string(idx):
            o = p + stringsStart + offsets[idx]
            if is_utf8:
                l = data[o]; o += 1
                if l & 0x80:
                    l = ((l & 0x7f) << 8) | data[o]; o += 1
                return data[o:o + l].decode('utf-8', errors='replace')
            else:
                l = u16(data, o); o += 2
                if l & 0x8000:
                    l = ((l & 0x7fff) << 16) | u16(data, o); o += 2
                return data[o:o + l * 2].decode('utf-16-le', errors='replace')

        strings = [read_string(i) for i in range(stringCount)]
        break

print('stringPool strings count:', len(strings))
for i, s in enumerate(strings):
    if any(k in s for k in ('1.1.0', '1.0.0', 'version', 'Version')):
        print('STR[%d]: %r' % (i, s))

found = []
for (p, ct, hs, cs) in chunks:
    if ct == RES_XML_START_ELEMENT_TYPE:
        base = p + hs
        ns = u32(data, base)
        name = u32(data, base + 4)
        ele = strings[name] if 0 <= name < len(strings) else '?'
        attrStart = u16(data, base + 8)
        attrSize = u16(data, base + 10)
        attrCount = u16(data, base + 12)
        for i in range(attrCount):
            aoff = base + attrStart + i * attrSize
            aname = u32(data, aoff + 4)
            vtype = data[aoff + 15]
            vdata = u32(data, aoff + 16)
            attr = strings[aname] if 0 <= aname < len(strings) else '?'
            if attr in ('versionCode', 'versionName'):
                if vtype == 0x03:
                    val = 'STRING[%d]=%r' % (vdata, strings[vdata] if 0 <= vdata < len(strings) else '?')
                elif vtype == 0x10:
                    val = 'INT=%d' % vdata
                else:
                    val = 'type=0x%02x data=%#x' % (vtype, vdata)
                found.append((ele, attr, val))

for f in found:
    print('FOUND:', f)