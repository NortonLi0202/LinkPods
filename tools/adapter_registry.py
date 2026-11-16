"""Validate bridge registry source metadata without private binary inputs."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

ROOT = Path(__file__).resolve().parents[1]

SDP_FIELDS=('primary_handle_offset','record_count_offset','records_offset','record_stride',
            'record_handle_offset','attribute_count_offset','attributes_offset','attribute_stride',
            'attribute_length_offset','attribute_id_offset','attribute_type_offset','attribute_value_pointer_offset',
            'max_records','max_attributes')


def validate_sdp_layout(profile):
    layout=profile.get('sdp_identity')
    if not isinstance(layout,dict) or any(type(layout.get(key)) is not int or not 0<=layout[key]<=2**31-1 for key in SDP_FIELDS):
        raise ValueError('profile requires complete sdp_identity layout')
    if layout['record_stride']<1 or layout['attribute_stride']<1 or not 1<=layout['max_records']<=64 or not 1<=layout['max_attributes']<=50:
        raise ValueError('invalid sdp_identity layout bounds')
    for offset,size,stride in [('record_handle_offset',4,'record_stride'),('attribute_count_offset',2,'record_stride'),
            ('attribute_length_offset',4,'attribute_stride'),('attribute_id_offset',2,'attribute_stride'),
            ('attribute_type_offset',1,'attribute_stride'),('attribute_value_pointer_offset',8,'attribute_stride')]:
        if layout[offset]+size>layout[stride]:raise ValueError('sdp_identity layout field outside record')
    if layout['attributes_offset']+layout['max_attributes']*layout['attribute_stride']>layout['record_stride']:
        raise ValueError('sdp_identity attribute table outside record')


def safe_path(value, absolute=False):
    if not isinstance(value, str) or not re.fullmatch(r'/?[A-Za-z0-9_.\-/]+', value):
        raise ValueError('invalid adapter path')
    path = PurePosixPath(value)
    if path.is_absolute() != absolute or any(p in ('', '.', '..') for p in value.split('/')[1 if absolute else 0:]):
        raise ValueError('noncanonical adapter path: '+value)
    return value


def profile_hash(profile):
    # Identical serialization to prepare-runtime-bundle.py configuration.profile_sha256.
    return hashlib.sha256(json.dumps(profile, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def load_registry(root=ROOT, registry_path=None):
    root = Path(root).resolve()
    path = Path(registry_path) if registry_path is not None else root/'compatibility/adapters.json'
    document = json.loads(path.read_text(encoding='utf-8-sig'))
    if document.get('schema') != 1 or not isinstance(document.get('profiles'), list):
        raise ValueError('invalid bridge registry schema')
    result, ids, identities, prologue_paths = [], set(), set(), set()
    for entry in document['profiles']:
        identifier = entry.get('id', '')
        if not isinstance(identifier, str) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,79}', identifier) or identifier in ids:
            raise ValueError('invalid or duplicate adapter id')
        module = safe_path(entry.get('module_path'), absolute=True)
        source = safe_path(entry.get('profile'))
        resolved = (root/source).resolve()
        if not resolved.is_relative_to(root): raise ValueError('profile escapes repository')
        profile = json.loads(resolved.read_text(encoding='utf-8-sig'))
        validate_sdp_layout(profile)
        match = entry.get('match', 'exact')
        if match not in ('exact', 'prologue'):
            raise ValueError('invalid adapter match mode')
        if profile.get('module_path') != module:
            raise ValueError('adapter identity differs from profile')
        if match == 'exact':
            sha, build = entry.get('sha256', ''), entry.get('build_id', '')
            if not isinstance(sha, str) or not re.fullmatch(r'[0-9a-f]{64}', sha): raise ValueError('invalid adapter SHA-256')
            if not isinstance(build, str) or not re.fullmatch(r'(?:[0-9a-f]{2}){1,64}', build): raise ValueError('invalid adapter BuildID')
            note = profile.get('build_id', {})
            offset = note.get('offset')
            if profile.get('file_sha256') != sha or note.get('hex') != build:
                raise ValueError('adapter identity differs from profile')
            if type(offset) is not int or offset < 0 or offset > 2**31-1: raise ValueError('invalid BuildID offset')
            identity = (module, sha, build)
            if identity in identities: raise ValueError('ambiguous adapter identity')
            identities.add(identity)
        else:
            resolution = profile.get('resolution')
            if (not isinstance(resolution, dict) or resolution.get('schema') != 1
                    or resolution.get('mode') != 'prologue_scan'):
                raise ValueError('prologue adapter requires a prologue_scan profile')
            sha, build = entry.get('sha256', ''), entry.get('build_id', '')
            if not isinstance(sha, str) or (sha and not re.fullmatch(r'[0-9a-f]{64}', sha)):
                raise ValueError('invalid adapter SHA-256')
            if not isinstance(build, str) or (build and not re.fullmatch(r'(?:[0-9a-f]{2}){1,64}', build)):
                raise ValueError('invalid adapter BuildID')
            note = profile.get('build_id')
            if not isinstance(note, dict):
                raise ValueError('profile build_id must be an object')
            if sha and sha != profile.get('file_sha256'):
                raise ValueError('adapter SHA-256 differs from profile')
            if build and build != note.get('hex'):
                raise ValueError('adapter BuildID differs from profile')
            if module in prologue_paths: raise ValueError('ambiguous prologue adapter for module path')
            prologue_paths.add(module)
            offset = note.get('offset', 0)
            if type(offset) is not int or offset < 0 or offset > 2**31-1: raise ValueError('invalid BuildID offset')
        ids.add(identifier)
        result.append(dict(entry, match=match, build_id_offset=offset, profile_sha256=profile_hash(profile)))
    return result
