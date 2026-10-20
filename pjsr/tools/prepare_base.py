#!/usr/bin/env python3
"""Restore original PJR binary assets, offline, from the baseline stored in this repository."""
import hashlib,io,pathlib,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
parts=sorted((ROOT.parent/'downloads/PJR-0.1.24').glob('ProjectJapanRefined-0.1.24-alpha.jar.part*'))
if len(parts)!=7:raise SystemExit('Missing seven PJR 0.1.24 baseline JAR parts in ../downloads/PJR-0.1.24')
raw=b''.join(p.read_bytes() for p in parts)
if hashlib.sha256(raw).hexdigest()!='71a64c622c6e08ef90447dec8a6e73e0fa2290293c6033f1e4bb1fd4f39b0428':raise SystemExit('PJR baseline checksum mismatch')
count=0
with zipfile.ZipFile(io.BytesIO(raw)) as z:
 for name in z.namelist():
  if name.startswith('assets/pjr/terrain/') or name in ('assets/pjr/world/geography.pjrg.gz','assets/pjr/world/hydraulic-startup.bin.gz'):
   target=ROOT/'src/main/resources'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name));count+=1
print(f'Restored {count} exact PJR baseline resources')
