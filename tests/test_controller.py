from types import SimpleNamespace
import pytest
from alice import controller
from pocketdeploy.common import DeployError


def test_sync_protection_precedes_cloud_access(monkeypatch):
    monkeypatch.setattr(controller, 'converge', lambda *a: pytest.fail('must not provision'))
    with pytest.raises(DeployError, match='protection|prevent-destroy'):
        controller.sync({}, {'compute-prevent-destroy': True}, None, None)


def test_interrupted_sync_then_delete_retains_unrecorded_files(monkeypatch, tmp_path):
    from contextlib import contextmanager
    from pocketdeploy.host import Host
    from pocketdeploy.state import State
    from pocketdeploy.runtime import recorded_operation
    events = []
    with State(tmp_path / 'state.sqlite', 'test', {}, create=True) as state:
        host = Host({}, state, tmp_path)
        host.known.parent.mkdir()
        host.known.write_text('synthetic existing trust')
        cloud = SimpleNamespace(resource_kinds=set(), plan_delete=lambda: [],
                                delete=lambda op: events.append('delete') or {'deleted': True})
        current = SimpleNamespace(state=state, host=host, cloud=cloud)
        def converge(*args):
            host.prepare_keys()
            return {}
        @contextmanager
        def tunnel(*args):
            try:
                yield None
            finally:
                events.append('tunnel-closed')
        def downloading(*args):
            raise KeyboardInterrupt()
        monkeypatch.setattr(controller, 'copy_preflight', lambda *a: None)
        monkeypatch.setattr(controller, 'converge', converge)
        monkeypatch.setattr(host, 'prepare_transport', lambda *a: None)
        monkeypatch.setattr(controller, 'tunnel', tunnel)
        monkeypatch.setattr(controller, 'sync_downloads', downloading)
        monkeypatch.setattr(controller, 'aliases', lambda *a, **kw: events.append('alias-removed'))
        config = {'transmission-local-directory': str(tmp_path / 'downloads'),
                  'transmission-tunnel-local-port': 19091, 'transmission-rpc-port': 9091,
                  'transmission-magnet-links': []}
        deployment = {'compute-prevent-destroy': False}
        with pytest.raises(KeyboardInterrupt):
            with recorded_operation(state, 'sync', 'synthetic') as op:
                controller.sync(config, deployment, current, op)
        assert events == ['tunnel-closed']
        assert host.key.exists()
        with recorded_operation(state, 'delete', 'synthetic') as op:
            result = controller.delete(config, deployment, current, op)
        assert events == ['tunnel-closed', 'delete', 'alias-removed']
        assert result['local_keys']['retained_key_files'] == 1
        assert host.known.read_text() == 'synthetic existing trust'
        assert not host.key.exists()


def test_delete_dry_run_includes_key_retention(monkeypatch):
    from contextlib import contextmanager
    from alice import cli
    cloud_action = {'resource': 'compute', 'action': 'delete'}
    key_action = {'resource': 'ssh-key-files', 'action': 'retain'}
    @contextmanager
    def session(*args, **kwargs):
        assert kwargs['read_only'] is True
        yield SimpleNamespace(
            cloud=SimpleNamespace(plan_delete=lambda: [cloud_action]),
            host=SimpleNamespace(plan_key_cleanup=lambda: [key_action]), state=None)
    monkeypatch.setattr(cli, 'session', session)
    monkeypatch.setattr(cli, 'load', lambda *a: ({}, {'_root': '.', 'compute-prevent-destroy': False}))
    result = cli.execute(cli.parser().parse_args(['delete', '--dry-run']))
    assert result['actions'] == [cloud_action, key_action]


def test_tunnel_preserves_caller_error(monkeypatch):
    from contextlib import contextmanager
    from alice.transmission import tunnel
    events = []
    @contextmanager
    def forwarding(*args):
        events.append('open')
        try:
            yield
        finally:
            events.append('close')
    @contextmanager
    def response(*args, **kwargs):
        yield SimpleNamespace(status=200)
    monkeypatch.setattr('alice.transmission.urlopen', response)
    with pytest.raises(OSError, match='copy failed'):
        with tunnel(SimpleNamespace(tunnel=forwarding), 19091, 9091):
            raise OSError('copy failed')
    assert events == ['open', 'close']


def test_remote_failure_not_exposed():
    host = SimpleNamespace(run_python=lambda *a, **kw: {'ok': False, 'error': 'PRIVATE'})
    with pytest.raises(DeployError) as error:
        controller.remote(host, {}, {'transmission-rpc-port': 9091}, 'converge')
    assert 'PRIVATE' not in str(error.value)


