#!/usr/bin/env python3
"""Compile an explicitly bounded OSM route corridor. Never downloads at game time."""
import argparse, collections, hashlib, json, math, pathlib, xml.etree.ElementTree as ET

def tags(el): return {x.get('k'): x.get('v') for x in el.findall('tag')}
def access(t):
    return next((t[k] for k in ('motorcar','motor_vehicle','vehicle','access') if k in t), 'yes') not in ('no','private')
def lanes(t):
    rev=t.get('oneway')=='-1'; one=rev or t.get('oneway') in ('yes','1','true')
    total=int(t.get('lanes','1' if one else '2'))
    if not 1<=total<=8: raise ValueError('unsupported lane total')
    f=int(t.get('lanes:forward',str(total if one else (total+1)//2)))
    b=int(t.get('lanes:backward',str(0 if one else total-f)))
    if one: f,b=total,0
    if f+b!=total or min(f,b)<0: raise ValueError('inconsistent directional lane count')
    if not one and total==1: raise ValueError('single shared bidirectional lane needs separate profile')
    return f,b,rev

def compile_file(source,dest,report):
    raw=source.read_bytes();root=ET.fromstring(raw)
    nodes={n.get('id'):(float(n.get('lon')),float(n.get('lat'))) for n in root.findall('node')}
    selected=[]; exclusions=[]
    # Explicit P1 pilot boundary: contiguous old R20 north of the narrow mountain section.
    # This is NOT a national classifier; southern bypass/tunnels are outside the pilot.
    for w in root.findall('way'):
        t=tags(w); wid=w.get('id'); ids=[n.get('ref') for n in w.findall('nd')]
        if not t.get('highway'): continue
        reason=None
        if t.get('nat_name')!='国道20号' or t.get('ref')!='20': reason='not verified pilot route identity'
        elif t.get('highway')!='trunk' or t.get('name')!='甲州街道': reason='outside old R20 pilot corridor'
        elif not access(t):reason='motor vehicle access excluded'
        elif any(k in t for k in ('construction','proposed','disused','abandoned')):reason='not active road'
        elif min(nodes[n][1] for n in ids)<35.63939:reason='outside northern pilot boundary'
        elif t.get('tunnel') not in (None,'no'):reason='tunnel outside first pilot'
        if reason:exclusions.append({'way':wid,'reason':reason});continue
        f,b,rev=lanes(t)
        if rev:ids.reverse()
        selected.append({'id':wid,'nodes':ids,'tags':t,'forward':f,'reverse':b,'inferred': 'lanes' not in t})
    # Join by exact source node identity only. No distance merging or 2-D intersection inference.
    degree=collections.Counter(n for w in selected for n in (w['nodes'][0],w['nodes'][-1]))
    endpoints=sorted(n for n,d in degree.items() if d==1)
    if len(endpoints)!=2 or any(d>2 for d in degree.values()):raise ValueError('pilot must be one connected non-branching corridor')
    current=endpoints[0];remaining=list(selected);ordered=[]
    while remaining:
        matches=[w for w in remaining if current in (w['nodes'][0],w['nodes'][-1])]
        if len(matches)!=1:raise ValueError('disconnected pilot')
        w=matches[0];remaining.remove(w)
        if w['nodes'][0]!=current:
            if w['reverse']==0:raise ValueError('cannot reverse one-way chain')
            w=dict(w,nodes=list(reversed(w['nodes'])),forward=w['reverse'],reverse=w['forward'])
        ordered.append(w);current=w['nodes'][-1]
    lines=['# PJSR_ROADS_V1; EPSG:4326; projection=com.sora.pjr.core.Geo; scale=0.5', '# node_id,lon,lat,way_id,forward,reverse,bridge; properties describe outgoing edge']
    for w in ordered:
        for n in w['nodes'][:-1]:
            lon,lat=nodes[n];lines.append(f"{n}\t{lon:.7f}\t{lat:.7f}\t{w['id']}\t{w['forward']}\t{w['reverse']}\t{1 if w['tags'].get('bridge') not in (None,'no') else 0}")
    w=ordered[-1];n=w['nodes'][-1];lon,lat=nodes[n]
    lines.append(f"{n}\t{lon:.7f}\t{lat:.7f}\t{w['id']}\t{w['forward']}\t{w['reverse']}\t0")
    dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text('\n'.join(lines)+'\n')
    manifest={'schema':1,'snapshot':'takao-r20-20261020-v1','download_date':'2026-10-20','source':'https://api.openstreetmap.org/api/0.6/map?bbox=139.267,35.630,139.292,35.650','license':'ODbL-1.0','attribution':'© OpenStreetMap contributors','raw_sha256':hashlib.sha256(raw).hexdigest(),'compiled_sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'compiler_version':1,'coordinate':'PJR Geo 0.5, EPSG:4326 input','ways':ordered,'excluded':exclusions,'note':'Regional corridor pilot only. No national coverage claim. Node IDs define connectivity; layer is not elevation.'}
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'ways':len(ordered),'points':len(lines)-2,'sha256':manifest['compiled_sha256']}))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('source',type=pathlib.Path);p.add_argument('dest',type=pathlib.Path);p.add_argument('report',type=pathlib.Path);a=p.parse_args();compile_file(a.source,a.dest,a.report)
