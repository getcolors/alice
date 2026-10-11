from types import SimpleNamespace
import pytest
from alice import controller
from pocketdeploy.common import DeployError


def test_sync_protection_precedes_cloud_access(monkeypatch):
    monkeypatch.setattr(controller, 'converge', lambda *a: pytest.fail('must not provision'))
    with pytest.raises(DeployError, match='protection|prevent-destroy'):
        controller.sync({}, {'compute-prevent-destroy': True}, None, None)


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
