import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('payload_catalog', Path(__file__).parents[1] / 'payload_catalog.py')
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class PayloadCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.entry = {
            'id': 'synthetic-payload',
            'status': 'device_tested',
            'target': {'model': 'SM-X810', 'android_sdk': 36, 'firmware': 'X810ZCS6EZH3',
                       'kernel': '5.15.189-android13-8-3251856-abX810ZCS6EZH3',
                       'bluetooth_sha256': 'a' * 64},
            'artifacts': {'exploit': 'libdfroot.so', 'module': 'diagnostic.ko',
                          'loader': 'libksud.so', 'bootstrap': 'liblpbootstrap.so'},
        }

    def load(self, payloads):
        path = self.root / 'payloads.json'
        path.write_text(json.dumps({'schema': 1, 'payloads': payloads}))
        return catalog.load(path)

    def test_valid_catalog_keeps_exact_target(self):
        result = self.load([self.entry])
        self.assertEqual([entry['id'] for entry in result], ['synthetic-payload'])
        self.assertEqual(result[0]['target'], self.entry['target'])

    def test_duplicate_identity_and_id_are_refused(self):
        duplicate = dict(self.entry, id='synthetic-other')
        with self.assertRaisesRegex(ValueError, 'ambiguous payload identity'):
            self.load([self.entry, duplicate])
        with self.assertRaisesRegex(ValueError, 'duplicate payload id'):
            self.load([self.entry, dict(self.entry)])

    def test_incomplete_identity_artifacts_and_status_are_refused(self):
        for field, value in [('bluetooth_sha256', 'a' * 63), ('android_sdk', '36'), ('kernel', '')]:
            broken = json.loads(json.dumps(self.entry))
            broken['target'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.load([broken])
        broken = json.loads(json.dumps(self.entry))
        del broken['artifacts']['bootstrap']
        with self.assertRaisesRegex(ValueError, 'artifacts'):
            self.load([broken])
        broken = json.loads(json.dumps(self.entry))
        broken['artifacts']['loader'] = '../escape'
        with self.assertRaisesRegex(ValueError, 'artifact name'):
            self.load([broken])
        broken = json.loads(json.dumps(self.entry))
        broken['status'] = 'stable'
        with self.assertRaisesRegex(ValueError, 'payload status'):
            self.load([broken])

    def test_empty_catalog_is_refused(self):
        with self.assertRaisesRegex(ValueError, 'schema'):
            self.load([])

    def test_invalid_payload_id_is_refused(self):
        broken = json.loads(json.dumps(self.entry))
        broken['id'] = 'INVALID'
        with self.assertRaisesRegex(ValueError, 'payload id'):
            self.load([broken])
