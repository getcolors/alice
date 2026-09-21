#!/usr/bin/env python3
"""Assert Alice's single-root compute and authoritative remote-key contract."""
import json
from pathlib import Path
import sys
root, network, backend = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
node = json.loads((root / 'compute.tf.json').read_text())
resources = node['resource']
assert resources['digitalocean_droplet']['node']['lifecycle']['prevent_destroy'] is True
assert resources['digitalocean_droplet']['node']['name'].endswith('-0')
assert resources['tls_private_key']['machine']['algorithm'] == 'ED25519'
assert set(resources['aws_s3_object']) == {'ssh_private', 'ssh_public'}
for name in ('ssh_private', 'ssh_public'):
    assert resources['aws_s3_object'][name]['provider'] == 'aws.keys'
    assert resources['aws_s3_object'][name]['force_destroy'] is False
assert 'aws_s3_object.ssh_private' in resources['digitalocean_droplet']['node']['depends_on']
assert node['output']['params']['value']['node_id'] == '0'
vpc = node['data']['digitalocean_vpc']['network']
if network == 'discovered':
    assert vpc['region'] == 'ams3'
    assert vpc['lifecycle']['postcondition'][0]['condition'] == '${self.default}'
else:
    assert vpc['id'] == '00000000-0000-4000-8000-000000000000'
assert 'digitalocean_vpc' not in resources
assert {v['port_range'] for v in node['locals']['public_ingress'].values()} == {'22', '51413'}
config = json.loads((root / 'backend.tf.json').read_text())['terraform']['backend']['s3']
assert config['key'].endswith('/alice-node-0.tfstate')
assert not ({'access_key', 'secret_key', 'token'} & config.keys())
assert ('endpoints' in config) == (backend == 'r2')
print('Alice single-node remote-key contract: passed')
