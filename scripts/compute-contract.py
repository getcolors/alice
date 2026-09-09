#!/usr/bin/env python3
"""Assert Alice's existing-network singleton and remote-state build contracts."""
import json
from pathlib import Path
import sys
root, mode, backend = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
shared = {p.name: json.loads(p.read_text()) for p in (root / 'shared').glob('*.tf.json')}
node = json.loads((root / 'nodes/0/node.tf.json').read_text())
assert set(node['resource']) == {'digitalocean_droplet'}
assert node['resource']['digitalocean_droplet']['node']['lifecycle']['prevent_destroy'] is True
params = node['output']['params']['value']
assert params['node_id'] == '0' and params['provider'] == 'digitalocean'
if mode == 'managed':
    assert 'shared-keygen.tf.json' in shared
    policy = shared['shared.tf.json']
    vpc = policy['data']['digitalocean_vpc']['network']
    assert vpc['region'] == 'ams3'
    assert vpc['lifecycle']['postcondition'][0]['condition'] == '${self.default}'
else:
    assert 'shared-keygen.tf.json' not in shared
    policy = shared['shared-referenced.tf.json']
    vpc = policy['data']['digitalocean_vpc']['network']
    assert vpc['id'] == '00000000-0000-4000-8000-000000000000'
    assert node['resource']['digitalocean_droplet']['node']['vpc_uuid'] == vpc['id']
    assert node['resource']['digitalocean_droplet']['node']['ssh_keys'] == ['812184']
assert 'digitalocean_vpc' not in policy['resource']
assert policy['output']['params']['value']['network_cidr'] == '${data.digitalocean_vpc.network.ip_range}'
assert {v['port_range'] for v in policy['locals']['public_ingress'].values()} == {'22', '51413'}
for stage in ['shared', 'nodes/0']:
    config = json.loads((root / stage / 'backend.tf.json').read_text())['terraform']['backend']['s3']
    assert '/compute/' in config['key'] and config['key'].endswith('.tfstate')
    assert not ({'access_key','secret_key','token'} & config.keys())
    assert ('endpoints' in config) == (backend == 'r2')
print('Alice existing-network singleton contract: passed')