def test_converge_resolves_matching_retry_only(monkeypatch, tmp_path):
    import json
    from contextlib import contextmanager
    evidence = {'instance_id': '123', 'desired_hash': 'desired'}
    completed = []
    class DB:
        def execute(self, query):
            return self
        def fetchall(self):
            return [{'id': 1, 'payload': json.dumps(evidence)},
                    {'id': 2, 'payload': json.dumps({**evidence, 'desired_hash': 'older'})}]
    state = SimpleNamespace(intent=lambda *a: 3, complete=lambda step, result: completed.append(step), db=DB())
    public = tmp_path / 'id.pub'; public.write_text('ssh-ed25519 test')
    connection = {'instance_id': '123', 'ip': '192.0.2.1', 'user': 'ubuntu'}
    cloud = SimpleNamespace(plan=lambda: [], converge=lambda *a: connection)
    host = SimpleNamespace(pub=public, cloud_init=lambda: {}, rotate_key=lambda *a: None,
                           prepare_transport=lambda *a: None)
    monkeypatch.setattr(controller, 'initialize', lambda *a: None)
    monkeypatch.setattr(controller, 'remote', lambda *a: {'ok': True})
    monkeypatch.setattr(controller, 'aliases', lambda *a: None)
    @contextmanager
    def tunnel(*a):
        yield SimpleNamespace(call=lambda *a: {})
    monkeypatch.setattr(controller, 'tunnel', tunnel)
    deployment = {'_desired_hash': 'desired'}
    controller.converge({'transmission-rpc-port': 9091, 'transmission-tunnel-local-port': 19091},
                        deployment, SimpleNamespace(state=state, cloud=cloud, host=host, root=tmp_path), 1)
    assert completed == [3, 1]
    assert '_cloud_init' not in deployment


@pytest.mark.parametrize('diagnostic, expected', [
    ({'operation': 'transmission-service', 'category': 'nonzero_exit', 'exit_code': 7}, 7),
    ({'operation': 'PRIVATE', 'category': 'nonzero_exit', 'exit_code': 7}, None),
    ({'operation': 'transmission-service', 'category': 'unknown', 'stderr': 'PRIVATE'}, None),
])
def test_remote_diagnostic_validation(diagnostic, expected):
    host = SimpleNamespace(run_python=lambda *a, **kw: {'ok': False, 'stage': 'service', 'diagnostic': diagnostic})
    with pytest.raises(DeployError) as failure:
        controller.remote(host, {}, {'transmission-rpc-port': 9091}, 'converge')
    assert (failure.value.diagnostic or {}).get('exit_code') == expected
    assert 'PRIVATE' not in repr(failure.value.diagnostic)


def test_cli_reports_structured_diagnostic(monkeypatch, capsys):
    import json
    from alice import cli
    def execute(args):
        raise DeployError('Download copy failed; deployment retained.', diagnostic={
            'operation': 'downloads-checksum-copy', 'category': 'nonzero_exit', 'exit_code': 23})
    monkeypatch.setattr(cli, 'execute', execute)
    assert cli.main(['sync', '--json']) == 1
    output = capsys.readouterr()
    result = json.loads(output.out or output.err)
    assert 'downloads-checksum-copy' in json.dumps(result)
    assert '23' in json.dumps(result)


def test_copy_preflight_failure_prevents_cloud_work(monkeypatch):
    def fail(destination):
        raise DeployError('Local copy preflight failed.')
    monkeypatch.setattr(controller, 'copy_preflight', fail)
    monkeypatch.setattr(controller, 'converge', lambda *a: pytest.fail('must not provision'))
    with pytest.raises(DeployError, match='preflight'):
        controller.sync({'transmission-local-directory': 'synthetic'},
                        {'compute-prevent-destroy': False}, None, None)


@pytest.mark.parametrize('failure_stage', ['provisioning', 'verification'])
def test_failed_converge_keeps_intent_pending(monkeypatch, tmp_path, failure_stage):
    from contextlib import contextmanager
    events = []
    state = SimpleNamespace(intent=lambda *a: events.append('intent') or 1,
                            complete=lambda *a: pytest.fail('must remain pending'))
    public = tmp_path / 'public'; public.write_text('synthetic-public')
    host = SimpleNamespace(pub=public, cloud_init=lambda: {}, rotate_key=lambda *a: None,
                           prepare_transport=lambda *a: None)
    cloud = SimpleNamespace(plan=lambda: [], converge=lambda *a: {'instance_id': 'synthetic'})
    monkeypatch.setattr(controller, 'initialize', lambda *a: None)
    def remote(*a):
        events.append('provisioning')
        if failure_stage == 'provisioning':
            raise DeployError('Synthetic failure')
    monkeypatch.setattr(controller, 'remote', remote)
    @contextmanager
    def tunnel(*a):
        events.append('verification')
        raise DeployError('Synthetic failure')
        yield
    monkeypatch.setattr(controller, 'tunnel', tunnel)
    monkeypatch.setattr(controller, 'aliases', lambda *a: pytest.fail('must not install alias'))
    deployment = {'_desired_hash': 'synthetic'}
    with pytest.raises(DeployError):
        controller.converge({'transmission-rpc-port': 9091, 'transmission-tunnel-local-port': 19091},
                            deployment, SimpleNamespace(state=state, cloud=cloud, host=host, root=tmp_path), 1)
    assert events[0] == 'intent'
    assert '_cloud_init' not in deployment


def test_remote_progress_stays_off_json_stdout(monkeypatch, capsys):
    import json
    from alice import cli
    def run_python(*args, progress, allowed_stages, **kwargs):
        assert 'apt-install' in allowed_stages
        progress('apt-install', 'started')
        progress('apt-install', 'completed')
        return {'ok': True}
    host = SimpleNamespace(run_python=run_python)
    monkeypatch.setattr(cli, 'execute', lambda args: controller.remote(
        host, {}, {'transmission-rpc-port': 9091}, 'converge'))
    assert cli.main(['converge', '--verbose', '--json']) == 0
    captured = capsys.readouterr()
    assert json.loads(captured.out)['ok'] is True
    assert 'apt-install' in captured.err
    assert cli.main(['converge', '--quiet', '--json']) == 0
    captured = capsys.readouterr()
    assert json.loads(captured.out)['ok'] is True
    assert captured.err == ''
