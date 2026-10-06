import copy
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

from xyren_bridge import Workspace, extract_cache, serve

TOOLS = Path(os.environ.get('XYREN_TOOLS_HOME', '/var/rsps'))
sys.path.insert(0, str(TOOLS))
from scripts.xyren_maps.test_editor import cache_fixture


class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rspsi-map-test-')
        self.root = Path(self.temp.name)
        self.native = cache_fixture(self.root/'xyren', wide=True)
        self.reference = cache_fixture(self.root/'emps', color=0x992233, name='Emps tree')
        self.specs = {'xyren': {'kind': 'native', 'path': str(self.native), 'label': 'Xyren'},
                      'reference': {'kind': 'native', 'path': str(self.reference), 'label': 'Emps'}}
        self.before = {p: p.read_bytes() for root in (self.native,self.reference)
                       for p in root.rglob('*') if p.is_file()}
        self.w = Workspace(TOOLS, self.root/'workspace', self.specs)
        self.project = self.w.dispatch('create', {'name': 'Copy test'})

    def tearDown(self):
        self.w.close()
        for path, raw in self.before.items():
            self.assertEqual(path.read_bytes(), raw, str(path))
        self.temp.cleanup()

    def edit(self, operation):
        self.project = self.w.dispatch('mutate', {'revision': self.project['revision'], 'operation': operation})
        return self.project

    def copy(self, source='reference', rect=None, planes=None, layers=None):
        return self.edit({'kind': 'copy', 'source': source,
            'rect': rect or {'x0':3260,'y0':3260,'x1':3262,'y1':3263},
            'planes': planes or [0,1,2,3],
            **({'layers':layers} if layers is not None else {})})

    def test_namespaced_ids_and_evaluated_heights_survive_paste_save_reload(self):
        incoming = self.w.sources.get('reference').region(12850)
        height = incoming['tiles'][60*64+60][7]
        self.copy()
        self.edit({'kind':'paste','x':3200,'y':3200,'plane':0})
        view = self.w.dispatch('view',{'source':'draft','regions':[12850]})
        tile = view['regions']['12850']['tiles'][0]
        self.assertEqual(tile[7], height)
        self.assertEqual(tile[5], 'reference')
        rows = [o for o in view['regions']['12850']['objects'] if o['x']==3200 and o['y']==3200]
        self.assertEqual(rows[0]['source'],'reference')
        self.assertEqual(rows[0]['name'],'Emps tree')
        exported = self.w.dispatch('save',{})
        imported = self.w.dispatch('import',{'document':exported['document']})
        self.assertNotEqual(imported['id'],self.project['id'])
        actual = self.w.dispatch('view',{'source':'draft','regions':[12850]})['regions']['12850']
        self.assertEqual(actual['tiles'],view['regions']['12850']['tiles'])
        self.assertEqual(actual['objects'],view['regions']['12850']['objects'])

    def test_cross_square_rotation_reflection_and_all_planes(self):
        self.copy(rect={'x0':3260,'y0':3260,'x1':3267,'y1':3267})
        self.edit({'kind':'paste','x':3260,'y':3260,'plane':0,'turns':1,'mirror_x':True})
        view = self.w.dispatch('view',{'source':'draft','regions':[12850,13106,12851,13107]})
        self.assertEqual(len(view['loaded_regions']),4)
        copied = [o for r in view['regions'].values() for o in r['objects'] if o['source']=='reference']
        self.assertEqual(len(copied),1)
        self.assertEqual((copied[0]['x'],copied[0]['y'],copied[0]['rotation']), (3260,3260,3))
        self.assertTrue(copied[0]['mirrored'])

    def test_undo_redo_is_persistent_and_revisions_reject_stale_edits(self):
        previous = self.project['revision']
        self.copy()
        self.edit({'kind':'paste','x':3210,'y':3210,'plane':0})
        before = self.w.dispatch('view',{'source':'draft','regions':[12850]})['regions']['12850']['tiles']
        self.edit({'kind':'undo'})
        after = self.w.dispatch('view',{'source':'draft','regions':[12850]})['regions']['12850']['tiles']
        self.assertNotEqual(before,after)
        self.edit({'kind':'redo'})
        self.assertEqual(before,self.w.dispatch('view',{'source':'draft','regions':[12850]})['regions']['12850']['tiles'])
        with self.assertRaisesRegex(ValueError,'changed'):
            self.w.dispatch('mutate',{'revision':previous,'operation':{'kind':'undo'}})
        project = self.w.project
        self.w.close()
        self.w = Workspace(TOOLS,self.root/'workspace',self.specs)
        self.w.dispatch('open',{'project':project})
        self.assertEqual(before,self.w.dispatch('view',{'source':'draft','regions':[12850]})['regions']['12850']['tiles'])

    def test_browsing_keeps_project_revision_and_history_exact(self):
        original = self.w.projects.path(self.w.project).read_bytes()
        view = self.w.dispatch('view',{'source':'draft','regions':[12850]})
        self.assertEqual(self.w.projects.path(self.w.project).read_bytes(),original)
        self.assertEqual(view['regions']['12850']['objects'][0]['name'],'Tree')
        self.assertEqual(len(view['shape_masks']),96)
        self.assertTrue(all(len(mask)==16 for mask in view['shape_masks']))

    def test_object_only_paste_preserves_terrain_and_flags(self):
        before = self.w.sources.get('xyren').region(12850)['tiles']
        self.copy(layers={'objects':True})
        self.edit({'kind':'paste','x':3200,'y':3200,'plane':0})
        view = self.w.dispatch('view',{'source':'draft','regions':[12850]})
        self.assertEqual(view['regions']['12850']['tiles'],before)
        self.assertTrue(any(o['source']=='reference' for o in view['regions']['12850']['objects']))

    def test_invalid_paste_leaves_project_exact_and_clipboard_reusable(self):
        self.copy()
        original = self.w.projects.path(self.w.project).read_bytes()
        with self.assertRaisesRegex(ValueError,'planes'):
            self.edit({'kind':'paste','x':3200,'y':3200,'plane':1})
        self.assertEqual(self.w.projects.path(self.w.project).read_bytes(),original)
        self.edit({'kind':'paste','x':3200,'y':3200,'plane':0})

    def test_source_floors_and_object_edits_keep_the_selected_namespace(self):
        self.edit({'kind':'fill','rect':{'x0':3200,'y0':3200,'x1':3201,'y1':3201},'planes':[0],
                   'values':{'underlay':{'source':'reference','id':1},'overlay':{'source':'reference','id':1},
                             'shape':53,'flags':1,'height':-80}})
        tile = self.w.view('draft',[12850])['regions']['12850']['tiles'][0]
        self.assertEqual((tile[1],tile[2],tile[3],tile[4],tile[5],tile[6],tile[7]),
                         (1,1,53,1,'reference','reference',-80))
        self.edit({'kind':'object_place','object':{'source':'reference','id':1,'x':3200,'y':3200,
                                                  'plane':0,'type':10,'rotation':1}})
        row = next(o for o in self.w.view('draft',[12850])['regions']['12850']['objects'] if o['x']==3200)
        key = row['key']
        self.edit({'kind':'object_duplicate','key':key,'changes':{'x':3204,'y':3204,'rotation':2}})
        self.edit({'kind':'object_update','key':key,'changes':{'x':3206,'y':3206,'mirrored':True}})
        rows = self.w.view('draft',[12850])['regions']['12850']['objects']
        self.assertTrue(any(o['x']==3204 and o['source']=='reference' for o in rows))
        moved = next(o for o in rows if o['key']==key)
        self.assertEqual((moved['x'],moved['name'],moved['mirrored']),(3206,'Emps tree',True))
        self.edit({'kind':'object_delete','key':key})
        self.assertFalse(any(o['key']==key for o in self.w.view('draft',[12850])['regions']['12850']['objects']))
        self.edit({'kind':'undo'})
        self.assertTrue(any(o['key']==key for o in self.w.view('draft',[12850])['regions']['12850']['objects']))

    def test_emps_destination_rewrites_namespace_without_mutating_either_cache(self):
        self.w.dispatch('base',{'source':'reference'})
        self.project = self.w.dispatch('create',{'name':'Emps destination'})
        self.copy(source='xyren-world',planes=[0])
        self.edit({'kind':'paste','x':3200,'y':3200,'plane':0})
        view = self.w.dispatch('view',{'source':'draft','regions':[12850]})
        row = next(o for o in view['regions']['12850']['objects'] if o['x']==3200 and o['y']==3200)
        self.assertEqual((row['source'],row['name']),('xyren-world','Tree'))
        self.w.dispatch('base',{'source':'xyren-world'})
        self.assertEqual(len(self.w.dispatch('projects',{})['projects']),1)

    def test_source_zip_is_detected_and_persists_across_restart(self):
        archive = self.root/'donor.zip'
        with zipfile.ZipFile(archive,'w') as z:
            for p in self.reference.rglob('*'):
                if p.is_file(): z.write(p,'cache/'+str(p.relative_to(self.reference)))
        self.w.dispatch('add_source',{'path':str(archive),'key':'donor','label':'Imported Emps'})
        self.assertEqual(self.w.sources.get('donor').kind,'native')
        self.w.close()
        self.w = Workspace(TOOLS,self.root/'workspace')
        self.assertEqual(self.w.sources.get('donor').region(12850)['objects'][0]['id'],1)

    def test_workspace_cannot_be_inside_a_source_cache(self):
        with self.assertRaisesRegex(ValueError,'outside every source'):
            Workspace(TOOLS,self.native/'edits',self.specs)

    def test_source_change_fails_closed_before_an_edit(self):
        self.copy()
        path = self.reference/'4/1'
        old = path.read_bytes()
        try:
            path.write_bytes(old+b'changed')
            with self.assertRaisesRegex(ValueError,'changed'):
                self.edit({'kind':'paste','x':3200,'y':3200,'plane':0})
        finally: path.write_bytes(old)

    def test_changed_destination_can_be_reviewed_before_reopening_a_project(self):
        self.edit({'kind':'fill','rect':{'x0':3200,'y0':3200,'x1':3200,'y1':3200},
                   'planes':[0],'values':{'height':-80}})
        identifier = self.w.project
        try:
            cache_fixture(self.native,color=0x887733,wide=True)
            self.w.dispatch('base',{'source':'xyren-world'})
            with self.assertRaisesRegex(ValueError,'changed'):
                self.w.dispatch('open',{'project':identifier})
            preview = self.w.dispatch('rebase',{'project':identifier})
            self.assertTrue(preview['changed'])
            self.assertFalse(preview['applied'])
            resolutions = {row['id']:'draft' for row in preview['conflicts']}
            with self.assertRaisesRegex(ValueError,'changed'):
                self.w.dispatch('rebase',{'project':identifier,'revision':preview['revision'],
                    'apply':True,'target_identity':'different','resolutions':resolutions})
            result = self.w.dispatch('rebase',{'project':identifier,'revision':preview['revision'],
                    'apply':True,'target_identity':preview['target_identity'],'resolutions':resolutions})
            self.assertTrue(result['applied'])
            view = self.w.dispatch('open',{'project':identifier})
            self.assertTrue(view['has_previous'])
            self.assertEqual(self.w.view('draft',[12850])['regions']['12850']['tiles'][0][7],-80)
        finally:
            for path, raw in self.before.items():
                if path.is_relative_to(self.native):path.write_bytes(raw)

    def test_rpc_errors_are_isolated_and_the_next_request_succeeds(self):
        incoming = io.StringIO('bad json\n'+json.dumps({'id':2,'action':'summary'})+'\n'+json.dumps({'id':3,'action':'quit'})+'\n')
        output = io.StringIO()
        serve(self.w,incoming,output)
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertFalse(rows[0]['ok'])
        self.assertTrue(rows[1]['ok'])
        self.assertTrue(rows[2]['result']['closed'])


class ZipTest(unittest.TestCase):
    def test_zip_traversal_duplicate_and_symlink_are_rejected(self):
        for kind in ('traversal','duplicate','symlink'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp); archive=root/'bad.zip'
                with zipfile.ZipFile(archive,'w') as z:
                    if kind=='traversal': z.writestr('../outside','bad')
                    elif kind=='duplicate':
                        z.writestr('file','one')
                        with self.assertWarns(UserWarning): z.writestr('file','two')
                    else:
                        info=zipfile.ZipInfo('link');info.external_attr=(0o120777 << 16);z.writestr(info,'/outside')
                with self.assertRaises(ValueError): extract_cache(archive,root/'input')
                self.assertFalse((root/'input').exists())


if __name__ == '__main__': unittest.main()
