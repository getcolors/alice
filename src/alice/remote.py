"""Small root-side Transmission reconciler; sent over verified SSH."""
import fcntl
import json
import os
from pathlib import Path
import pwd
import subprocess
import sys

STAGE = 'validation'

DOWNLOADS = '/var/lib/transmission-daemon/downloads'


def run(*args, check=True):
    return subprocess.run(args, check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, env={**os.environ, 'DEBIAN_FRONTEND': 'noninteractive'})


def main(request):
    global STAGE
    action = request['action']
    if action == 'stop':
        STAGE = 'stop'
        run('systemctl', 'stop', 'transmission-daemon')
        if run('systemctl', 'is-active', 'transmission-daemon', check=False).returncode == 0:
            raise RuntimeError('service remains active')
        return {'ok': True}
    if action == 'status':
        return {'ok': True, 'active': run('systemctl', 'is-active', 'transmission-daemon', check=False).returncode == 0}
    if action != 'converge':
        raise ValueError('unsupported action')
    port = request['port']
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('invalid port')
    STAGE = 'apt-update'
    run('apt-get', 'update', '-qq')
    STAGE = 'apt-install'
    run('apt-get', 'install', '-y', '-qq', 'transmission-daemon', 'apparmor-utils', 'rsync')
    # Ubuntu 24.04 AppArmor profile prevents systemd notification, even in complain mode.
    STAGE = 'apparmor'
    run('aa-disable', '/usr/bin/transmission-daemon', check=False)
    profiles = Path('/sys/kernel/security/apparmor/profiles')
    if profiles.exists() and any(line.split(' (')[0] == 'transmission-daemon' for line in profiles.read_text().splitlines()):
        raise RuntimeError('Transmission AppArmor profile remains loaded')
    run('systemctl', 'stop', 'transmission-daemon')
    STAGE = 'configuration'
    path = Path('/etc/transmission-daemon/settings.json')
    settings = json.loads(path.read_text())
    settings.update({'rpc-bind-address': '127.0.0.1', 'rpc-port': port,
                     'rpc-authentication-required': False, 'rpc-whitelist-enabled': True,
                     'rpc-whitelist': '127.0.0.1,::1', 'download-dir': DOWNLOADS,
                     'peer-port': 51413, 'peer-port-random-on-start': False})
    path.write_text(json.dumps(settings, indent=2) + '\n')
    downloads = Path(DOWNLOADS)
    downloads.mkdir(parents=True, exist_ok=True)
    user = pwd.getpwnam('debian-transmission')
    os.chown(downloads, user.pw_uid, user.pw_gid)
    os.chmod(downloads, 0o755)
    STAGE = 'service'
    run('systemctl', 'enable', '--now', 'transmission-daemon')
    run('systemctl', 'is-active', '--quiet', 'transmission-daemon')
    return {'ok': True}


if __name__ == '__main__':
    try:
        request = json.load(sys.stdin)
        with open('/run/lock/alice-transmission.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = main(request)
    except Exception as error:
        result = {'ok': False, 'stage': STAGE}
        if isinstance(error, subprocess.CalledProcessError):
            result['exit_code'] = error.returncode
    print(json.dumps(result))
