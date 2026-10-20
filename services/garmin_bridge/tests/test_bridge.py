import inspect
import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient
from garminconnect import Garmin, GarminConnectAuthenticationError, GarminConnectConnectionError
from bridge.core import Bridge, Vault, normalize
from bridge.app import create_app


def raw(id=1, distance=11000):
    return dict(activityId=id, activityName="Seuil", activityType=dict(typeKey="running"), startTimeGMT="2026-10-19 06:30:00", startTimeLocal="2026-10-19 08:30:00", distance=distance, duration=3300, averageHR=155, elevationGain=80)


class Fake:
    def __init__(self):
        self.activities=[raw()]; self.missing_exist=False; self.require_mfa=False; self.failure=False; self.expired=False
    def login(self, email, password, is_cn): return self.require_mfa
    def mfa(self, code):
        if code != "123456": raise GarminConnectAuthenticationError()
    def restore(self, tokens, is_cn):
        if self.expired: raise GarminConnectAuthenticationError()
    def tokens(self): return '{"di_token":"secret-access","di_refresh_token":"secret-refresh"}'
    def page(self, start, limit):
        if self.failure: raise GarminConnectConnectionError("secret-ticket")
        return self.activities[start:start+limit]
    def exists(self, id):
        if self.failure: raise GarminConnectConnectionError()
        return self.missing_exist


@pytest.fixture
def setup(tmp_path):
    master=Fernet.generate_key().decode(); vault=Vault(tmp_path/'vault.db', master); fake=Fake()
    bridge=Bridge(vault,master,lambda:fake); bridge.login('athlete@example.invalid','password-not-stored')
    client=TestClient(create_app(bridge,'k'*48),raise_server_exceptions=False); client.headers['Authorization']='Bearer '+'k'*48
    return bridge,fake,client,vault


def test_normalization():
    a=normalize(raw()); assert a['averagePace']==300 and a['sport']=='RUN'
    assert a['startedAt']=='2026-10-19T06:30:00Z' and a['localDateTime']=='2026-10-19T08:30:00'
    assert a['fingerprint']==normalize(raw())['fingerprint']
    assert a['fingerprint']!=normalize(raw(distance=12000))['fingerprint']


def test_invalid_payload_never_commits(setup):
    b,f,c,v=setup; first=b.sync(); f.activities=[dict(raw(),duration=-1)]
    assert c.post('/v1/sync').status_code==502
    assert v.get('activities:'+first['accountId'])['revision']==1


def test_all_pages_including_old_modifications(setup):
    b,f,c,v=setup; f.activities=[raw(id=i) for i in range(1,207)]
    first=b.sync(); assert len(first['activities'])==206
    f.activities[-1]['distance']=12000
    second=b.sync(); assert second['revision']==2
    assert first['activities'][-1]['fingerprint']!=second['activities'][-1]['fingerprint']


def test_missing_requires_confirmed_deletion_and_tombstones_persist(setup):
    b,f,c,v=setup; b.sync(); f.activities=[]; f.missing_exist=True
    assert len(b.sync()['activities'])==1
    f.missing_exist=False; result=b.sync(); assert result['deletedIds']==['1'] and not result['activities']
    assert b.sync()['deletedIds']==['1']
    f.activities=[raw()]; assert not b.sync()['deletedIds']


def test_network_failure_redacted_and_no_partial_commit(setup):
    b,f,c,v=setup; first=b.sync(); f.failure=True
    r=c.post('/v1/sync'); assert r.status_code==502 and 'secret-ticket' not in r.text
    assert v.get('activities:'+first['accountId'])['revision']==1


def test_duplicate_page_is_incomplete(setup):
    b,f,c,v=setup; f.activities=[raw(),raw()]
    assert c.post('/v1/sync').json()['code']=='INCOMPLETE'


def test_mfa_retry_success_and_expiry(setup):
    b,f,c,v=setup; f.require_mfa=True
    r=c.post('/v1/login',json=dict(email='athlete@example.invalid',password='secret-password'))
    assert r.json()['needsMfa'] and 'secret-password' not in r.text
    challenge=r.json()['challengeId']
    assert c.post('/v1/mfa',json=dict(challengeId=challenge,code='000000')).status_code==401
    assert c.post('/v1/mfa',json=dict(challengeId=challenge,code='123456')).json()['connected']
    b.login('athlete@example.invalid','pass'); challenge=b.pending['challenge']; b.pending['expires']=0
    assert c.post('/v1/mfa',json=dict(challengeId=challenge,code='123456')).json()['code']=='MFA_EXPIRED'


def test_auth_expiry_disconnect_and_validation_redaction(setup):
    b,f,c,v=setup
    assert c.get('/v1/status',headers={'Authorization':'wrong'}).status_code==401
    assert 'tokens' not in c.get('/v1/status').text
    f.expired=True; assert c.post('/v1/sync').json()['code']=='AUTH_REQUIRED'
    assert not c.get('/v1/status').json()['connected']
    assert c.delete('/v1/session').json()==dict(connected=False) and v.get('session') is None
    assert 'password-must-not-leak' not in c.post('/v1/login',json=dict(email='a',password='password-must-not-leak')).text


def test_encrypted_at_rest_and_restart(setup):
    b,f,c,v=setup; result=b.sync(); data=open(v.filename,'rb').read()
    assert all(value not in data for value in [b'secret-access',b'secret-refresh',b'Seuil',b'password-not-stored'])
    assert Bridge(v,'identity',lambda:f).sync()['accountId']==result['accountId']


def test_pinned_sdk_contract():
    assert 'tokenstore' in inspect.signature(Garmin.login).parameters
    assert 'client_state' in inspect.signature(Garmin.resume_login).parameters
    assert all(hasattr(Garmin,name) for name in ['get_activities','get_activity'])


def test_sdk_adapter_confirms_only_404_or_410_and_requires_persistable_tokens():
    from types import SimpleNamespace
    from bridge.provider import PythonGarminProvider
    from garminconnect import GarminConnectNotFoundError
    p=PythonGarminProvider()
    def missing(id): raise GarminConnectNotFoundError()
    p.client=SimpleNamespace(get_activity=missing)
    assert not p.exists('1')
    def gone(id):
        e=GarminConnectConnectionError();e.response=SimpleNamespace(status_code=410);raise e
    p.client=SimpleNamespace(get_activity=gone)
    assert not p.exists('1')
    def bad_auth(id): raise GarminConnectAuthenticationError()
    p.client=SimpleNamespace(get_activity=bad_auth)
    with pytest.raises(GarminConnectAuthenticationError): p.exists('1')
    p.client=SimpleNamespace(client=SimpleNamespace(dumps=lambda:'{"di_token":null}'))
    with pytest.raises(GarminConnectAuthenticationError): p.tokens()
