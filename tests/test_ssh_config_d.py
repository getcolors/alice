"""Integration checks for Alice aliases through the pinned PocketDeploy writer."""
from types import SimpleNamespace
import subprocess

import pytest

from alice import controller
from pocketdeploy.common import DeployError


@pytest.fixture
def ssh_home(monkeypatch, tmp_path):
    home = tmp_path.resolve()
    directory = home / '.ssh'
    directory.mkdir(mode=0o700)
    config = directory / 'config'
    config.write_text('Host *\n    Include config.d/*\n    ServerAliveInterval 17\n')
    config.chmod(0o600)
    monkeypatch.setenv('HOME', str(home))
    host = SimpleNamespace(key=home / 'identity', known=home / 'known_hosts')
    return directory, config, host


def test_alias_install_and_remove_preserve_main_config(ssh_home):
    directory, config, host = ssh_home
    original = config.read_bytes()
    desired = {'profile': 'alice-test'}
    connection = {'ip': '192.0.2.12', 'user': 'ubuntu'}
    assert controller.aliases(desired, host, connection)
    assert config.read_bytes() == original
    fragments = list((directory / 'config.d').glob('*.conf'))
    assert len(fragments) == 1
    assert fragments[0].stat().st_mode & 0o777 == 0o600
    assert not controller.aliases(desired, host, connection)

    # Parse through OpenSSH to catch Include scope or precedence mistakes.
    # The absolute Include is confined to this synthetic home: OpenSSH uses the
    # account home, rather than HOME, for relative Include paths with -F.
    probe = directory / 'probe'
    probe.write_text(original.decode().replace('config.d/*', str(directory / 'config.d/*')))
    result = subprocess.run(['ssh', '-G', '-F', str(probe), 'alice-test'],
                            check=True, capture_output=True, text=True)
    settings = dict(line.split(' ', 1) for line in result.stdout.splitlines())
    assert settings['hostname'] == connection['ip']
    assert settings['user'] == connection['user']
    assert settings['stricthostkeychecking'] in {'true', 'yes'}
    assert settings['userknownhostsfile'] == str(host.known)
    assert settings['identityfile'] == str(host.key)
    assert settings['identityagent'] == 'none'
    assert settings['forwardagent'] in {'false', 'no'}
    assert controller.aliases(desired, host, remove=True)
    assert config.read_bytes() == original
    assert not fragments[0].exists()
    assert not controller.aliases(desired, host, remove=True)


def test_missing_include_refuses_without_rewriting_main_config(ssh_home):
    directory, config, host = ssh_home
    config.write_text('Host *\n    ServerAliveInterval 17\n')
    original = config.read_bytes()
    with pytest.raises(DeployError, match='SSH alias update refused'):
        controller.aliases({'profile': 'alice-test'}, host,
                           {'ip': '192.0.2.12', 'user': 'ubuntu'})
    assert config.read_bytes() == original
    assert not list((directory / 'config.d').glob('*.conf'))
