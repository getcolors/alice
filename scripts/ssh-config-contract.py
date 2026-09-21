#!/usr/bin/env python3
"""Exercise the embedded SSH-config updater with an isolated synthetic home."""
from pathlib import Path
import tempfile
import textwrap

source = Path(__file__).resolve().parents[1] / 'src/resources/io/github/getcolors/alice/tools/ansible-local/main.yml'
text = source.read_text()
script = textwrap.dedent(text.split('          - |\n', 1)[1].split('        stdin:', 1)[0])
namespace = {'__name__': 'alice_ssh_contract'}
exec(compile(script, str(source), 'exec'), namespace)
with tempfile.TemporaryDirectory(prefix='alice-ssh-contract-') as directory:
    home = Path(directory)
    key = str(home / 'SDK with spaces' / 'profile' / '0' / 'ssh-key')
    payload = {'host_alias': 'alice-test', 'block_state': 'present', 'keygen': True,
               'identity_file': key, 'ssh_hosts': [{'name': 'alice-test', 'ip': '192.0.2.10', 'user': 'root'}]}
    assert namespace['update'](payload, home)
    config = (home / '.ssh/config').read_text()
    assert 'IdentityFile "' + key + '"' in config
    assert 'IdentityFile ~/.ssh/' not in config
    assert not namespace['update'](payload, home)
    assert namespace['update']({**payload, 'block_state': 'absent', 'identity_file': None}, home)
    assert 'Host alice-test' not in (home / '.ssh/config').read_text()
print('Alice SDK SSH identity contract: passed')
