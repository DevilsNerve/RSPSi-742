"""Check the 3D transport boundary without requiring a game cache or JVM reader.

The maintained compiler is substituted here; real decoder acceptance still
requires the Linux cache integration checks described in the handoff.
"""
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from xyren_bridge import Workspace, serve


class PreviewTransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rspsi-preview-transport-')
        self.root = Path(self.temp.name)
        self.source_path = self.root/'source'; self.source_path.mkdir()
        self.source_pin = self.source_path/'reader-input'; self.source_pin.write_bytes(b'pinned read-only input')
        self.original_bytes = self.source_pin.read_bytes()
        self.region = {'tiles': [[None, 1, 0, 0, 0, 'near', 'near', 240] for _ in range(16384)],
                       'objects': [{'key':'near:12850:0','source':'near','id':42,
                                    'x':3222,'y':3222,'plane':0,'type':10,'rotation':0}]}
        self.bindings = [[70001,3222,3222,0,10,0,'near:12850:0']]
        self.revision = 'revision-1'
        self.fail_reader = False
        self.change_revision = False
        self.jobs = []
        self.w = Workspace.__new__(Workspace)
        self.w.tools = self.root
        self.w.directory = self.root/'workspace'; self.w.directory.mkdir()
        self.w.project = 'private-project'
        self.w.projects = SimpleNamespace()
        self.w.exports = {}
        self.w.progress = lambda message: None
        self.w.summary = lambda: {'revision': self.revision}
        self.w.view = lambda source, regions: {'regions': {'12850': self.region}}
        source = SimpleNamespace(path=self.source_path, key='xyren', identity='source-pin', regions={12850}, exclusions={})
        source.region = lambda region: self.region
        self.w.sources = SimpleNamespace(get=lambda key: source, assert_current=lambda: None)
        self.w.preview_surfaces = lambda scene, source, regions, plane: {'12850': [-1]*4096}
        owner = self
        class Scenes:
            def __init__(self, projects, progress=None): pass
            def create(self, project, request):
                owner.stage = owner.w.directory/'scene-1'; owner.stage.mkdir()
                return {'id':'scene-1'}
            def path(self, identifier): return owner.stage
            def manifest(self, identifier, check_generation=False): return {'source':'xyren','bindings':owner.bindings}
        modules = {}
        for name in ('scripts','scripts.xyren_maps','scripts.xyren_maps.editor_scene',
                     'scripts.xyren_maps.editor_publication','scripts.xyren_maps.converter_runtime'):
            modules[name] = ModuleType(name)
        modules['scripts.xyren_maps.editor_scene'].Scenes = Scenes
        modules['scripts.xyren_maps.editor_publication'].reader_classes = lambda *args: (
            'private-reader-classpath', {str(self.source_pin): hashlib.sha256(self.original_bytes).hexdigest()})
        modules['scripts.xyren_maps.converter_runtime'].reader_resources = lambda root: root
        modules['scripts.xyren_maps.converter_runtime'].build = lambda root: None
        self.module_patch = patch.dict(sys.modules, modules); self.module_patch.start()
        self.run_patch = patch('subprocess.run', self.run_reader); self.run_patch.start()

    def tearDown(self):
        self.run_patch.stop(); self.module_patch.stop()
        self.assertEqual(self.original_bytes, self.source_pin.read_bytes())
        self.temp.cleanup()

    def run_reader(self, args, **kwargs):
        self.assertIs(kwargs['stdout'], sys.stderr)
        self.assertIs(kwargs['stderr'], sys.stderr)
        if args[0] != 'java': return
        self.jobs.append(json.loads(Path(args[-2]).read_bytes()))
        if self.fail_reader: raise OSError('reader failed')
        if self.change_revision: self.revision = 'revision-2'
        Path(args[-1]).write_text(json.dumps({'schema':'rspsi-source-preview-1','meshes':[],
                                            'textures':{},'surfaces':{},'failures':[]}))

    def body(self):
        return {'source':'near','regions':[12850],'x':3222,'y':3222,'plane':0,'revision':'revision-1'}

    def test_foreign_id_uses_compiled_binding_and_download_is_verified(self):
        result = self.w.dispatch('preview_3d',self.body())
        self.assertEqual(70001,self.jobs[0]['objects'][0]['native_id'])
        archive = self.w.exports[result['token']]
        self.assertEqual(result['sha256'],hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(result['bytes'],archive.stat().st_size)
        with zipfile.ZipFile(archive) as z:
            self.assertEqual(['preview.json'],z.namelist())
            self.assertEqual('rspsi-source-preview-1',json.loads(z.read('preview.json'))['schema'])
        self.w.dispatch('release_export',{'token':result['token']})
        self.assertFalse(archive.parent.exists())

    def test_foreign_id_without_binding_is_rejected(self):
        self.bindings = []
        with self.assertRaisesRegex(ValueError,'no compiled native binding'):
            self.w.preview_3d(self.body())
        self.assertFalse(self.stage.exists());self.assertFalse(self.w.exports)

    def test_revision_change_cannot_publish_stale_preview(self):
        self.change_revision = True
        with self.assertRaisesRegex(ValueError,'changed while building'):
            self.w.preview_3d(self.body())
        self.assertFalse(self.stage.exists());self.assertFalse(self.w.exports)

    def test_reader_failure_cleans_only_private_scene(self):
        self.fail_reader = True
        with self.assertRaisesRegex(OSError,'reader failed'):
            self.w.preview_3d(self.body())
        self.assertFalse(self.stage.exists());self.assertFalse(self.w.exports)

    def test_protocol_returns_json_and_no_decoder_stdout(self):
        outgoing = io.StringIO()
        serve(self.w,io.StringIO(json.dumps({'id':7,'action':'preview_3d','body':self.body()})+'\n'),outgoing)
        messages = [json.loads(line) for line in outgoing.getvalue().splitlines()]
        self.assertTrue(all(message.get('id') == 7 for message in messages))
        response = messages[-1]
        self.assertTrue(response['ok']);self.assertEqual(7,response['id'])
        self.assertEqual('near',response['result']['source'])


if __name__ == '__main__': unittest.main()
