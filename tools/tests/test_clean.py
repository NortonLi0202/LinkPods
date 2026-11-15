import importlib.util
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('project_cleanup',Path(__file__).parents[1]/'project.py')
project=importlib.util.module_from_spec(spec);spec.loader.exec_module(project)


class CleanTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.build=self.root/'apps/android/build';(self.build/'classes').mkdir(parents=True)
        (self.build/'classes/Main.class').write_bytes(b'generated')
        (self.build/'probe-debug.jks').write_bytes(b'unique signing identity')
        (self.root/'releases/stable').mkdir(parents=True)
        (self.root/'releases/stable/app.apk').write_bytes(b'release')
        (self.root/'research').mkdir()
        (self.root/'research/evidence.log').write_bytes(b'evidence')
        (self.root/'apps/android/src').mkdir()
        (self.root/'apps/android/src/Main.java').write_text('source')

    def test_default_is_preview_only(self):
        plan=project.clean(self.root)
        self.assertTrue(any(item['path']=='apps/android/build/classes' for item in plan))
        self.assertTrue((self.build/'classes/Main.class').exists())

    def test_apply_preserves_signing_sources_releases_and_evidence(self):
        project.clean(self.root,apply=True)
        self.assertFalse((self.build/'classes').exists())
        for path in ('apps/android/build/probe-debug.jks','apps/android/src/Main.java',
                     'releases/stable/app.apk','research/evidence.log'):
            self.assertTrue((self.root/path).is_file(),path)

    def test_nested_key_protects_entire_candidate(self):
        (self.build/'unexpected').mkdir()
        (self.build/'unexpected/private.keystore').write_bytes(b'key')
        project.clean(self.root,apply=True)
        self.assertEqual((self.build/'unexpected/private.keystore').read_bytes(),b'key')

    def test_external_link_never_followed(self):
        outside=self.root/'valuable';outside.mkdir();(outside/'data').write_text('keep')
        try:(self.build/'external').symlink_to(outside,target_is_directory=True)
        except OSError:self.skipTest('Windows does not allow creating symlinks')
        project.clean(self.root,apply=True)
        self.assertTrue((outside/'data').exists())


if __name__=='__main__':unittest.main()
