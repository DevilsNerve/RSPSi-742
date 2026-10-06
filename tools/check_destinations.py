#!/usr/bin/env python3
"""Verify all three source maps in each destination using actual cache readers."""
import argparse
import json
from pathlib import Path
import tempfile

from xyren_bridge import Workspace


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tools', default='/var/rsps')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    proof = {'destinations': [], 'transfers': [], 'original_sources_unchanged': False}
    with tempfile.TemporaryDirectory(prefix='rspsi-destinations-') as temporary:
        workspace = Workspace(args.tools, Path(temporary)/'workspace', progress=lambda message: print(message, flush=True))
        try:
            for destination in ('xyren-world', 'reference', 'near-reality'):
                print('Checking destination: '+destination, flush=True)
                state = workspace.dispatch('base', {'source':destination, 'regions':[12850]})
                project = workspace.dispatch('create', {'name':destination+' destination transfers'})
                expected = []
                for index, (source, x, y) in enumerate((('reference',3212,3215),
                                                       ('near-reality',3212,3218),
                                                       ('xyren-world',3212,3218))):
                    source_region = workspace.sources.get(source).region(((x>>6)<<8)|(y>>6))
                    sample = next(o for o in source_region['objects']
                                  if o['x']==x and o['y']==y and o['plane']==0 and o['type']==10)
                    target = 12223+index*3
                    for operation in ({'kind':'copy','source':source,
                                       'rect':{'x0':x,'y0':y,'x1':x+1,'y1':y+1},'planes':[0,1,2,3]},
                                      {'kind':'paste','x':target,'y':target,'plane':0,
                                       'turns':index, 'mirror_x':index==2,'create_regions':True}):
                        project = workspace.mutate(operation, project['revision'])
                    expected.append((source, sample['id']))
                    proof['transfers'].append({'source':source,'destination':destination,'object_id':sample['id']})
                regions = workspace.summary()['loaded_regions']
                view = workspace.view('draft', regions)
                for source, identifier in expected:
                    assert any(o['source']==source and o['id']==identifier
                               for r in view['regions'].values() for o in r['objects'])
                if destination=='near-reality':
                    original = workspace.view('draft', [12850])['regions']['12850']
                    assert len(original['objects'])==4723
                exported = workspace.cache_export({'revision':project['revision'],'full_cache':False})
                proof['destinations'].append({'source':destination,'base':state['base'],
                    'changed_regions':exported['manifest']['changed_regions'],
                    'reader_inputs_sha256':exported['manifest']['reader_inputs_sha256'],
                    'export_bytes':exported['bytes'],'export_sha256':exported['sha256']})
                workspace.dispatch('release_export', {'token':exported['token']})
                workspace.sources.assert_current()
            proof['original_sources_unchanged'] = True
        finally:
            workspace.close()
    proof['temporary_workspace_removed'] = not Path(temporary).exists()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(proof, sort_keys=True, indent=2)+'\n')
    print(json.dumps(proof, sort_keys=True, indent=2))


if __name__=='__main__':
    main()
