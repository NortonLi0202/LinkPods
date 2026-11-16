"""Validate the exact-firmware temporary Root payload catalog; never touches a device."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET_STRING_FIELDS = ('model', 'firmware', 'kernel', 'bluetooth_sha256')
ARTIFACT_FIELDS = ('exploit', 'module', 'loader', 'bootstrap')
STATUSES = ('device_tested', 'payload_ready', 'experimental', 'unverified')


def _artifact(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+', value) or value in ('.', '..'):
        raise ValueError('invalid artifact name')
    return value


def validate(document):
    if document.get('schema') != 1 or not isinstance(document.get('payloads'), list) or not document['payloads']:
        raise ValueError('invalid payload catalog schema')
    result, ids, identities = [], set(), set()
    for entry in document['payloads']:
        identifier = entry.get('id', '')
        if (not isinstance(identifier, str) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', identifier)
                or identifier in ids):
            raise ValueError('invalid or duplicate payload id')
        target = entry.get('target')
        if not isinstance(target, dict):
            raise ValueError('payload requires target identity')
        for key in TARGET_STRING_FIELDS:
            if not isinstance(target.get(key), str) or not target[key]:
                raise ValueError('payload target requires ' + key)
        if not re.fullmatch(r'[0-9a-f]{64}', target['bluetooth_sha256']):
            raise ValueError('invalid payload bluetooth_sha256')
        if type(target.get('android_sdk')) is not int or not 1 <= target['android_sdk'] <= 1000:
            raise ValueError('invalid payload android_sdk')
        identity = tuple(target[key] for key in
                         ('model', 'android_sdk', 'firmware', 'kernel', 'bluetooth_sha256'))
        if identity in identities:
            raise ValueError('ambiguous payload identity')
        identities.add(identity)
        artifacts = entry.get('artifacts')
        if not isinstance(artifacts, dict) or any(field not in artifacts for field in ARTIFACT_FIELDS):
            raise ValueError('payload requires exploit/module/loader/bootstrap artifacts')
        for field in ARTIFACT_FIELDS:
            _artifact(artifacts[field])
        if entry.get('status') not in STATUSES:
            raise ValueError('invalid payload status')
        ids.add(identifier)
        result.append(dict(entry))
    return result


def load(path=None):
    path = Path(path) if path is not None else ROOT / 'compatibility/payloads.json'
    return validate(json.loads(path.read_text(encoding='utf-8-sig')))
