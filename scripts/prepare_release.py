#!/usr/bin/env python3
"""Create a verified GitHub release directory. Requires an explicitly signed release APK."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT)

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()

def sdk_tool(name):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        local = ROOT / 'local.properties'
        if local.exists():
            match = re.search(r'^sdk.dir=(.+)$', local.read_text(encoding="utf-8"), re.M)
            if match: sdk = match[1].replace('\\:', ':').replace('\\\\', '\\')
    path = Path(sdk or '') / 'build-tools' / '35.0.0' / (name + ('.bat' if os.name == 'nt' and name == 'apksigner' else '.exe' if os.name == 'nt' else ''))
    if not path.is_file(): raise ValueError(f'{name} introuvable. Définissez ANDROID_HOME avec les Build Tools 35.0.0.')
    return str(path)

def inspect_apk(apk, config):
    signature = command(sdk_tool('apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', signature)
    if certificates != [config['signingCertificateSha256']]: raise ValueError('Signature différente de la clé de distribution configurée. Publication refusée.')
    metadata = command(sdk_tool('aapt'), 'dump', 'badging', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", metadata)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", metadata, re.M)
    target = re.search(r"^targetSdkVersion:'([0-9]+)'", metadata, re.M)
    if not package or not minimum or not target or 'application-debuggable' in metadata: raise ValueError('Un APK release non débogable et signé est requis.')
    if package[1] != config['applicationId']: raise ValueError('Identifiant Android inattendu.')
    version = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', (ROOT/'app/build.gradle.kts').read_text(encoding="utf-8"))[1]
    code = int(re.search(r'versionCode = ([0-9]+)', (ROOT/'app/build.gradle.kts').read_text(encoding="utf-8"))[1])
    if package[3] != version or int(package[2]) != code: raise ValueError('APK périmé : sa version diffère du projet. Recompilez-le.')
    return version, code, int(minimum[1]), int(target[1]), signature

def notes_for(version):
    text = (ROOT / 'CHANGELOG.md').read_text(encoding="utf-8")
    match = re.search(r'^## '+re.escape(version)+r'\s*\n(.*?)(?=^## |\Z)', text, re.M | re.S)
    if not match or not match[1].strip(): raise ValueError('Ajoutez les nouveautés de cette version à CHANGELOG.md.')
    return match[1].strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT/'app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    config = json.loads((ROOT/'release-config.json').read_text(encoding="utf-8"))
    # Source files must be committed; local.properties, keys and dist/ are ignored.
    dirty = command('git','status','--porcelain','--untracked-files=normal')
    if dirty.strip(): raise ValueError('Le dépôt contient des modifications non enregistrées. Testez puis commitez les sources avant de préparer une publication.')
    version, code, minimum, target, signature = inspect_apk(args.apk.resolve(), config)
    notes = notes_for(version)
    commit = command('git','rev-parse','HEAD').strip()
    tag = 'v'+version
    output = (args.output or ROOT/'dist'/tag).resolve()
    if output.exists(): raise ValueError('Le dossier de sortie existe déjà. Choisissez un nouveau dossier pour ne pas écraser une publication.')
    output.mkdir(parents=True)
    try:
        apk_name = 'HybridCoach-'+version+'.apk'
        shutil.copy2(args.apk, output/apk_name)
        manifest = {
            'format':'fr.hybridcoach.update', 'schemaVersion':1,
            'applicationId':config['applicationId'], 'versionCode':code, 'versionName':version,
            'minSdk':minimum, 'targetSdk':target, 'signingCertificateSha256':config['signingCertificateSha256'],
            'apk':{'name':apk_name,'url':f'https://github.com/{config["repository"]}/releases/download/{tag}/{apk_name}', 'size':(output/apk_name).stat().st_size,'sha256':digest(output/apk_name)},
            'notes':notes,'sourceCommit':commit,
        }
        (output/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n', encoding="utf-8")
        (output/'release-notes.md').write_text(f'# Hybrid Coach {version}\n\n{notes}\n\n## Installation\n\nTélécharger `{apk_name}` puis confirmer la mise à jour dans Android. Installer par-dessus la version précédente signée avec la même clé ; ne pas désinstaller. Une sauvegarde JSON reste disponible dans Profil sportif → Sauvegarde et transfert.\n\nAndroid API {minimum} minimum · code {code}\n\nSources : `{commit}`\n', encoding="utf-8")
        (output/'INSTALLATION.md').write_text('''# Installer Hybrid Coach

1. Télécharger le fichier APK de cette publication sur le téléphone.
2. Ouvrir le fichier et autoriser cette source si Android le demande.
3. Confirmer l’installation ou la mise à jour, sans désinstaller la version précédente.
4. Pour les prochaines versions : Profil sportif → À propos et mises à jour.

Le carnet reste local. Exporter une sauvegarde JSON avant un changement de téléphone ou une désinstallation. La clé Garmin n’est jamais incluse dans cette sauvegarde.

Si Android refuse la signature : ne pas désinstaller sans sauvegarde ; vérifier que l’APK provient bien de ce dépôt et contacter le mainteneur.
''', encoding="utf-8")
        (output/'apk-signature.txt').write_text(signature, encoding="utf-8")
        command('git','archive','--format=zip','--prefix=HybridCoach/','--output='+str(output/'HybridCoach-sources.zip'),commit)
        with zipfile.ZipFile(output/'HybridCoach-sources.zip') as archive:
            if archive.testzip(): raise ValueError('Archive source invalide.')
            if any(re.search(r'(^|/)(local.properties|[^/]+\.(?:keystore|jks)|secrets.env)$', n) for n in archive.namelist()): raise ValueError('Un fichier privé apparaît dans les sources.')
        files = sorted(p for p in output.iterdir() if p.is_file())
        (output/'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in files), encoding="utf-8")
        print(f'Publication préparée : {output}\nVersion {version} · code {code} · {commit}\nSignature et sommes SHA-256 vérifiées.')
    except BaseException:
        shutil.rmtree(output)
        raise

if __name__ == '__main__':
    try: main()
    except (ValueError,subprocess.CalledProcessError) as exc: raise SystemExit(str(exc))
