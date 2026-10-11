"""Alice's application CLI over PocketDeploy's shared runtime."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
from pocketdeploy.common import DeployError, local_path
from pocketdeploy.runtime import session, initialize, recorded_operation
from pocketdeploy.state import deployment_lock
from pocketdeploy.host import Host
from pocketdeploy.output import Reporter
from pocketdeploy import vault
from .config import load
from . import controller
from .transmission import tunnel

COMMANDS = ('init', 'plan', 'converge', 'status', 'ssh', 'ssh-install', 'ssh-uninstall',
            'tunnel', 'sync', 'delete', 'vault-save', 'vault-restore')


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise DeployError('Invalid arguments; run alice --help.')


def parser():
    p = Parser(prog='alice', description='Private Transmission on DigitalOcean using PocketDeploy.', allow_abbrev=False)
    p.add_argument('command', choices=COMMANDS)
    p.add_argument('-f', '--file', default='colors.yml')
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--json', action='store_true')
    p.add_argument('--quiet', action='store_true')
    p.add_argument('--verbose', action='store_true')
    p.add_argument('--ssh-command')
    p.add_argument('--document')
    p.add_argument('--version')
    p.add_argument('--destination')
    p.add_argument('--overwrite', action='store_true')
    return p


def execute(args):
    if args.json and args.command in ('ssh', 'tunnel'):
        raise DeployError('--json does not support interactive SSH or tunnel.')
    if args.quiet and args.verbose:
        raise DeployError('--quiet and --verbose conflict.')
    if args.dry_run and args.command not in ('converge', 'delete', 'plan'):
        raise DeployError('--dry-run supports converge, delete and plan.')
    if args.ssh_command and args.command != 'ssh':
        raise DeployError('--ssh-command requires ssh.')
    if (args.document or args.version or args.destination or args.overwrite) and args.command != 'vault-restore':
        raise DeployError('Restore options require vault-restore.')
    config, deployment = load(args.file)
    root = Path(deployment['_root'])
    if args.command == 'vault-restore':
        destination = Path(args.destination).absolute() if args.destination else root
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        with deployment_lock(local_path(destination, deployment['state-file'])):
            return vault.restore(deployment, destination, args.document, args.version, args.overwrite)
    if args.command == 'ssh-uninstall':
        return {'removed': controller.aliases(config, Host(deployment, None, root), remove=True)}
    read_only = args.command in ('plan', 'status') or args.dry_run
    with session(deployment, read_only=read_only, allow_create=args.command in ('init', 'plan', 'converge', 'sync')) as current:
        cloud, host, state = current.cloud, current.host, current.state
        if args.command == 'init':
            return initialize(deployment, state, host, current.root)
        if args.command == 'vault-save':
            return vault.save(deployment, state, current.root)
        if args.command == 'status':
            observed = cloud.inspect()
            result = {'resources': observed, 'state': state.safe_status() if state else None}
            if observed.get('compute'):
                result['transmission'] = controller.remote(host, cloud.connection(), config, 'status')
            return result
        if read_only:
            return {'actions': cloud.plan_delete() if args.command == 'delete' else cloud.plan(),
                    'protected': deployment['compute-prevent-destroy']}
        if args.command == 'ssh':
            return {'ssh_exit': host.ssh(cloud.connection(), args.ssh_command)}
        if args.command == 'ssh-install':
            connection = cloud.connection()
            host.prepare_transport(connection)
            return {'changed': controller.aliases(config, host, connection)}
        if args.command == 'tunnel':
            with tunnel(host.prepare_transport(cloud.connection()), config['transmission-tunnel-local-port'], config['transmission-rpc-port']):
                print(f"http://127.0.0.1:{config['transmission-tunnel-local-port']}/transmission/web/", flush=True)
                while True:
                    time.sleep(1)
        with recorded_operation(state, args.command, deployment['_desired_hash']) as operation:
            if args.command == 'converge':
                return {'connection': controller.converge(config, deployment, current, operation)}
            if args.command == 'sync':
                return controller.sync(config, deployment, current, operation)
            if args.command == 'delete':
                return controller.delete(config, deployment, current, operation)
            raise DeployError('Unsupported command.')


def main(argv=None):
    os.umask(0o077)
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        parser().print_help()
        return 0
    reporter = Reporter(json_mode='--json' in argv, quiet='--quiet' in argv)
    started = time.monotonic()
    try:
        args = parser().parse_args(argv)
        reporter.command, reporter.verbose = args.command, args.verbose
        with reporter.activate():
            result = execute(args)
        if args.command == 'ssh':
            return result['ssh_exit'] if result['ssh_exit'] >= 0 else 128 - result['ssh_exit']
        if args.json:
            reporter.success(result, elapsed_seconds=time.monotonic() - started)
        elif not args.quiet:
            print(json.dumps(result, indent=2))
        return 0
    except KeyboardInterrupt:
        reporter.failure('Interrupted; deployment retained for inspection.', code='interrupted')
        return 130
    except DeployError as error:
        reporter.failure(str(error), code=error.code, stage=error.stage or reporter.failed_stage,
                         elapsed_seconds=time.monotonic() - started)
        return 1
    except Exception:
        reporter.failure('Alice operation failed; private output suppressed. Inspect deployment state before retry.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
