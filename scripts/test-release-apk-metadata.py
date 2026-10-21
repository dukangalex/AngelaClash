#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('verify-release-apks.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class MetadataGateTest(unittest.TestCase):
    def fixture(self, abi='arm64-v8a'):
        abis = gate.ABIS if abi == 'universal' else {abi}
        badging = "package: name='io.chainbox.clash' versionCode='10105' versionName='1.1.5.Meta'\n"
        badging += 'native-code: ' + ' '.join(repr(a) for a in sorted(abis)) + '\n'
        return badging, {f'lib/{a}/libclash.so' for a in abis}
    def check(self, badging, entries, abi='arm64-v8a'):
        return gate.validate_metadata(badging, entries, abi, '1.1.5', 10105)
    def test_all_split_abis(self):
        for abi in gate.ABIS:
            self.check(*self.fixture(abi), abi)
    def test_universal(self): self.check(*self.fixture('universal'), 'universal')
    def test_wrong_package(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b.replace('io.chainbox.clash', 'other.app'), e)
    def test_wrong_code(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b.replace('10105', '10104'), e)
    def test_wrong_name(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b.replace('1.1.5.Meta', '1.1.5.Alpha'), e)
    def test_missing_metadata(self):
        with self.assertRaises(ValueError): self.check('', set())
    def test_missing_native_declaration(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b.splitlines()[0], e)
    def test_wrong_native_declaration(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b.replace("'arm64-v8a'", "'x86'"), e)
    def test_missing_native_core(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b, {'lib/arm64-v8a/other.so'})
    def test_extra_native_architecture(self):
        b, e = self.fixture()
        with self.assertRaises(ValueError): self.check(b, e | {'lib/x86/libclash.so'})
    def test_universal_incomplete(self):
        b, e = self.fixture('universal')
        with self.assertRaises(ValueError): self.check(b, e - {'lib/x86/libclash.so'}, 'universal')
    def test_empty_release_directory(self):
        with tempfile.TemporaryDirectory() as path:
            with self.assertRaises(ValueError): gate.verify_set(path, 'unreachable-aapt', '1.1.5', 10105)
    def test_extra_or_misnamed_file(self):
        with tempfile.TemporaryDirectory() as path:
            Path(path, 'unknown.apk').touch()
            with self.assertRaises(ValueError): gate.verify_set(path, 'unreachable-aapt', '1.1.5', 10105)

if __name__ == '__main__': unittest.main()
