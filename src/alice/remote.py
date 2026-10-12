"""Small root-side Transmission reconciler; sent over verified SSH."""
import fcntl
import json
import os
from pathlib import Path
import pwd
import subprocess
import sys
import time

# Production receives this stdlib helper prepended to the SSH program.
if 'build_diagnostic' not in globals():
    from pocketdeploy.diagnostics import build_diagnostic, CommandError

STAGE = 'validation'

DOWNLOADS = '/var/lib/transmission-daemon/downloads'


def run(*args, check=True):
    started = time.monotonic()
    operation = 'transmission-' + STAGE
    try:
        result = subprocess.run(args, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                text=True, env={**os.environ, 'DEBIAN_FRONTEND': 'noninteractive'})
    except OSError:
        raise CommandError(build_diagnostic(operation, 'unavailable', elapsed_seconds=time.monotonic() - started)) from None
    if check and result.returncode:
        raise CommandError(build_diagnostic(operation, 'signal' if result.returncode < 0 else 'nonzero_exit', returncode=result.returncode,
                                           elapsed_seconds=time.monotonic() - started)) from None
    return result


def verify_apparmor():
    profiles = Path('/sys/kernel/security/apparmor/profiles')
    if profiles.exists() and any(line.split(' (')[0] == 'transmission-daemon' for line in profiles.read_text().splitlines()):
        raise RuntimeError('Transmission AppArmor profile remains loaded')


def stop_service():
    run('systemctl', 'stop', 'transmission-daemon')
    verify_stopped()


def verify_stopped():
    if run('systemctl', 'is-active', 'transmission-daemon', check=False).returncode == 0:
        raise RuntimeError('service remains active')


def desired_settings(port):
    return {'rpc-bind-address': '127.0.0.1', 'rpc-port': port,
            'rpc-authentication-required': False, 'rpc-whitelist-enabled': True,
            'rpc-whitelist': '127.0.0.1,::1', 'download-dir': DOWNLOADS,
            'peer-port': 51413, 'peer-port-random-on-start': False}


def report_stage(stage, event):
    print(json.dumps({'stage': stage, 'event': event}), file=sys.stderr, flush=True)


def main(request):
    global STAGE
    STAGE = 'validation'
    action = request['action']
    if action == 'stop':
        STAGE = 'stop'
        stop_service()
        return {'ok': True}
    if action == 'status':
        STAGE = 'status'
        return {'ok': True, 'active': run('systemctl', 'is-active', 'transmission-daemon', check=False).returncode == 0}
    stages = ('apt-update', 'apt-install', 'apparmor', 'configuration', 'service')
    if action != 'converge':
        raise ValueError('unsupported action')
    port = request['port']
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('invalid port')
    for stage in stages:
        STAGE = stage
        report_stage(stage, 'started')
        if stage == 'apt-update':
            run('apt-get', 'update', '-qq')
        elif stage == 'apt-install':
            run('apt-get', 'install', '-y', '-qq', 'transmission-daemon', 'apparmor-utils', 'rsync')
        elif stage == 'apparmor':
            # Ubuntu 24.04's profile prevents systemd notification, even in complain mode.
            run('aa-disable', '/usr/bin/transmission-daemon', check=False)
            verify_apparmor()
            stop_service()
        elif stage == 'configuration':
            # Do not edit settings while Transmission can overwrite them.
            verify_stopped()
            path = Path('/etc/transmission-daemon/settings.json')
            settings = json.loads(path.read_text())
            settings.update(desired_settings(port))
            path.write_text(json.dumps(settings, indent=2) + '\n')
            downloads = Path(DOWNLOADS)
            downloads.mkdir(parents=True, exist_ok=True)
            user = pwd.getpwnam('debian-transmission')
            os.chown(downloads, user.pw_uid, user.pw_gid)
            os.chmod(downloads, 0o755)
        elif stage == 'service':
            # Verify safety immediately before starting the service.
            verify_apparmor()
            settings = json.loads(Path('/etc/transmission-daemon/settings.json').read_text())
            if any(settings.get(key) != value for key, value in desired_settings(port).items()):
                raise RuntimeError('Transmission configuration does not match desired settings')
            run('systemctl', 'enable', '--now', 'transmission-daemon')
            run('systemctl', 'is-active', '--quiet', 'transmission-daemon')
        report_stage(stage, 'completed')
    return {'ok': True}


if __name__ == '__main__':
    try:
        request = json.load(sys.stdin)
        with open('/run/lock/alice-transmission.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = main(request)
    except Exception as error:
        if STAGE in ('apt-update', 'apt-install', 'apparmor', 'configuration', 'service'):
            report_stage(STAGE, 'failed')
        result = {'ok': False, 'stage': STAGE}
        result['diagnostic'] = error.diagnostic if isinstance(error, CommandError) else build_diagnostic('transmission-' + STAGE)
    print(json.dumps(result))
