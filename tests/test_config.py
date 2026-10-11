import pytest
from alice.config import load
from pocketdeploy.common import DeployError

BASE = '''profile: alice-test
provider-compute: digitalocean
digitalocean-account-id: 11111111-1111-1111-1111-111111111111
digitalocean-region: ams3
'''


def test_normalization_and_secret_exclusion(tmp_path):
    path = tmp_path / 'colors.yml'
    path.write_text(BASE + 'transmission-local-directory: downloads\n')
    c, d = load(path, {'COLORS_PAR_DO_TOKEN': 'SECRET', 'COLORS_PAR_COMPUTE_PREVENT_DESTROY': 'false'})
    assert d['compute-prevent-destroy'] is True
    assert d['digitalocean-token-env'] == 'COLORS_PAR_DO_TOKEN'
    assert c['transmission-local-directory'] == str(tmp_path / 'downloads')
    assert 'SECRET' not in repr(d)
    assert 'digitalocean-vpc-id' not in d
    assert {r['protocol'] for r in d['compute-ingress']} == {'tcp', 'udp'}


def test_explicit_vpc_and_hash(tmp_path):
    path = tmp_path / 'colors.yml'
    path.write_text(BASE + 'digitalocean-vpc-uuid: 22222222-2222-2222-2222-222222222222\n')
    _, d = load(path, {})
    assert d['digitalocean-vpc-id'].startswith('22222222')
    path.write_text(path.read_text() + 'transmission-rpc-port: 9092\n')
    assert load(path, {})[1]['_desired_hash'] != d['_desired_hash']


@pytest.mark.parametrize('extra', ['provider-backend: local', 'transmission-rpc-port: true', 'transmission-magnet-links: nope'])
def test_reject_bad_or_retired_fields(tmp_path, extra):
    path = tmp_path / 'colors.yml'; path.write_text(BASE + extra + '\n')
    with pytest.raises(DeployError):
        load(path, {})
