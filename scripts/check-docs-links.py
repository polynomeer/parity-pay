#!/usr/bin/env python3
"""문서 안의 상대 링크가 실제 파일을 가리키는지 확인합니다.

설계 문서가 서로를 참조하는 구조라, 파일을 옮기거나 이름을 바꾸면 링크가 조용히 깨집니다.
깨진 링크는 문서를 읽는 사람에게만 보이고 빌드는 통과하므로 검사로 잡습니다.

근거: docs/10-test-strategy.md §11 (문서 링크와 요구사항 추적 검사)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

LINK = re.compile(r"\[[^\]]*\]\((?!https?://|#|mailto:)([^)]+)\)")
ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    broken: list[str] = []
    checked = 0

    for markdown in sorted(ROOT.rglob("*.md")):
        if any(part in {"build", ".git", "node_modules"} for part in markdown.parts):
            continue
        for match in LINK.finditer(markdown.read_text(encoding="utf-8")):
            target = match.group(1).split("#", 1)[0].strip()
            if not target:
                continue
            checked += 1
            if not (markdown.parent / target).exists():
                broken.append(f"{markdown.relative_to(ROOT)} -> {target}")

    print(f"checked {checked} relative links in markdown files")
    for entry in broken:
        print(f"BROKEN: {entry}")
    if broken:
        print(f"\n{len(broken)} broken link(s)")
        return 1
    print("all links resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
