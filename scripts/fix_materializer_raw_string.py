from pathlib import Path

path = Path("scripts/apply_android_0_8_1_patch.py")
text = path.read_text(encoding="utf-8")
old = "        '''            // All-frames bridge"
new = "        r'''            // All-frames bridge"
if new not in text:
    if old not in text:
        raise RuntimeError("Runtime block literal was not found")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
