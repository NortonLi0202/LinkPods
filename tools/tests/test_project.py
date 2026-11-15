import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('project', Path(__file__).parents[1] / 'project.py')
project = importlib.util.module_from_spec(spec)
spec.loader.exec_module(project)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'apps/android'
        self.source.mkdir(parents=True)
        (self.source / 'src').mkdir()
        (self.source / 'src/Main.java').write_text('class Main {}')
        (self.source / 'build').mkdir()
        (self.source / 'build/key.jks').write_bytes(b'PRIVATE')
        (self.source / 'secret.key').write_bytes(b'PRIVATE')
        self.apk = self.source / 'build/app.apk'
        self.apk.write_bytes(b'apk fixture')

    def archive(self, name='test-1'):
        return project.snapshot(self.root, name, 'apps/android', self.apk)

    def test_roundtrip_and_excludes_keys_build(self):
        manifest = self.archive()
        self.assertEqual(project.verify_manifest(manifest), [])
        with zipfile.ZipFile(manifest.parent / 'source.zip') as archive:
            self.assertEqual(archive.namelist(), ['src/Main.java'])

    def test_corruption_and_missing_file_detected(self):
        manifest = self.archive()
        (manifest.parent / 'app.apk').write_bytes(b'changed')
        self.assertTrue(any('mismatch' in item for item in project.verify_manifest(manifest)))
        (manifest.parent / 'app.apk').unlink()
        self.assertTrue(any('missing' in item for item in project.verify_manifest(manifest)))

    def test_existing_release_never_overwritten(self):
        manifest = self.archive()
        original = manifest.read_bytes()
        with self.assertRaises(FileExistsError):
            self.archive()
        self.assertEqual(manifest.read_bytes(), original)

    def test_native_bootstrap_source_is_archived(self):
        (self.source/'native').mkdir()
        (self.source/'native/bootstrap.c').write_text('int main(void) {return 0;}')
        manifest=self.archive()
        with zipfile.ZipFile(manifest.parent/'source.zip') as archive:
            self.assertIn('native/bootstrap.c',archive.namelist())

    def test_android_image_resource_bytes_are_archived(self):
        resource=self.source/'res/drawable/earbud.png'
        resource.parent.mkdir(parents=True)
        resource.write_bytes(b'PNG source fixture')
        manifest=self.archive()
        with zipfile.ZipFile(manifest.parent/'source.zip') as archive:
            self.assertEqual(archive.read('res/drawable/earbud.png'),resource.read_bytes())

    def test_bad_id_and_external_source_rejected(self):
        for value in ['../escape', 'a/b', '.', 'a:b']:
            with self.assertRaises(ValueError):
                self.archive(value)
        with self.assertRaises(ValueError):
            project.snapshot(self.root, 'test-1', '../elsewhere', self.apk)

    def test_missing_input_does_not_create_release(self):
        with self.assertRaises(FileNotFoundError):
            project.snapshot(self.root, 'test-1', 'apps/android', self.root / 'missing.apk')
        self.assertFalse((self.root / 'releases/test-1').exists())

    def test_symlink_source_rejected(self):
        external = self.root / 'outside.java'
        external.write_text('private')
        try:
            (self.source / 'src/link.java').symlink_to(external)
        except OSError:
            self.skipTest('OS does not permit symlinks')
        with self.assertRaises(ValueError):
            self.archive()

    def test_manifest_path_escape_rejected(self):
        manifest = self.archive()
        import json
        data = json.loads(manifest.read_text())
        data['files']['../../outside'] = {'sha256': '0' * 64}
        manifest.write_text(json.dumps(data))
        self.assertTrue(any('unsafe' in item for item in project.verify_manifest(manifest)))


if __name__ == '__main__':
    unittest.main()
