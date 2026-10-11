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
        data = json.dumps({'method': method, 'arguments': arguments or {}}).encode()
        for attempt in range(2):
            request = Request(self.url, data=data, headers={'X-Transmission-Session-Id': self.session})
            try:
                with urlopen(request, timeout=30) as response:
                    result = json.load(response)
                if result.get('result') != 'success':
                    raise DeployError('Transmission RPC refused the operation.')
                return result.get('arguments', {})
            except HTTPError as error:
                if error.code != 409 or attempt:
                    raise DeployError('Transmission RPC HTTP request failed.') from None
                self.session = error.headers.get('X-Transmission-Session-Id', '')
            except (OSError, ValueError):
                raise DeployError('Transmission RPC unavailable or invalid.') from None
        raise DeployError('Transmission session negotiation failed.')


@contextmanager
def tunnel(transport, local_port, remote_port):
    with transport.tunnel(local_port, remote_port):
        for _ in range(60):
            try:
                with urlopen(f'http://127.0.0.1:{local_port}/transmission/web/', timeout=2) as response:
                    if response.status == 200:
                        break
            except (URLError, OSError):
                time.sleep(1)
        else:
            raise DeployError('Tunneled Transmission UI acceptance timed out.')
        yield RPC(local_port)


def copy_downloads(transport, connection, destination, *, checksum=False):
    Path(destination).mkdir(parents=True, exist_ok=True)
    command = ['rsync', '-a', '--protect-args', '--partial']
    if checksum:
        command.append('--checksum')
    command += ['-e', transport.rsync_shell(), '--',
                f"{connection['user']}@{connection['ip']}:/var/lib/transmission-daemon/downloads/",
                str(Path(destination).absolute()) + '/']
    completed = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if completed.returncode:
        raise DeployError('Download copy failed; deployment retained.')


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
            raise DeployError('Transmission reported a download error; deployment retained.')
        copy(checksum=False)
        if set(selected) == wanted and all(t.get('percentDone') == 1 and t.get('metadataPercentComplete') == 1 and t.get('leftUntilDone') == 0 for t in selected.values()):
            break
        sleep(10)
    stop()
    copy(checksum=True)
    return delete()
