#!/usr/bin/env python3
"""Pin Alice dependencies to verified clean, pushed sibling main checkouts."""
from pathlib import Path
import os
import re
import subprocess
import tempfile

DEPENDENCIES = (('green', 'green'), ('colors-compute', 'colors-compute'))


def git(repo, *args):
    result = subprocess.run(['git', '-C', str(repo), *args], text=True,
                            capture_output=True, check=False)
    if result.returncode:
        raise ValueError(f'{repo.name}: git {args[0]} failed; no pins changed')
    return result.stdout.strip()


def verified_head(repo, name, git_command=git):
    if git_command(repo, 'branch', '--show-current') != 'main':
        raise ValueError(f'{name}: checkout must be on main')
    if git_command(repo, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError(f'{name}: checkout is dirty; commit before pinning')
    origin = git_command(repo, 'remote', 'get-url', 'origin')
    expected = {f'https://github.com/getcolors/{name}.git',
                f'git@github.com:getcolors/{name}.git',
                f'ssh://git@github.com/getcolors/{name}.git'}
    if origin not in expected:
        raise ValueError(f'{name}: origin must be the getcolors/{name} repository')
    head = git_command(repo, 'rev-parse', 'HEAD')
    remote = git_command(repo, 'ls-remote', '--exit-code', 'origin', 'refs/heads/main').split()
    if not re.fullmatch(r'[0-9a-f]{40}', head) or remote != [head, 'refs/heads/main']:
        raise ValueError(f'{name}: HEAD must equal the current pushed origin/main')
    return head


def pin_text(source, heads):
    result = source
    for name, head in heads.items():
        pattern = re.compile(r'(io\.github\.getcolors/' + re.escape(name) + r'\s+\{)([^{}]*)(\})')
        matches = list(pattern.finditer(result))
        if len(matches) != 1:
            raise ValueError(f'{name}: expected exactly one dependency coordinate')
        match = matches[0]
        body = match.group(2)
        expected_url = f':git/url "https://github.com/getcolors/{name}.git"'
        if expected_url not in body:
            raise ValueError(f'{name}: unexpected dependency URL')
        if len(re.findall(r':git/sha\s+"[0-9a-f]{40}"', body)) != 1:
            raise ValueError(f'{name}: expected exactly one SHA pin')
        updated = re.sub(r'(:git/sha\s+)"[0-9a-f]{40}"',
                         lambda m: m.group(1) + '"' + head + '"', body)
        result = result[:match.start(2)] + updated + result[match.end(2):]
    return result


def pin_dependencies(root, git_command=git):
    manifest = root / 'deps.edn'
    source = manifest.read_text()
    heads = {name: verified_head(root.parent / checkout, name, git_command)
             for name, checkout in DEPENDENCIES}
    updated = pin_text(source, heads)
    if updated != source:
        fd, temporary = tempfile.mkstemp(prefix='.deps-pin-', dir=root)
        try:
            os.fchmod(fd, manifest.stat().st_mode & 0o777)
            with os.fdopen(fd, 'w') as stream:
                stream.write(updated)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, manifest)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    return heads


if __name__ == '__main__':
    try:
        for name, head in pin_dependencies(Path(__file__).resolve().parents[1]).items():
            print(f'{name}: pinned verified origin/main {head}')
    except (OSError, ValueError) as error:
        raise SystemExit(str(error))
