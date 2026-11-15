"""Classify supplied observations offline; never probe, root or modify a device."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def _identity(value):
    if not isinstance(value, dict):
        raise ValueError('module identity must be an object')
    for key, pattern in [('sha256', r'[0-9a-fA-F]{64}'), ('build_id', r'(?:[0-9a-fA-F]{2}){4,64}')]:
        if key in value and (not isinstance(value[key], str) or not re.fullmatch(pattern, value[key])):
            raise ValueError('invalid module ' + key)
    if 'module_path' in value and (not isinstance(value['module_path'], str) or not value['module_path'].startswith('/')):
        raise ValueError('module_path must be absolute')
    return tuple(value.get(key, '').lower() if key != 'module_path' else value.get(key, '')
                 for key in ('sha256', 'build_id', 'module_path'))


def classify(observed, profiles):
    if not isinstance(observed, dict):
        raise ValueError('observations must be an object')
    for key in ('root', 'system_control_channel', 'apple_identity', 'temporary_root_available'):
        if observed.get(key) is not None and type(observed[key]) is not bool:
            raise ValueError(key + ' must be true, false or null')
    identity = _identity(observed['module']) if observed.get('module') is not None else None
    if not isinstance(profiles, list):
        raise ValueError('profiles must be a list')
    match = None
    for profile in profiles:
        expected = _identity(profile)
        if not all(expected) or not isinstance(profile.get('id'), str) or not profile['id']:
            raise ValueError('profile requires id and complete module identity')
        if identity == expected:
            if match is not None:
                raise ValueError('ambiguous matching profiles')
            match = profile['id']
    root = observed.get('root')
    temporary = observed.get('temporary_root_available')
    root_path = ('existing_root' if root is True else
                 'temporary_root_required' if root is False and temporary is True else
                 'unsupported' if root is False and temporary is False else 'unknown')
    missing = []
    direct = observed.get('system_control_channel')
    if direct is True:
        path = 'system'
    elif root is True and match:
        path = 'bridge_candidate'
        missing.append('native_runtime_validation_required')
    else:
        path = 'unknown' if direct is None or root is None or identity is None else 'unavailable'
        if root is not True:
            missing.append('root_not_available' if root is False else 'root_not_observed')
        if not match:
            missing.append('matching_bridge_profile_missing')
        if direct is not True:
            missing.append('system_control_channel_not_observed')
    return {'control_path': path, 'root_path': root_path, 'profile_id': match,
            'control_channel_observed': direct, 'apple_identity_observed': observed.get('apple_identity'),
            'missing': missing, 'handoff_verified': False, 'verification': 'offline_classification_only'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('observations', type=Path)
    parser.add_argument('--profiles', type=Path, default=ROOT / 'compatibility/adapters.json')
    args = parser.parse_args()
    observed = json.loads(args.observations.read_text(encoding='utf-8-sig'))
    profiles = json.loads(args.profiles.read_text(encoding='utf-8-sig'))['profiles']
    print(json.dumps(classify(observed, profiles), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
