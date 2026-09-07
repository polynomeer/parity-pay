#!/usr/bin/env python3
"""모든 금융 불변조건이 테스트에서 언급되는지 확인합니다.

불변조건은 이 시스템이 지켜야 할 규칙 전부이고, 규칙마다 그것을 지키는지 확인하는 테스트가
있어야 합니다. 이 검사는 "테스트가 옳은가"를 판단하지 못합니다. 그저 어떤 불변조건이 아무
테스트에서도 언급되지 않는 상태를 막습니다.

근거: docs/00-document-map.md §4 (요구사항 추적), docs/10-test-strategy.md §11
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INVARIANT = re.compile(r"INV-\d{3}")


def main() -> int:
    declared = set()
    policy = ROOT / "docs" / "04-payment-policy.md"
    for match in INVARIANT.finditer(policy.read_text(encoding="utf-8")):
        declared.add(match.group(0))

    mentioned = set()
    for source in ROOT.rglob("*.java"):
        if "/build/" in str(source):
            continue
        if "/src/test/" not in str(source) and "Test.java" not in source.name:
            continue
        for match in INVARIANT.finditer(source.read_text(encoding="utf-8")):
            mentioned.add(match.group(0))

    missing = sorted(declared - mentioned)
    print(f"declared invariants: {len(declared)}, mentioned in tests: {len(declared & mentioned)}")
    for invariant in missing:
        print(f"NOT COVERED BY ANY TEST: {invariant}")
    if missing:
        return 1
    print("every declared invariant is referenced by at least one test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
