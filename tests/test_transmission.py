import pytest
from alice.transmission import magnet_hash, sync_downloads, copy_downloads
from pocketdeploy.common import DeployError

MAGNET = 'magnet:?xt=urn%3Abtih%3A' + 'a' * 40


def test_magnet_decodes_once():
    assert magnet_hash(MAGNET) == 'a' * 40
    with pytest.raises(DeployError):
        magnet_hash('magnet:?dn=x%26xt%3Durn%3Abtih%3A' + 'a' * 40)


class RPC:
    def call(self, method, arguments):
        return {'torrents': [{'hashString': 'a' * 40, 'percentDone': 1, 'metadataPercentComplete': 1, 'leftUntilDone': 0, 'error': 0}]}


def test_copy_stop_checksum_delete_order():
    calls = []
    sync_downloads(RPC(), [MAGNET], lambda **kw: calls.append(('copy', kw['checksum'])),
                   lambda: calls.append('stop'), lambda: calls.append('delete'))
    assert calls == [('copy', False), 'stop', ('copy', True), 'delete']


@pytest.mark.parametrize('failure', ['incremental', 'stop', 'final'])
def test_failures_never_delete(failure):
    calls = []
    def copy(*, checksum):
        if failure == ('final' if checksum else 'incremental'):
            raise DeployError('failure')
    def stop():
        if failure == 'stop':
            raise DeployError('failure')
    with pytest.raises(DeployError):
        sync_downloads(RPC(), [MAGNET], copy, stop, lambda: calls.append('delete'))
    assert not calls


def test_empty_magnets_still_stop_and_checksum():
    calls = []
    sync_downloads(RPC(), [], lambda **kw: calls.append(kw), lambda: calls.append('stop'), lambda: calls.append('delete'))
    assert calls == ['stop', {'checksum': True}, 'delete']


def test_rsync_never_deletes(monkeypatch, tmp_path):
    from types import SimpleNamespace
    calls = []
    monkeypatch.setattr('alice.transmission.subprocess.run', lambda argv, **kw: calls.append(argv) or SimpleNamespace(returncode=0))
    copy_downloads(SimpleNamespace(rsync_shell=lambda: 'ssh -o StrictHostKeyChecking=yes'),
                   {'user': 'root', 'ip': '192.0.2.1'}, tmp_path, checksum=True)
    assert '--delete' not in calls[0]
    assert '--checksum' in calls[0]


def test_metadata_is_required_before_teardown():
    calls = []
    class PendingRPC(RPC):
        def __init__(self):
            self.polls = 0
        def call(self, method, arguments):
            result = super().call(method, arguments)
            if method == 'torrent-get':
                self.polls += 1
                if self.polls == 1:
                    result['torrents'][0]['metadataPercentComplete'] = 0
            return result
    sync_downloads(PendingRPC(), [MAGNET], lambda **kw: calls.append(('copy', kw['checksum'])),
                   lambda: calls.append('stop'), lambda: calls.append('delete'),
                   sleep=lambda n: calls.append('wait'))
    assert calls == [('copy', False), 'wait', ('copy', False), 'stop', ('copy', True), 'delete']


def test_rpc_session_negotiation(monkeypatch):
    from io import BytesIO
    from urllib.error import HTTPError
    from alice.transmission import RPC as Client
    calls = []
    def open_request(request, timeout):
        calls.append(request)
        if len(calls) == 1:
            raise HTTPError(request.full_url, 409, 'conflict', {'X-Transmission-Session-Id': 'session'}, None)
        return BytesIO(b'{"result":"success","arguments":{"ok":true}}')
    monkeypatch.setattr('alice.transmission.urlopen', open_request)
    assert Client(9091).call('session-get') == {'ok': True}
    assert calls[1].get_header('X-transmission-session-id') == 'session'
    assert calls[1].full_url == 'http://127.0.0.1:9091/transmission/rpc'


