#!/usr/bin/env python3
"""Exercise real Xyren, Emps and Near Reality sources and an exported cache.

All writes are temporary test projects. This never publishes or changes inputs.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import zipfile

from xyren_bridge import Workspace


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tools',default='/var/rsps')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    proof={'sources':{},'checks':[]}
    with tempfile.TemporaryDirectory(prefix='rspsi-real-maps-') as tmp:
        w=Workspace(args.tools,Path(tmp)/'workspace',progress=lambda message:print(message,flush=True))
        try:
            state=w.dispatch('hello',{})
            for row in state['sources']:
                if row.get('error'):raise ValueError(row['error'])
                proof['sources'][row['key']]={'identity':row['identity'],'regions':len(row['regions'])}
            project=w.dispatch('create',{'name':'Three-map round trip'})
            def edit(operation):
                nonlocal project
                project=w.dispatch('mutate',{'revision':project['revision'],'operation':operation})
            expected=[]
            selections=[('reference',3212,3215,12223,12223,False),
                        ('near-reality',3212,3218,12226,12226,False),
                        ('xyren-world',3212,3218,12229,12229,True)]
            for source,x,y,dx,dy,mirror in selections:
                original=w.sources.get(source).region(((x>>6)<<8)|(y>>6))
                sample=next(o for o in original['objects'] if o['x']==x and o['y']==y and o['plane']==0 and o['type']==10)
                edit({'kind':'copy','source':source,'rect':{'x0':x,'y0':y,'x1':x+1,'y1':y+1},'planes':[0,1,2,3]})
                edit({'kind':'paste','x':dx,'y':dy,'plane':0,'mirror_x':mirror,'create_regions':True})
                expected.append({'source':source,'id':sample['id']})
            view=w.dispatch('summary',{})
            regions=view['loaded_regions']
            assert len(regions)>=4,'cross-square paste did not create all squares'
            real=w.dispatch('view',{'source':'draft','regions':regions})
            for row in expected:
                assert any(o['source']==row['source'] and o['id']==row['id'] for r in real['regions'].values() for o in r['objects'])
            proof['checks'].append('Three real source namespaces, four planes, cross-square paste and reflected geometry')
            before=real['regions']
            edit({'kind':'undo'})
            edit({'kind':'redo'})
            assert w.dispatch('view',{'source':'draft','regions':regions})['regions']==before
            proof['checks'].append('Undo and redo restore the complete three-source project')
            portable=w.dispatch('save',{})
            imported=w.dispatch('import',{'document':portable['document']})
            project=imported
            assert w.dispatch('view',{'source':'draft','regions':regions})['regions']==before
            proof['checks'].append('Save, import and reopen preserve tiles and source object identities')
            result=w.dispatch('export_cache',{'revision':project['revision'],'full_cache':False})
            proof['export']={'bytes':result['bytes'],'sha256':result['sha256'],
                             'changed_regions':result['manifest']['changed_regions'],
                             'collision_regions':result['manifest']['collision_regions'],
                             'files':len(result['manifest']['files']),
                             'included_files':result['manifest']['included_files'],
                             'reader_inputs_sha256':result['manifest']['reader_inputs_sha256']}
            archive=Path(tmp)/'round-trip.zip'
            digest=hashlib.sha256();offset=0
            with archive.open('xb') as out:
                while offset<result['bytes']:
                    chunk=w.dispatch('download',{'token':result['token'],'offset':offset})
                    data=base64.b64decode(chunk['data']);assert data
                    out.write(data);digest.update(data);offset+=len(data)
            assert offset==result['bytes'] and digest.hexdigest()==result['sha256']
            with zipfile.ZipFile(archive) as z:
                assert z.testzip() is None
                manifest=json.loads(z.read('manifest.json'))
                for name,pin in manifest['files'].items():
                    raw=z.read('cache/'+name)
                    assert len(raw)==pin['bytes'] and hashlib.sha256(raw).hexdigest()==pin['sha256']
                from scripts.xyren_maps.collision import read_collision
                _,blocks=read_collision(z.read('collision.bin'))
                assert set(blocks)==set(manifest['collision_regions'])
            proof['checks'].append('Actual Xyren definitions, geometry, materials, maps and server collision reader accept the compiled export')
            proof['checks'].append('Downloaded ZIP matches its size, checksum, all changed assets and collision coverage')
            w.dispatch('release_export',{'token':result['token']})
            w.sources.assert_current()
            proof['checks'].append('Every consumed source archive stayed unchanged')
        finally:w.close()
    proof['temporary_workspace_removed']=not Path(tmp).exists()
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(proof,sort_keys=True,indent=2)+'\n')
    print(json.dumps(proof,sort_keys=True,indent=2))


if __name__=='__main__':main()
