"""Application ordering; infrastructure, identity and recovery stay in PocketDeploy."""
import json
from pathlib import Path
from pocketdeploy.common import DeployError
from pocketdeploy import diagnostics
from pocketdeploy.runtime import initialize, delete_compute
from pocketdeploy import ssh_config
from pocketdeploy.output import operation as report_operation, remote_progress
from .transmission import tunnel, copy_downloads, sync_downloads, copy_preflight


def remote(host, connection, config, action):
    stages = {'validation', 'status', 'stop', 'apt-update', 'apt-install', 'apparmor', 'configuration', 'service'}
    with remote_progress(stages) as progress:
        result = host.run_python(connection, Path(diagnostics.__file__).read_text() + '\n' + Path(__file__).with_name('remote.py').read_text(),
                                 {'action': action, 'port': config['transmission-rpc-port']}, timeout=1200,
                                 progress=progress, allowed_stages=stages)
    if not isinstance(result, dict) or result.get('ok') is not True:
        stage = result.get('stage') if isinstance(result, dict) else None
        stage = stage if stage in {'validation', 'status', 'stop', 'apt-update', 'apt-install', 'apparmor', 'configuration', 'service'} else 'provisioning'
        diagnostic = diagnostics.validate_diagnostic(result.get('diagnostic'),
                      allowed_operations={'transmission-' + stage}) if isinstance(result, dict) else None
        raise DeployError('Transmission ' + stage + ' failed; deployment retained.', stage=stage, diagnostic=diagnostic)
    return result


def aliases(config, host, connection=None, *, remove=False):
    try:
        return ssh_config.update({'host_alias': config['profile'], 'block_state': 'absent' if remove else 'present',
                                  'ssh_hosts': [] if remove else [{'name': config['profile'], 'ip': connection['ip'], 'user': connection['user']}],
                                  'identity_file': str(host.key), 'known_hosts_file': str(host.known)}, owner='ALICE')
    except (OSError, ValueError):
        raise DeployError('SSH alias update refused; check ownership and paths.') from None


def converge(config, deployment, session, operation):
    state, cloud, host = session.state, session.cloud, session.host
    with report_operation('preflight'):
        cloud.plan()
    with report_operation('local-authority'):
        initialize(deployment, state, host, session.root)
        deployment['_cloud_init'] = host.cloud_init()
    try:
        with report_operation('cloud-readiness'):
            connection = cloud.converge(host.pub.read_text().strip(), operation)
        with report_operation('ssh-trust'):
            host.rotate_key(connection, operation)
        evidence = {'instance_id': connection['instance_id'], 'desired_hash': deployment['_desired_hash']}
        step = state.intent(operation, 'transmission-converge', evidence)
        with report_operation('transmission-provisioning'):
            remote(host, connection, config, 'converge')
        with report_operation('transmission-verification'):
            with tunnel(host.prepare_transport(connection), config['transmission-tunnel-local-port'], config['transmission-rpc-port']) as rpc:
                with report_operation('transmission-rpc-verification'):
                    rpc.call('session-get')
        state.complete(step, {'verified': True})
        for row in state.db.execute("SELECT id,payload FROM steps WHERE step='transmission-converge' AND status='pending'").fetchall():
            if json.loads(row['payload']) == evidence:
                state.complete(row['id'], {'verified': True})
        with report_operation('ssh-alias'):
            aliases(config, host, connection)
        return connection
    finally:
        deployment.pop('_cloud_init', None)


def delete(config, deployment, session, operation):
    with report_operation('teardown'):
        return delete_compute(deployment, session.state, session.cloud, session.host, operation,
                              before_cleanup=lambda: aliases(config, session.host, remove=True))


def sync(config, deployment, session, operation):
    if deployment['compute-prevent-destroy']:
        raise DeployError('Sync teardown requires compute-prevent-destroy: false explicitly.')
    copy_preflight(config['transmission-local-directory'])
    connection = converge(config, deployment, session, operation)
    transport = session.host.prepare_transport(connection)
    with tunnel(transport, config['transmission-tunnel-local-port'], config['transmission-rpc-port']) as rpc:
        return sync_downloads(rpc, config['transmission-magnet-links'],
                              lambda **kw: copy_downloads(transport, connection, config['transmission-local-directory'], **kw),
                              lambda: remote(session.host, connection, config, 'stop'),
                              lambda: delete(config, deployment, session, operation))
