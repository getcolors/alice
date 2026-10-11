import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from alice import remote


def test_converge_keeps_rpc_loopback_and_stops_before_config(monkeypatch, tmp_path):
    settings = tmp_path / 'settings.json'
    settings.write_text('{}')
    profiles = tmp_path / 'profiles'
    profiles.write_text('unrelated (enforce)\n')
    downloads = tmp_path / 'downloads'
    paths = {'/etc/transmission-daemon/settings.json': settings,
             '/sys/kernel/security/apparmor/profiles': profiles,
             remote.DOWNLOADS: downloads}
    monkeypatch.setattr(remote, 'Path', lambda value: paths[value])
    calls = []
    def run(*args, **kw):
        if args[:2] == ('systemctl', 'stop'):
            assert settings.read_text() == '{}'
        calls.append(args)
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(remote, 'run', run)
    monkeypatch.setattr(remote.pwd, 'getpwnam', lambda name: SimpleNamespace(pw_uid=1, pw_gid=1))
    monkeypatch.setattr(remote.os, 'chown', lambda *args: None)
    assert remote.main({'action': 'converge', 'port': 9092}) == {'ok': True}
    config = json.loads(settings.read_text())
    assert config['rpc-bind-address'] == '127.0.0.1'
    assert config['rpc-port'] == 9092
    assert config['rpc-whitelist-enabled'] is True
    assert ('aa-disable', '/usr/bin/transmission-daemon') in calls
    assert calls[-1] == ('systemctl', 'is-active', '--quiet', 'transmission-daemon')


def test_apparmor_still_loaded_refuses_start(monkeypatch, tmp_path):
    profiles = tmp_path / 'profiles'
    profiles.write_text('transmission-daemon (enforce)\n')
    monkeypatch.setattr(remote, 'Path', lambda value: profiles)
    calls = []
    monkeypatch.setattr(remote, 'run', lambda *a, **kw: calls.append(a))
    with pytest.raises(RuntimeError, match='profile remains loaded'):
        remote.main({'action': 'converge', 'port': 9091})
    assert not any(a[:2] == ('systemctl', 'enable') for a in calls)


def test_stop_must_confirm_inactive(monkeypatch):
    monkeypatch.setattr(remote, 'run', lambda *a, **kw: SimpleNamespace(returncode=0))
    with pytest.raises(RuntimeError, match='remains active'):
        remote.main({'action': 'stop'})


def test_invalid_port_never_installs(monkeypatch):
    monkeypatch.setattr(remote, 'run', lambda *a, **kw: pytest.fail('must not run'))
    with pytest.raises(ValueError):
        remote.main({'action': 'converge', 'port': True})


def test_remote_runner_discards_private_output(monkeypatch):
    from pocketdeploy.diagnostics import CommandError
    remote.STAGE = 'service'
    def run(*args, **kwargs):
        assert kwargs['stdout'] == remote.subprocess.DEVNULL
        assert kwargs['stderr'] == remote.subprocess.DEVNULL
        return SimpleNamespace(returncode=7)
    monkeypatch.setattr(remote.subprocess, 'run', run)
    with pytest.raises(CommandError) as failure:
        remote.run('PRIVATE', 'PRIVATE')
    assert failure.value.diagnostic['exit_code'] == 7
    assert failure.value.diagnostic['operation'] == 'transmission-service'
    assert 'PRIVATE' not in str(failure.value)


def test_injected_remote_needs_no_installed_pocketdeploy():
    from pocketdeploy import diagnostics
    source = Path(diagnostics.__file__).read_text() + '\n' + Path(remote.__file__).read_text()
    namespace = {'__name__': 'injected'}
    exec(compile(source, '<remote>', 'exec'), namespace)
    assert namespace['build_diagnostic'].__module__ == 'injected'
