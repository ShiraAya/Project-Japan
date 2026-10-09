"""Download and verify the unchanged PJR JAR; Python 3, standard library only."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import urllib.request

BASE = 'https://raw.githubusercontent.com/ShiraAya/Project-Japan/main/downloads/PJR-0.1.24'
NAME = 'ProjectJapanRefined-0.1.24-alpha.jar'
EXPECTED = '71a64c622c6e08ef90447dec8a6e73e0fa2290293c6033f1e4bb1fd4f39b0428'
SIZE = 49_777_004

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def main():
    destination = Path(__file__).resolve().parent / NAME
    if destination.exists():
        if destination.is_file() and digest(destination) == EXPECTED:
            print('Complete JAR already exists and is verified:', destination)
            return
        raise RuntimeError('A different file exists at the destination; move it first.')
    with urllib.request.urlopen(BASE + '/manifest.json', timeout=60) as response:
        manifest = json.load(response)
    if (manifest['file'], manifest['size'], manifest['sha256']) != (NAME, SIZE, EXPECTED):
        raise RuntimeError('Unexpected manifest')
    with tempfile.TemporaryDirectory(prefix='pjr-download-', dir=destination.parent) as tmp:
        staged = Path(tmp) / NAME
        with staged.open('wb') as output:
            for i, part in enumerate(manifest['parts'], 1):
                if not re.fullmatch(r'ProjectJapanRefined-0\.1\.24-alpha\.jar\.part[0-9]{2}', part['name']):
                    raise RuntimeError('Invalid part name')
                print(f'Downloading part {i}/{len(manifest["parts"])}...', flush=True)
                h = hashlib.sha256()
                count = 0
                with urllib.request.urlopen(BASE + '/' + part['name'], timeout=60) as response:
                    for chunk in iter(lambda: response.read(1024 * 1024), b''):
                        h.update(chunk)
                        count += len(chunk)
                        output.write(chunk)
                if count != part['size'] or h.hexdigest() != part['sha256']:
                    raise RuntimeError('Part checksum mismatch')
        if staged.stat().st_size != SIZE or digest(staged) != EXPECTED:
            raise RuntimeError('Final JAR checksum mismatch')
        # Exclusive creation prevents replacing a file that appeared meanwhile.
        with destination.open('xb') as output, staged.open('rb') as source:
            shutil.copyfileobj(source, output)
    print('Verified:', destination)

if __name__ == '__main__':
    main()
