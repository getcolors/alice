"""Alice vocabulary normalized through PocketDeploy's public configuration API."""
import os
from pathlib import Path
from blue.cli import load_yaml, read_pars
from pocketdeploy.config import normalize
from pocketdeploy.common import DeployError
from .transmission import magnet_hash

DEFAULTS = {'provider-compute': 'digitalocean', 'workdir': '.alice',
            'compute-prevent-destroy': True, 'alice-ssh-sources': ['0.0.0.0/0'],
            'digitalocean-token-env': 'COLORS_PAR_DO_TOKEN',
            'transmission-rpc-port': 9091, 'transmission-tunnel-local-port': 19091,
            'transmission-local-directory': '~/Downloads/alice', 'transmission-magnet-links': []}
SHARED = {'profile', 'provider-compute', 'workdir', 'state-file', 'compute-prevent-destroy',
          'compute-require-existing-state', 'digitalocean-account-id', 'digitalocean-region',
          'digitalocean-size', 'digitalocean-image', 'digitalocean-vpc-uuid', 'digitalocean-token-env',
          'ssh-user', 'ssh-private-key-file', 'ssh-public-key-file', 'ssh-host-private-key-file',
          'ssh-host-public-key-file', 'ssh-known-hosts-file', 'vault-id', 'vault-command',
          'vault-state-document-id'}
KEYS = SHARED | set(DEFAULTS)


def load(filename, env=None):
    path = Path(filename).absolute()
    env = os.environ if env is None else env
    if 'COLORS_PAR_PROFILE' in env:
        raise DeployError('COLORS_PAR_PROFILE is forbidden; use a separate configuration.')
    try:
        if path.is_symlink() or path.stat().st_size > 1024 * 1024:
            raise ValueError()
        raw = load_yaml(path.read_text())
        if not isinstance(raw, dict) or set(raw) - KEYS:
            raise ValueError()
    except Exception:
        raise DeployError('Invalid Alice configuration; unknown and retired fields are refused.') from None
    environment = {k: v for k, v in env.items() if k != 'COLORS_PAR_COMPUTE_PREVENT_DESTROY'}
    c = {k: v for k, v in read_pars({**DEFAULTS, **raw}, environment).items() if k in KEYS}
    if c['provider-compute'] != 'digitalocean':
        raise DeployError('Alice supports DigitalOcean only.')
    for key in ('transmission-rpc-port', 'transmission-tunnel-local-port'):
        if type(c[key]) is not int or not 1 <= c[key] <= 65535:
            raise DeployError(key + ' must be a port number.')
    magnets = c['transmission-magnet-links']
    if not isinstance(magnets, list):
        raise DeployError('transmission-magnet-links must be a list.')
    hashes = [magnet_hash(value) for value in magnets]
    if len(set(hashes)) != len(hashes):
        raise DeployError('Duplicate magnet hash.')
    directory = c['transmission-local-directory']
    if not isinstance(directory, str) or not directory or any(ord(ch) < 32 for ch in directory):
        raise DeployError('Invalid download directory.')
    destination = Path(directory).expanduser()
    c['transmission-local-directory'] = str(destination if destination.is_absolute() else path.parent / destination)
    d = normalize({**{k: c[k] for k in SHARED if k in c},
                   'compute-ssh-sources': c['alice-ssh-sources'], 'compute-http-sources': [],
                   'compute-ingress': [{'protocol': p, 'ports': ['51413'], 'sources': ['0.0.0.0/0']} for p in ('tcp', 'udp')]},
                  path=path, env={}, desired=c, aliases={'digitalocean-vpc-uuid': 'digitalocean-vpc-id'})
    return c, d
