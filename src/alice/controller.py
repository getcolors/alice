"""Application ordering; infrastructure, identity and recovery stay in PocketDeploy."""
import json
from pathlib import Path
from pocketdeploy.common import DeployError
from pocketdeploy.runtime import initialize, delete_compute
from pocketdeploy import ssh_config
from .transmission import tunnel, copy_downloads, sync_downloads


def remote(host, connection, config, action):
    result = host.run_python(connection, Path(__file__).with_name('remote.py').read_text(),
                             {'action': action, 'port': config['transmission-rpc-port']}, timeout=1200)
    if not isinstance(result, dict) or result.get('ok') is not True:
        stage = result.get('stage') if isinstance(result, dict) else None
        stage = stage if stage in {'validation', 'stop', 'apt-update', 'apt-install', 'apparmor', 'configuration', 'service'} else 'provisioning'
        raise DeployError('Transmission ' + stage + ' failed; deployment retained.', stage=stage)
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
    cloud.plan()
    initialize(deployment, state, host, session.root)
    deployment['_cloud_init'] = host.cloud_init()
    try:
        connection = cloud.converge(host.pub.read_text().strip(), operation)
        host.rotate_key(connection, operation)
        evidence = {'instance_id': connection['instance_id'], 'desired_hash': deployment['_desired_hash']}
        step = state.intent(operation, 'transmission-converge', evidence)
        remote(host, connection, config, 'converge')
        with tunnel(host.prepare_transport(connection), config['transmission-tunnel-local-port'], config['transmission-rpc-port']) as rpc:
            rpc.call('session-get')
        state.complete(step, {'verified': True})
        for row in state.db.execute("SELECT id,payload FROM steps WHERE step='transmission-converge' AND status='pending'").fetchall():
            if json.loads(row['payload']) == evidence:
                state.complete(row['id'], {'verified': True})
        aliases(config, host, connection)
        return connection
    finally:
        deployment.pop('_cloud_init', None)


def delete(config, deployment, session, operation):
    return delete_compute(deployment, session.state, session.cloud, session.host, operation,
                          before_cleanup=lambda: aliases(config, session.host, remove=True))


def sync(config, deployment, session, operation):
    if deployment['compute-prevent-destroy']:
        raise DeployError('Sync teardown requires compute-prevent-destroy: false explicitly.')
    connection = converge(config, deployment, session, operation)
    transport = session.host.prepare_transport(connection)
    with tunnel(transport, config['transmission-tunnel-local-port'], config['transmission-rpc-port']) as rpc:
        return sync_downloads(rpc, config['transmission-magnet-links'],
                              lambda **kw: copy_downloads(transport, connection, config['transmission-local-directory'], **kw),
                              lambda: remote(session.host, connection, config, 'stop'),
                              lambda: delete(config, deployment, session, operation))
