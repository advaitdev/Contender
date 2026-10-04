#!/usr/bin/env bash
# Unpacks Xvfb into this folder without installing anything (no sudo needed), for hidden client rendering.
# Ubuntu/Debian only (uses apt-get download). Software OpenGL comes from the system's Mesa (llvmpipe).
set -e
cd "$(dirname "$0")"
if [ -x Xvfb-local ]; then echo "already set up"; exit 0; fi
mkdir -p debs && cd debs
apt-get download xvfb xserver-common libxfont2 libfontenc1 libxkbfile1 x11-xkb-utils
cd ..
for f in debs/*.deb; do dpkg -x "$f" root; done
# Xvfb runs xkbcomp from a fixed /usr/bin path. Point it at ./xkbbin (same length) so the unpacked copy is used.
python3 - <<'PY'
data = bytearray(open('root/usr/bin/Xvfb', 'rb').read())
needle = b'/usr/bin\x00'
offsets = [i for i in range(len(data)) if data.startswith(needle, i)]
assert len(offsets) == 1, f"expected one /usr/bin string, found {len(offsets)}"
data[offsets[0]:offsets[0] + 8] = b'./xkbbin'
open('Xvfb-local', 'wb').write(data)
PY
chmod +x Xvfb-local
ln -sfn root/usr/bin xkbbin
echo "Xvfb ready"
