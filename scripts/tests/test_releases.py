import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'))
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
prepare=module('prepare_release')
publish=module('publish_release')
CONFIG={'repository':'douwwii/HybridCoach','applicationId':'fr.hybridcoach','signingCertificateSha256':'a'*64}

class ReleaseToolsTest(unittest.TestCase):
    def fixture(self,directory):
        version='1.7.0';apk='HybridCoach-'+version+'.apk'
        manifest={'format':'fr.hybridcoach.update','schemaVersion':1,'applicationId':CONFIG['applicationId'],'versionName':version,'versionCode':9,'sourceCommit':'b'*40,'signingCertificateSha256':'a'*64,
                  'apk':{'name':apk,'url':f'https://github.com/douwwii/HybridCoach/releases/download/v{version}/{apk}','size':3,'sha256':prepare.hashlib.sha256(b'apk').hexdigest()}}
        for name,content in {apk:b'apk','release-notes.md':b'notes','INSTALLATION.md':b'install','apk-signature.txt':b'signer','HybridCoach-sources.zip':b'sources'}.items(): (directory/name).write_bytes(content)
        (directory/'update.json').write_text(json.dumps(manifest))
        self.checks(directory)
        return manifest
    def checks(self,directory):
        files=sorted(p for p in directory.iterdir() if p.name!='SHA256SUMS')
        (directory/'SHA256SUMS').write_text(''.join(f'{prepare.digest(p)}  {p.name}\n' for p in files))
    def test_complete_assets_match_the_runtime_contract(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);manifest=self.fixture(directory)
            actual,assets=publish.verified_assets(directory,CONFIG)
            self.assertEqual(manifest,actual);self.assertEqual(7,len(assets))
    def test_tampered_apk_and_missing_metadata_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);self.fixture(directory)
            (directory/'HybridCoach-1.7.0.apk').write_bytes(b'bad')
            with self.assertRaises(ValueError):publish.verified_assets(directory,CONFIG)
            self.fixture(directory);(directory/'update.json').unlink()
            with self.assertRaises(FileNotFoundError):publish.verified_assets(directory,CONFIG)
    def test_path_traversal_and_extra_files_cannot_be_uploaded(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);self.fixture(directory)
            with (directory/'SHA256SUMS').open('a') as out:out.write('c'*64+'  ../private.key\n')
            with self.assertRaises(ValueError):publish.verified_assets(directory,CONFIG)
            self.fixture(directory);(directory/'private.key').write_text('not for publication');self.checks(directory)
            with self.assertRaises(ValueError):publish.verified_assets(directory,CONFIG)
    def test_wrong_repository_or_certificate_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d);manifest=self.fixture(directory)
            manifest['apk']['url']='https://attacker.test/apk'
            (directory/'update.json').write_text(json.dumps(manifest));self.checks(directory)
            with self.assertRaises(ValueError):publish.verified_assets(directory,CONFIG)
            self.fixture(directory)
            with self.assertRaises(ValueError):publish.verified_assets(directory,{**CONFIG,'signingCertificateSha256':'c'*64})
    def test_apk_inspection_refuses_wrong_key_and_debuggable_builds(self):
        signature='Signer #1 certificate SHA-256 digest: '+'a'*64
        import re
        gradle=(prepare.ROOT/'app/build.gradle.kts').read_text()
        version=re.search(r'versionName = \"([^\"]+)\"',gradle)[1]
        code=int(re.search(r'versionCode = ([0-9]+)',gradle)[1])
        metadata=f"package: name='fr.hybridcoach' versionCode='{code}' versionName='{version}'\nsdkVersion:'26'\ntargetSdkVersion:'35'\n"
        with patch.object(prepare,'sdk_tool',side_effect=lambda x:x):
            with patch.object(prepare,'command',side_effect=[signature,metadata]):
                self.assertEqual((version,code,26,35),prepare.inspect_apk(Path('test.apk'),CONFIG)[:4])
            with patch.object(prepare,'command',return_value=signature.replace('a'*64,'b'*64)):
                with self.assertRaises(ValueError):prepare.inspect_apk(Path('test.apk'),CONFIG)
            with patch.object(prepare,'command',side_effect=[signature,metadata+'application-debuggable\n']):
                with self.assertRaises(ValueError):prepare.inspect_apk(Path('test.apk'),CONFIG)
    def test_full_release_directory_contains_utf8_notes_and_exact_sources(self):
        import subprocess,argparse,contextlib,io
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            (root/'.gitignore').write_text('dist/\n*.apk\n',encoding='utf-8')
            (root/'release-config.json').write_text(json.dumps(CONFIG),encoding='utf-8')
            (root/'CHANGELOG.md').write_text('# Versions\n\n## 1.7.0\n\n- Séances été 🏃\n',encoding='utf-8')
            (root/'README.md').write_text('sources',encoding='utf-8')
            subprocess.run(['git','init','-q',str(root)],check=True)
            subprocess.run(['git','-C',str(root),'add','.'],check=True)
            subprocess.run(['git','-C',str(root),'-c','user.name=Release test','-c','user.email=test@example.invalid','commit','-qm','fixture'],check=True)
            apk=root/'test.apk';apk.write_bytes(b'apk')
            output=root/'dist'/'v1.7.0'
            with patch.object(prepare,'ROOT',root),patch.object(prepare.argparse.ArgumentParser,'parse_args',return_value=argparse.Namespace(apk=apk,output=output)),patch.object(prepare,'inspect_apk',return_value=('1.7.0',9,26,35,'signature')),contextlib.redirect_stdout(io.StringIO()):
                prepare.main()
            self.assertTrue((output/'INSTALLATION.md').read_text(encoding='utf-8').startswith('# Installer Hybrid Coach\n'))
            self.assertIn('Séances été 🏃',json.loads((output/'update.json').read_text(encoding='utf-8'))['notes'])
            manifest,assets=publish.verified_assets(output,CONFIG)
            self.assertEqual(7,len(assets))
            self.assertEqual(40,len(manifest['sourceCommit']))

    def test_changelog_extracts_only_the_requested_version(self):
        notes=prepare.notes_for('1.7.0')
        self.assertIn('GitHub',notes);self.assertNotIn('Interface allégée',notes)
        with self.assertRaises(ValueError):prepare.notes_for('99.0.0')

if __name__=='__main__':unittest.main()
