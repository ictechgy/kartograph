#!/usr/bin/env python3
"""배포 문서의 설치 버전을 파일 경로가 아닌 실제 문구에서 검사한다."""
import argparse
from pathlib import Path
import re
import sys


INSTALL_VERSION = re.compile(r'version\s+"([0-9]+\.[0-9]+\.[0-9]+)"|kartograph-([0-9]+\.[0-9]+\.[0-9]+)\.(?:zip|tar)')


def mismatches(root: Path, version: str) -> list[str]:
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version) or not root.is_dir():
        raise ValueError('a documentation directory and stable version are required')
    result = []
    for path in sorted(root.rglob('*.md')):
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if any(next(value for value in match.groups() if value is not None) != version for match in INSTALL_VERSION.finditer(line)):
                result.append(f'{path.relative_to(root).as_posix()}:{number}')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    try:
        stale = mismatches(args.root, args.version)
    except (OSError, UnicodeError, ValueError):
        print('Unable to check release documentation; supply a readable directory and stable version.', file=sys.stderr)
        return 2
    if stale:
        print('Release documentation contains outdated installation versions: ' + ', '.join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
