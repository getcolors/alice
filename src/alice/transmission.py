"""Loopback-only Transmission RPC and verified copy ordering."""
from contextlib import contextmanager
import json
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen
from pocketdeploy.common import DeployError
from pocketdeploy.diagnostics import build_diagnostic


def magnet_hash(value):
    if not isinstance(value, str) or urlsplit(value).scheme != 'magnet':
        raise DeployError('Expected a magnet URI.')
    topics = parse_qs(urlsplit(value).query).get('xt', [])
    hashes = [x[9:].lower() for x in topics if re.fullmatch(r'urn:btih:[0-9a-fA-F]{40}', x)]
    if len(hashes) != 1:
        raise DeployError('Each magnet needs exactly one 40-character BTIH hash.')
    return hashes[0]


class RPC:
    def __init__(self, port):
        self.url = f'http://127.0.0.1:{port}/transmission/rpc'
        self.session = ''

    def call(self, method, arguments=None):
        started = time.monotonic()
        def failure(category, status=None):
            return DeployError('Transmission RPC failed; deployment retained.',
                diagnostic=build_diagnostic('transmission-rpc', category, http_status=status,
                                            elapsed_seconds=time.monotonic() - started))
        data = json.dumps({'method': method, 'arguments': arguments or {}}).encode()
        for attempt in range(2):
            request = Request(self.url, data=data, headers={'X-Transmission-Session-Id': self.session})
            try:
                with urlopen(request, timeout=30) as response:
                    result = json.load(response)
                if not isinstance(result, dict) or not isinstance(result.get('result'), str):
                    raise failure('invalid_response')
                if result['result'] != 'success':
                    raise failure('rejected')
                arguments = result.get('arguments', {})
                if not isinstance(arguments, dict):
                    raise failure('invalid_response')
                if method == 'torrent-get':
                    torrents = arguments.get('torrents')
                    if not isinstance(torrents, list) or any(
                            not isinstance(item, dict) or not isinstance(item.get('hashString'), str)
                            for item in torrents):
                        raise failure('invalid_response')
                return arguments
            except HTTPError as error:
                if error.code != 409 or attempt:
                    raise failure('authentication' if error.code in (401, 403) else
                                  'rate_limit' if error.code == 429 else 'http', error.code) from None
                self.session = error.headers.get('X-Transmission-Session-Id', '')
            except TimeoutError:
                raise failure('timeout') from None
            except URLError as error:
                raise failure('timeout' if isinstance(error.reason, TimeoutError) else 'connection') from None
            except OSError:
                raise failure('connection') from None
            except ValueError:
                raise failure('invalid_response') from None
        raise failure('rejected')


@contextmanager
def tunnel(transport, local_port, remote_port):
    started = time.monotonic()
    with transport.tunnel(local_port, remote_port):
        for _ in range(60):
            try:
                with urlopen(f'http://127.0.0.1:{local_port}/transmission/web/', timeout=2) as response:
                    if response.status == 200:
                        break
            except (URLError, OSError):
                time.sleep(1)
        else:
            raise DeployError('Tunneled Transmission UI acceptance timed out.',
                diagnostic=build_diagnostic('transmission-ui', 'timeout', elapsed_seconds=time.monotonic() - started))
        yield RPC(local_port)


def copy_downloads(transport, connection, destination, *, checksum=False):
    Path(destination).mkdir(parents=True, exist_ok=True)
    command = ['rsync', '-a', '--protect-args', '--partial']
    if checksum:
        command.append('--checksum')
    command += ['-e', transport.rsync_shell(), '--',
                f"{connection['user']}@{connection['ip']}:/var/lib/transmission-daemon/downloads/",
                str(Path(destination).absolute()) + '/']
    started = time.monotonic()
    operation = 'downloads-checksum-copy' if checksum else 'downloads-incremental-copy'
    try:
        completed = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        raise DeployError('Download copy unavailable; deployment retained.',
            diagnostic=build_diagnostic(operation, 'unavailable', elapsed_seconds=time.monotonic() - started)) from None
    if completed.returncode:
        raise DeployError('Download copy failed; deployment retained.',
            diagnostic=build_diagnostic(operation, 'signal' if completed.returncode < 0 else 'nonzero_exit', returncode=completed.returncode,
                                        elapsed_seconds=time.monotonic() - started))


def sync_downloads(rpc, magnets, copy, stop, delete, *, sleep=time.sleep):
    wanted = {magnet_hash(magnet) for magnet in magnets}
    for magnet in magnets:
        rpc.call('torrent-add', {'filename': magnet})
    if wanted:
        rpc.call('torrent-start', {'ids': sorted(wanted)})
    while wanted:
        torrents = rpc.call('torrent-get', {'fields': ['hashString', 'percentDone', 'metadataPercentComplete', 'leftUntilDone', 'error']}).get('torrents', [])
        selected = {t['hashString'].lower(): t for t in torrents if t.get('hashString', '').lower() in wanted}
        if any(t.get('error', 0) for t in selected.values()):
            raise DeployError('Transmission reported a download error; deployment retained.',
                diagnostic=build_diagnostic('transmission-download', 'rejected'))
        copy(checksum=False)
        if set(selected) == wanted and all(t.get('percentDone') == 1 and t.get('metadataPercentComplete') == 1 and t.get('leftUntilDone') == 0 for t in selected.values()):
            break
        sleep(10)
    stop()
    copy(checksum=True)
    return delete()
