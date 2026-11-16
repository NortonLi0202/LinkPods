import importlib.util
import json
from pathlib import Path
SDP_LAYOUT=json.loads(Path(__file__).with_name('sdp_fixture.json').read_text())
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
            profile = {'sdp_identity': dict(SDP_LAYOUT), 'module_path': '/apex/test/lib64/library%d.so' % index,
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

    def test_two_firmware_profiles_share_one_module_path(self):
        module=self.entries[0]['module_path']
        self.entries[1].update(module_path=module,build_id='cd'*16)
        path=self.root/self.entries[1]['profile']
        profile=json.loads(path.read_text())
        profile.update(module_path=module,build_id={'hex':'cd'*16,'offset':48})
        path.write_text(json.dumps(profile))
        adapters=self.load()
        self.assertEqual(adapters[0]['module_path'],adapters[1]['module_path'])
        self.assertNotEqual(adapters[0]['sha256'],adapters[1]['sha256'])
        self.assertNotEqual(adapters[0]['build_id'],adapters[1]['build_id'])

    def test_prologue_adapter_is_explicit_unique_and_requires_resolution(self):
        profile={'sdp_identity': dict(SDP_LAYOUT), 'module_path':'/apex/test/lib64/prologue.so',
                 'file_sha256':'c'*64, 'build_id':{'hex':'ab'*16,'offset':32},
                 'resolution':{'schema':1,'mode':'prologue_scan'}}
        path='profiles/prologue.json'; (self.root/path).write_text(json.dumps(profile))
        self.entries.append({'id':'synthetic-prologue','match':'prologue',
                             'module_path':profile['module_path'],'profile':path})
        adapters=self.load()
        prologue=[a for a in adapters if a['id']=='synthetic-prologue'][0]
        self.assertEqual(prologue['match'],'prologue')
        self.assertNotIn('sha256',prologue)
        self.assertNotIn('build_id',prologue)
        self.entries.append({'id':'synthetic-prologue-two','match':'prologue',
                             'module_path':profile['module_path'],'profile':path})
        with self.assertRaisesRegex(ValueError,'ambiguous prologue'): self.load()
        self.entries.pop()
        broken=dict(profile); broken.pop('resolution')
        broken['module_path']='/apex/test/lib64/broken.so'
        broken_path='profiles/broken.json'; (self.root/broken_path).write_text(json.dumps(broken))
        self.entries.append({'id':'synthetic-broken','match':'prologue',
                             'module_path':broken['module_path'],'profile':broken_path})
        with self.assertRaisesRegex(ValueError,'prologue_scan'): self.load()
