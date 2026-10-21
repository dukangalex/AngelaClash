#!/usr/bin/env python3
"""Exercise the actual release-gate shell extracted from its workflow, using fake tool output.
This tests command selection/fail-closed comparison, not APK cryptography. No signing keys.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
yaml = (ROOT / '.github/workflows/build-release.yaml').read_text()
section = yaml.split('      - name: Verify every release APK against the existing signing certificate\n', 1)[1]
block = section.split('\n      - name:', 1)[0].split('        run: |\n', 1)[1]
script = '\n'.join(line[10:] for line in block.splitlines()) + '\n'
CERT = '82d0db007c5eafc37e126b4fdc16660d7cf2434c7a15e610f4b43fd6426943dc'

def check(name, output, expected_success, tool_exit=0, install_tool=True, with_apk=True):
    with tempfile.TemporaryDirectory(prefix='release-gate-test-') as directory:
        root = Path(directory)
        tools = root / 'sdk/build-tools/35.0.0'
        tools.mkdir(parents=True)
        if install_tool:
            tool = tools / 'apksigner'
            tool.write_text("#!/usr/bin/env bash\ncat <<'OUTPUT'\n" + output + '\nOUTPUT\nexit ' + str(tool_exit) + '\n')
            tool.chmod(0o755)
        # A newer installed tool must not silently change the parser contract.
        newer = root / 'sdk/build-tools/37.0.0/apksigner'
        newer.parent.mkdir()
        newer.write_text('#!/usr/bin/env bash\necho newer-tool-was-used >&2\nexit 99\n')
        newer.chmod(0o755)
        apks = root / 'app/build/outputs/apk/meta/release'
        apks.mkdir(parents=True)
        if with_apk:
            (apks / 'fixture.apk').write_bytes(b'test placeholder; fake verifier only')
        proc = subprocess.run(['bash', '-c', script], cwd=root,
                              env=os.environ | {'ANDROID_HOME': str(root / 'sdk')}, capture_output=True, text=True)
        assert (proc.returncode == 0) == expected_success, (name, proc.returncode, proc.stdout, proc.stderr)
        assert 'newer-tool-was-used' not in proc.stderr, name
        print('PASS:', name)

base = 'Number of signers: 1\nSigner #1 certificate SHA-256 digest: '
check('pinned tool despite newer installation', base + CERT, True)
check('uppercase digest normalization', base + CERT.upper(), True)
check('wrong certificate fails', base + '0' * 64, False)
check('missing certificate fails', 'Number of signers: 1', False)
check('multiple signers fail', (base + CERT).replace('signers: 1', 'signers: 2'), False)
check('failed verifier cannot pass from printed text', base + CERT, False, tool_exit=1)
check('absent pinned tool fails closed', base + CERT, False, install_tool=False)
check('empty release directory fails', base + CERT, False, with_apk=False)
print('PASS: 8 release-gate orchestration regressions (no cryptographic claim).')
