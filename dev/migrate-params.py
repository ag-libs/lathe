#!/usr/bin/env python3
"""Convert lsp-params-*.properties files to lsp-params-*.json (ModuleConfig format)."""

import json
import re
import sys
from pathlib import Path


def parse_properties(text: str) -> dict[str, str]:
    """Parse Java .properties format, handling backslash escapes and line continuations."""
    result = {}
    lines = iter(text.splitlines())
    for raw in lines:
        line = raw
        # Strip leading whitespace
        line = line.lstrip()
        # Skip blank lines and comments
        if not line or line.startswith('#') or line.startswith('!'):
            continue
        # Handle line continuations
        while line.endswith('\\') and not line.endswith('\\\\'):
            line = line[:-1]
            try:
                line += next(lines).lstrip()
            except StopIteration:
                break
        # Split on first unescaped = or :
        m = re.match(r'^((?:[^=:\s\\]|\\.)+)\s*[=:]\s*(.*)', line)
        if not m:
            # key with no value
            m2 = re.match(r'^(\S+)', line)
            if m2:
                result[decode_escapes(m2.group(1))] = ''
            continue
        key = decode_escapes(m.group(1))
        value = decode_escapes(m.group(2))
        result[key] = value
    return result


def decode_escapes(s: str) -> str:
    """Decode Java properties backslash escape sequences."""
    out = []
    i = 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            c = s[i + 1]
            if c == 'n':
                out.append('\n')
            elif c == 'r':
                out.append('\r')
            elif c == 't':
                out.append('\t')
            else:
                out.append(c)
            i += 2
        else:
            out.append(s[i])
            i += 1
    return ''.join(out)


def read_list(props: dict, key: str) -> list[str]:
    result = []
    i = 0
    while True:
        v = props.get(f'{key}.{i}')
        if v is None:
            break
        result.append(v)
        i += 1
    return result


def props_to_module_config(props: dict) -> dict:
    return {
        'sourceTree': props.get('sourceTree'),
        'outputDir': props.get('outputDir'),
        'generatedSourcesDir': props.get('generatedSourcesDir') or None,
        'sourceRoots': read_list(props, 'sourceRoots'),
        'classpath': read_list(props, 'classpath'),
        'modulepath': read_list(props, 'modulepath'),
        'processorPath': read_list(props, 'processorPath'),
        'release': props.get('release'),
        'encoding': props.get('encoding') or 'UTF-8',
        'parameters': props.get('parameters', 'false').lower() == 'true',
        'enablePreview': props.get('enablePreview', 'false').lower() == 'true',
        'proc': props.get('proc') or None,
        'compilerArgs': read_list(props, 'compilerArgs'),
    }


def convert_dir(lathe_dir: Path, dry_run: bool = False) -> tuple[int, int]:
    converted = 0
    skipped = 0
    for props_file in sorted(lathe_dir.rglob('lsp-params-*.properties')):
        stem = props_file.stem  # lsp-params-<sourceTree>
        json_file = props_file.with_suffix('.json')
        if json_file.exists():
            skipped += 1
            continue
        props = parse_properties(props_file.read_text(encoding='utf-8'))
        config = props_to_module_config(props)
        if not dry_run:
            json_file.write_text(json.dumps(config, indent=2), encoding='utf-8')
        converted += 1
        print(f'  converted {props_file.relative_to(lathe_dir.parent)}')
    return converted, skipped


def main():
    dirs = sys.argv[1:] if len(sys.argv) > 1 else []
    if not dirs:
        print('Usage: migrate-params.py <lathe-dir> [...]')
        sys.exit(1)
    for d in dirs:
        lathe_dir = Path(d)
        print(f'\n=== {lathe_dir} ===')
        converted, skipped = convert_dir(lathe_dir)
        print(f'  done: {converted} converted, {skipped} already had .json')


if __name__ == '__main__':
    main()