def test_rpc_failure_is_sanitized(monkeypatch):
    from io import BytesIO
    from alice.transmission import RPC as Client
    monkeypatch.setattr('alice.transmission.urlopen', lambda *a, **kw: BytesIO(b'{"result":"PRIVATE FAILURE"}'))
    with pytest.raises(DeployError) as error:
        Client(9091).call('session-get')
    assert 'PRIVATE' not in str(error.value)


@pytest.mark.parametrize('response, category', [
    (b'[]', 'invalid_response'), (b'{"result":"success","arguments":[]}', 'invalid_response'),
    (b'PRIVATE', 'invalid_response'), (b'{"result":"PRIVATE"}', 'rejected'),
])
def test_rpc_structured_failure_excludes_response(monkeypatch, response, category):
    from io import BytesIO
    from alice.transmission import RPC as Client
    monkeypatch.setattr('alice.transmission.urlopen', lambda *a, **kw: BytesIO(response))
    with pytest.raises(DeployError) as failure:
        Client(9091).call('torrent-add', {'filename': MAGNET})
    assert failure.value.diagnostic['category'] == category
    assert 'PRIVATE' not in repr(failure.value.diagnostic)
    assert MAGNET not in repr(failure.value.diagnostic)


@pytest.mark.parametrize('kind, category', [('http', 'http'), ('timeout', 'timeout'), ('connection', 'connection')])
def test_rpc_transport_diagnostics(monkeypatch, kind, category):
    from urllib.error import HTTPError, URLError
    from alice.transmission import RPC as Client
    def fail(*a, **kw):
        if kind == 'http':
            raise HTTPError('PRIVATE', 503, 'PRIVATE', {'X-Transmission-Session-Id': 'PRIVATE'}, None)
        raise URLError(TimeoutError('PRIVATE') if kind == 'timeout' else OSError('PRIVATE'))
    monkeypatch.setattr('alice.transmission.urlopen', fail)
    with pytest.raises(DeployError) as failure:
        Client(9091).call('session-get')
    assert failure.value.diagnostic['category'] == category
    assert 'PRIVATE' not in repr(failure.value.diagnostic)


@pytest.mark.parametrize('checksum', [False, True])
@pytest.mark.parametrize('returncode', [23, -15])
def test_copy_diagnostic_excludes_paths(monkeypatch, tmp_path, checksum, returncode):
    from types import SimpleNamespace
    monkeypatch.setattr('alice.transmission.subprocess.run', lambda *a, **kw: SimpleNamespace(returncode=returncode))
    with pytest.raises(DeployError) as failure:
        copy_downloads(SimpleNamespace(rsync_shell=lambda: 'PRIVATE'),
                       {'user': 'PRIVATE', 'ip': 'PRIVATE'}, tmp_path / 'PRIVATE', checksum=checksum)
    diagnostic = failure.value.diagnostic
    assert diagnostic['operation'] == ('downloads-checksum-copy' if checksum else 'downloads-incremental-copy')
    assert diagnostic['category'] == ('signal' if returncode < 0 else 'nonzero_exit')
    assert 'PRIVATE' not in repr(diagnostic)


def test_download_failure_retains_deployment():
    class FailedRPC(RPC):
        def call(self, method, arguments):
            result = super().call(method, arguments)
            result['torrents'][0]['error'] = 1
            return result
    with pytest.raises(DeployError) as failure:
        sync_downloads(FailedRPC(), [MAGNET], lambda **kw: pytest.fail('copy'),
                       lambda: pytest.fail('stop'), lambda: pytest.fail('delete'))
    assert failure.value.diagnostic['operation'] == 'transmission-download'


def test_rpc_invalid_torrent_list_is_diagnosed(monkeypatch):
    from io import BytesIO
    from alice.transmission import RPC as Client
    monkeypatch.setattr('alice.transmission.urlopen', lambda *a, **kw: BytesIO(
        b'{"result":"success","arguments":{"torrents":["PRIVATE"]}}'))
    with pytest.raises(DeployError) as failure:
        Client(9091).call('torrent-get')
    assert failure.value.diagnostic['category'] == 'invalid_response'
    assert 'PRIVATE' not in repr(failure.value.diagnostic)
