#!/usr/bin/env python3
"""Fail closed on an incomplete or incorrectly identified Meta release APK set.

The workflow's preceding apksigner gate remains responsible for cryptographic
verification and the existing production certificate. This adds package, version
and ABI checks before any artifact is uploaded or release is published.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
PACKAGE = 'io.chainbox.clash'

def require(condition, message):
    if not condition:
        raise ValueError(message)

def validate_metadata(badging, entries, abi, version, code):
    lines = badging.splitlines()
    require(bool(lines) and lines[0].startswith('package: '), 'Missing package metadata')
    attrs = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)='([^']*)'", lines[0]))
    require(attrs.get('name') == PACKAGE, 'Unexpected package name')
    require(attrs.get('versionCode') == str(code), 'Unexpected version code')
    require(attrs.get('versionName') == version + '.Meta', 'Unexpected version name')
    expected = ABIS if abi == 'universal' else {abi}
    require(expected <= ABIS and bool(expected), 'Unknown release ABI')
    native_lines = [line for line in lines if line.startswith('native-code:')]
    require(len(native_lines) == 1, 'Missing or duplicate native-code metadata')
    declared = set(re.findall(r"'([^']+)'", native_lines[0]))
    actual = {name.split('/')[1] for name in entries
              if name.startswith('lib/') and name.endswith('.so') and len(name.split('/')) == 3}
    require(declared == expected and actual == expected, 'Unexpected native ABI set')
    for architecture in expected:
        require(f'lib/{architecture}/libclash.so' in entries, 'Missing native core library')
    return {'package': attrs['name'], 'version_name': attrs['versionName'],
            'version_code': int(attrs['versionCode']), 'native_abis': sorted(actual)}

def verify_set(directory, aapt, version, code):
    paths = list(Path(directory).glob('*.apk'))
    expected = {f'Angela-Clash-{version}-meta-{abi}-release.apk': abi
                for abi in ABIS | {'universal'}}
    require({p.name for p in paths} == set(expected), 'Expected exactly five named release APKs')
    results = []
    for path in sorted(paths):
        badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(path)], text=True)
        with zipfile.ZipFile(path) as apk:
            require(apk.testzip() is None, 'Corrupt APK ZIP member')
            entries = set(apk.namelist())
        result = validate_metadata(badging, entries, expected[path.name], version, code)
        results.append({'file': path.name, **result})
        print('Verified release identity and ABI: ' + path.name)
    return results

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    results = verify_set(args.directory, args.aapt, args.version, args.code)
    Path(args.report).write_text(json.dumps(results, indent=2) + '\n')

if __name__ == '__main__':
    main()
