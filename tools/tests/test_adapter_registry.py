import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('adapter_registry', Path(__file__).parents[1]/'adapter_registry.py')
registry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(registry)


class AdapterRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entries = []
        for index in range(2):
            profile = {'module_path': '/apex/test/lib64/library%d.so' % index,
                       'file_sha256': str(index)*64, 'build_id': {'hex': 'ab'*16, 'offset': 32}}
            path = 'profiles/%d.json' % index
            (self.root/'profiles').mkdir(exist_ok=True)
            (self.root/path).write_text(json.dumps(profile))
            self.entries.append({'id': 'synthetic-%d' % index, 'module_path': profile['module_path'],
                                 'sha256': profile['file_sha256'], 'build_id': profile['build_id']['hex'], 'profile': path})

    def load(self):
        (self.root/'registry.json').write_text(json.dumps({'schema': 1, 'profiles': self.entries}))
        return registry.load_registry(self.root, self.root/'registry.json')

    def test_two_profiles_preserve_canonical_runtime_hash(self):
        adapters = self.load()
        self.assertEqual([a['id'] for a in adapters], ['synthetic-0', 'synthetic-1'])
        import hashlib
        for adapter in adapters:
            profile = json.loads((self.root/adapter['profile']).read_text())
            expected = hashlib.sha256(json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            self.assertEqual(adapter['profile_sha256'], expected)

    def test_invalid_paths_identity_and_duplicate_are_refused(self):
        for field, value in [('id', '../escape'), ('module_path', '/apex/../escape.so'),
                             ('profile', '../escape.json'), ('profile', 'C:/escape.json'),
                             ('sha256', 'a'*64), ('build_id', 'ff'*16)]:
            original = self.entries[0][field]
            self.entries[0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError): self.load()
            self.entries[0][field] = original
        self.entries.append(dict(self.entries[0], id='duplicate-identity'))
        with self.assertRaisesRegex(ValueError, 'ambiguous'): self.load()
