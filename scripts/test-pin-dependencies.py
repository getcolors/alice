#!/usr/bin/env python3
"""Offline regression coverage for dependency pin authorization and atomicity."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('pin_dependencies', Path(__file__).with_name('pin-dependencies.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SOURCE = Path(__file__).resolve().parents[1].joinpath('deps.edn').read_text()
HEADS = {'green': 'a' * 40, 'colors-compute': 'b' * 40}


def clean_git(repo, *args):
    if args == ('branch', '--show-current'): return 'main'
    if args[0] == 'status': return ''
    if args[:2] == ('remote', 'get-url'): return f'git@github.com:getcolors/{repo.name}.git'
    if args[0] == 'rev-parse': return HEADS[repo.name]
    if args[0] == 'ls-remote': return HEADS[repo.name] + '\trefs/heads/main'
    raise AssertionError(args)


class PinTests(unittest.TestCase):
    def attempt(self, runner):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'alice'
            root.mkdir()
            manifest = root / 'deps.edn'
            manifest.write_text(SOURCE)
            try:
                result = module.pin_dependencies(root, runner)
            except ValueError:
                self.assertEqual(SOURCE, manifest.read_text())
                raise
            return result, manifest.read_text()

    def test_updates_only_the_two_verified_pins(self):
        heads, changed = self.attempt(clean_git)
        self.assertEqual(HEADS, heads)
        self.assertEqual(module.pin_text(SOURCE, HEADS), changed)
        self.assertIn(':deps/root "green"', changed)
        self.assertEqual(changed, module.pin_text(changed, HEADS))

    def test_all_preconditions_fail_before_any_write(self):
        for kind in ['dirty', 'branch', 'origin', 'unpushed']:
            def invalid(repo, *args):
                if repo.name == 'colors-compute':
                    if kind == 'dirty' and args[0] == 'status': return ' M README.md'
                    if kind == 'branch' and args[0] == 'branch': return 'feature'
                    if kind == 'origin' and args[0] == 'remote': return 'git@example.com:other/repo.git'
                    if kind == 'unpushed' and args[0] == 'ls-remote': return 'c' * 40 + '\trefs/heads/main'
                return clean_git(repo, *args)
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                self.attempt(invalid)

    def test_ambiguous_manifest_is_refused(self):
        with self.assertRaises(ValueError):
            module.pin_text(SOURCE + SOURCE, HEADS)


if __name__ == '__main__':
    unittest.main()
