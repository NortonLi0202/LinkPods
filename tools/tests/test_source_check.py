import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('source_project',Path(__file__).parents[1]/'project.py')
project=importlib.util.module_from_spec(spec);spec.loader.exec_module(project)


class SourceCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        for name in ('apps/android/src/Main.java','docs/v1.md','docs/issues.md'):
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('source')
        config={'product':{'source':'apps/android'},'release_notes':'docs/v1.md','issues':'docs/issues.md'}
        self.write('project.json',config)
        digest=hashlib.sha256(b'expected binary').hexdigest()
        self.write('third_party/payloads/manifest.json',{'inputs':{'frida':{'file':'frida-inject','sha256':digest}}})
        self.write('third_party/dependencies.json',{'dependencies':{'library':{'path':'apps/android/lib/library.jar','sha256':digest}}})
        source=self.root/'third_party/sources/helper.c';source.parent.mkdir(parents=True);source.write_bytes(b'source')
        self.write('third_party/sources/manifest.json',{'files':{'helper.c':{'sha256':hashlib.sha256(b'source').hexdigest(),'bytes':6}}})

    def write(self,name,value):
        p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value))

    def check(self,strict=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return project.check(self.root,require_inputs=strict)

    def test_source_checkout_checks_without_unpublished_binaries(self):
        self.assertEqual(self.check(),0)
        self.assertEqual(self.check(strict=True),1)

    def test_present_input_must_match_pin_even_in_source_mode(self):
        (self.root/'third_party/payloads/frida-inject').write_bytes(b'wrong binary')
        self.assertEqual(self.check(),1)

    def test_missing_corresponding_source_is_always_an_error(self):
        (self.root/'third_party/sources/helper.c').unlink()
        self.assertEqual(self.check(),1)

    def test_full_input_mode_accepts_only_complete_pinned_inputs(self):
        (self.root/'third_party/payloads/frida-inject').write_bytes(b'expected binary')
        jar=self.root/'apps/android/lib/library.jar';jar.parent.mkdir();jar.write_bytes(b'expected binary')
        self.assertEqual(self.check(strict=True),0)


if __name__=='__main__':unittest.main()
