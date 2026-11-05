#!/usr/bin/env python3
"""Upload a verified release directory to GitHub; creates a draft unless --publish is given."""
import argparse
import hashlib
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def verified_assets(directory, config):
    directory = Path(directory)
    manifest = json.loads((directory/'update.json').read_text())
    version = manifest['versionName']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',version): raise ValueError('Version invalide.')
    apk = manifest['apk']
    if manifest['applicationId'] != config['applicationId'] or manifest['signingCertificateSha256'] != config['signingCertificateSha256']: raise ValueError('Configuration de signature incohérente.')
    expected_url = f'https://github.com/{config["repository"]}/releases/download/v{version}/HybridCoach-{version}.apk'
    if apk['url'] != expected_url or apk['name'] != f'HybridCoach-{version}.apk': raise ValueError('URL de publication incohérente.')
    if not re.fullmatch(r'[0-9a-f]{40}',manifest['sourceCommit']): raise ValueError('Commit source invalide.')
    checks = {}
    for line in (directory/'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._-]+)',line)
        if not match or match[2] in checks: raise ValueError('Liste SHA256SUMS invalide.')
        checks[match[2]] = match[1]
    expected_names = {apk['name'],'update.json','release-notes.md','INSTALLATION.md','apk-signature.txt','HybridCoach-sources.zip'}
    if set(checks) != expected_names: raise ValueError('Les fichiers de publication sont incomplets ou inattendus.')
    assets = []
    for name, expected in checks.items():
        path = directory/name
        if path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected: raise ValueError('Un fichier de publication est absent ou a été modifié : '+name)
        assets.append(path)
    if (directory/apk['name']).stat().st_size != apk['size'] or checks[apk['name']] != apk['sha256']: raise ValueError('L’APK diffère des informations de mise à jour.')
    return manifest, assets + [directory/'SHA256SUMS']

class GitHub:
    def __init__(self, token): self.token = token
    def request(self, method, url, body=None, binary=False):
        if not url.startswith(('https://api.github.com/','https://uploads.github.com/')): raise ValueError('Hôte GitHub non autorisé.')
        data = body if binary else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url,data=data,method=method,headers={
            'Authorization':'Bearer '+self.token,'User-Agent':'HybridCoach-release',
            'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2026-03-10',
            'Content-Type':'application/octet-stream' if binary else 'application/json',
        })
        # Do not redirect an authenticated request to any other host.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs): return None
        try:
            with urllib.request.build_opener(NoRedirect).open(req,timeout=120) as response:
                raw=response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            if exc.code == 404 and method == 'GET': return None
            raise ValueError(f'GitHub a refusé {method} (HTTP {exc.code}). Vérifiez votre accès au dépôt et aux publications.') from None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--publish',action='store_true',help='Publier après le téléversement complet ; sinon conserver un brouillon.')
    args=parser.parse_args()
    config=json.loads((ROOT/'release-config.json').read_text())
    manifest,assets=verified_assets(args.directory,config)
    token=os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not token: raise ValueError('Authentification absente. Utilisez un jeton GitHub dans GH_TOKEN/GITHUB_TOKEN ou le workflow GitHub Actions. Ne le placez jamais dans les sources.')
    github=GitHub(token)
    base=f'https://api.github.com/repos/{config["repository"]}'
    tag='v'+manifest['versionName']
    release=github.request('GET',base+'/releases/tags/'+tag)
    if release and (not release['draft'] or release.get('target_commitish') not in [manifest['sourceCommit']]): raise ValueError('Cette version existe déjà ou correspond à d’autres sources. Créez un nouveau numéro de version ; aucune publication existante n’est modifiée.')
    if release is None:
        release=github.request('POST',base+'/releases',{
            'tag_name':tag,'target_commitish':manifest['sourceCommit'],'name':'Hybrid Coach '+manifest['versionName'],
            'body':(args.directory/'release-notes.md').read_text(),'draft':True,'prerelease':False,
        })
    existing={asset['name']:asset for asset in release['assets']}
    for path in assets:
        if path.name in existing:
            remote=existing[path.name]
            expected='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest()
            if remote.get('digest') != expected or remote['size'] != path.stat().st_size: raise ValueError('Le brouillon contient déjà un fichier différent : '+path.name+'. Vérifiez-le sur GitHub avant toute modification.')
            continue
        upload=f'https://uploads.github.com/repos/{config["repository"]}/releases/{release["id"]}/assets?'+urllib.parse.urlencode({'name':path.name})
        github.request('POST',upload,path.read_bytes(),binary=True)
    if args.publish:
        release=github.request('PATCH',base+'/releases/'+str(release['id']),{'draft':False,'make_latest':'true'})
    print(('Publication disponible : ' if args.publish else 'Brouillon prêt à relire : ')+release['html_url'])

if __name__ == '__main__':
    try: main()
    except (ValueError,KeyError,FileNotFoundError) as exc: raise SystemExit(str(exc))
