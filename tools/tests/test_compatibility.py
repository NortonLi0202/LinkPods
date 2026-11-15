import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('compatibility', Path(__file__).parents[1] / 'compatibility.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

PROFILE = {'id': 'test-stack', 'sha256': 'a' * 64, 'build_id': 'b' * 40,
           'module_path': '/apex/example/lib64/bluetooth.so'}


class ClassificationTests(unittest.TestCase):
    def observed(self, **values):
        return {'root': True, 'system_control_channel': False, 'apple_identity': False,
                'module': {key: PROFILE[key] for key in ('sha256', 'build_id', 'module_path')}, **values}

    def test_direct_channel_needs_neither_root_nor_known_library(self):
        result = module.classify(self.observed(root=False, module=None, system_control_channel=True), [PROFILE])
        self.assertEqual(result['control_path'], 'system')
        self.assertTrue(result['control_channel_observed'])
        self.assertFalse(result['apple_identity_observed'])
        self.assertFalse(result['handoff_verified'])

    def test_known_library_and_root_are_only_a_bridge_candidate(self):
        result = module.classify(self.observed(), [PROFILE])
        self.assertEqual(result['control_path'], 'bridge_candidate')
        self.assertEqual(result['profile_id'], 'test-stack')
        self.assertIn('native_runtime_validation_required', result['missing'])
        self.assertFalse(result['control_channel_observed'])

    def test_existing_root_does_not_make_unknown_library_supported(self):
        observation = self.observed()
        observation['module']['sha256'] = 'c' * 64
        result = module.classify(observation, [PROFILE])
        self.assertEqual(result['control_path'], 'unavailable')
        self.assertIn('matching_bridge_profile_missing', result['missing'])

    def test_all_identity_fields_must_match(self):
        for field, value in [('build_id', 'c' * 40), ('module_path', '/other/path.so')]:
            observation = self.observed()
            observation['module'][field] = value
            self.assertIsNone(module.classify(observation, [PROFILE])['profile_id'])

    def test_brand_and_android_version_never_select_an_adapter(self):
        result = module.classify({'brand': 'Samsung', 'android_version': 16, 'root': True}, [PROFILE])
        self.assertEqual(result['control_path'], 'unknown')
        self.assertIsNone(result['profile_id'])

    def test_no_root_does_not_offer_a_native_bridge(self):
        result = module.classify(self.observed(root=False), [PROFILE])
        self.assertEqual(result['control_path'], 'unavailable')
        self.assertIn('root_not_available', result['missing'])

    def test_existing_root_is_preferred_to_temporary_root(self):
        result = module.classify(self.observed(temporary_root_available=True), [PROFILE])
        self.assertEqual(result['root_path'], 'existing_root')

    def test_supported_temporary_root_is_pending_not_granted(self):
        result = module.classify(self.observed(root=False, temporary_root_available=True), [PROFILE])
        self.assertEqual(result['root_path'], 'temporary_root_required')
        self.assertEqual(result['control_path'], 'unavailable')
        self.assertIn('root_not_available', result['missing'])

    def test_unsupported_temporary_root_does_not_offer_exploit(self):
        result = module.classify(self.observed(root=False, temporary_root_available=False), [PROFILE])
        self.assertEqual(result['root_path'], 'unsupported')

    def test_absent_observations_stay_unknown(self):
        result = module.classify({}, [])
        self.assertEqual(result['control_path'], 'unknown')
        self.assertIsNone(result['control_channel_observed'])
        self.assertIsNone(result['apple_identity_observed'])

    def test_capabilities_are_independent_and_no_input_claims_handoff_success(self):
        result = module.classify(self.observed(system_control_channel=True, apple_identity=True,
                                              handoff_verified=True), [PROFILE])
        self.assertTrue(result['apple_identity_observed'])
        self.assertFalse(result['handoff_verified'])
        self.assertEqual(result['verification'], 'offline_classification_only')

    def test_malformed_observations_rejected(self):
        for observation in [[], None, {'root': 'true'}, {'root': 1}, {'system_control_channel': 0},
                            {'module': 'library'}, {'module': {'sha256': 'bad'}}]:
            with self.subTest(observation=observation), self.assertRaises(ValueError):
                module.classify(observation, [PROFILE])

    def test_inputs_not_modified(self):
        observation = self.observed()
        original = copy.deepcopy(observation)
        profiles = [copy.deepcopy(PROFILE)]
        module.classify(observation, profiles)
        self.assertEqual(observation, original)
        self.assertEqual(profiles, [PROFILE])


if __name__ == '__main__':
    unittest.main()
