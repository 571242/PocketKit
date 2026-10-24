#!/usr/bin/env bash
# 扫描 device/ 下我自己写的文件，找出"中文里夹了裸 ASCII 双引号"的**代码**。
#
# 为什么会踩：中文输入法下很容易打出 " 而不是「」，Java 会把它当字符串定界符，
# 于是整行语法崩掉。父会话的 media/EnhanceTool.java:395 就是这个问题，
# 我自己在 DuilianTool 里也犯过一次。这里做成固定检查，避免再犯。
#
# 说明：注释里出现裸引号不会导致编译失败，因此只对**代码**报错；
# 注释命中只作为提示（建议统一用「」，但不阻断）。
set -u
PROJ=/f/SPACE/PocketKit
SRC="$PROJ/app/src/main/java/cn/xianhua/pocketkit/device"

python - "$SRC" <<'PY'
import io, os, re, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
src = sys.argv[1]

# 中文字符 + 裸双引号 + 中文字符：真正合法的字符串定界符两侧不会是汉字
pattern = re.compile(r'[\u4e00-\u9fff][\u3000-\u303f]*"[\u3000-\u303f]*[\u4e00-\u9fff]')

def is_comment_line(stripped):
    # // 行注释、* 续行、/** 或 /* 开头的块注释，都算注释
    return stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*')

code_hits, comment_hits = [], []
for name in sorted(os.listdir(src)):
    if not name.endswith('.java'):
        continue
    path = os.path.join(src, name)
    for n, line in enumerate(io.open(path, encoding='utf-8'), 1):
        stripped = line.strip()
        for m in pattern.finditer(line):
            entry = (name, n, m.group(), stripped[:90])
            (comment_hits if is_comment_line(stripped) else code_hits).append(entry)

for name, n, g, line in code_hits:
    print(f"  ✗ [代码] {name}:{n}  {g!r}")
    print(f"      {line}")
for name, n, g, line in comment_hits:
    print(f"  · [注释·不阻断] {name}:{n}  {g!r}")

if code_hits:
    print(f"  代码中有 {len(code_hits)} 处裸引号 → 会编译失败，必须改成「」")
    sys.exit(1)
print(f"  ✓ 代码中无裸引号（注释里另有 {len(comment_hits)} 处，仅风格提示）")
sys.exit(0)
PY
