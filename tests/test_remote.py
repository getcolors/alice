import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from alice import remote


def test_converge_keeps_rpc_loopback_and_stops_before_config(monkeypatch, tmp_path, capsys):
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
        return SimpleNamespace(returncode=3 if args[:2] == ('systemctl', 'is-active') and '--quiet' not in args else 0)
    monkeypatch.setattr(remote, 'run', run)
    monkeypatch.setattr(remote.pwd, 'getpwnam', lambda name: SimpleNamespace(pw_uid=1, pw_gid=1))
    monkeypatch.setattr(remote.os, 'chown', lambda *args: None)
    result = remote.main({'action': 'converge', 'port': 9092})
    assert result['ok'] is True
    events = [json.loads(line) for line in capsys.readouterr().err.splitlines()]
    stages = ['apt-update', 'apt-install', 'apparmor', 'configuration', 'service']
    assert [(event['stage'], event['event']) for event in events] == [
        (stage, status) for stage in stages for status in ('started', 'completed')]
    assert all(set(event) == {'stage', 'event'} for event in events)
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


def test_converge_refuses_configuration_when_stop_does_not_complete(monkeypatch, tmp_path):
    profiles = tmp_path / 'profiles'
    profiles.write_text('')
    monkeypatch.setattr(remote, 'Path', lambda value: profiles if value == '/sys/kernel/security/apparmor/profiles' else pytest.fail('must not configure'))
    monkeypatch.setattr(remote, 'run', lambda *a, **kw: SimpleNamespace(returncode=0))
    with pytest.raises(RuntimeError, match='remains active'):
        remote.main({'action': 'converge', 'port': 9091})
    assert remote.STAGE == 'apparmor'


def test_failed_install_does_not_report_completion_or_later_stages(monkeypatch, capsys):
    from pocketdeploy.diagnostics import CommandError, build_diagnostic
    def run(*args, **kwargs):
        if args[:2] == ('apt-get', 'install'):
            raise CommandError(build_diagnostic('transmission-apt-install', 'nonzero_exit', returncode=1))
        return SimpleNamespace(returncode=0)
    monkeypatch.setattr(remote, 'run', run)
    with pytest.raises(CommandError):
        remote.main({'action': 'converge', 'port': 9091})
    events = [json.loads(line) for line in capsys.readouterr().err.splitlines()]
    assert [(event['stage'], event['event']) for event in events] == [
        ('apt-update', 'started'), ('apt-update', 'completed'), ('apt-install', 'started')]
    assert remote.STAGE == 'apt-install'
