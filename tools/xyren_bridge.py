#!/usr/bin/env python3
"""Local/SSH JSON-lines adapter to the maintained Xyren map codecs.

Only private workspaces are writable. No publication, website, login, or game
process is started by this program. The map and asset writers have one owner:
scripts.xyren_maps in the configured Xyren tools checkout.
"""
from __future__ import annotations

import argparse
import base64
from contextlib import redirect_stdout
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import traceback
import uuid
import zipfile

MAX_MESSAGE = 64 * 1024 * 1024
MAX_ZIP = 8 * 1024 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def extract_cache(archive, directory):
    """Accept native full-cache ZIPs and JS5 ZIPs, never arbitrary extraction."""
    with zipfile.ZipFile(archive) as z:
        infos = z.infolist()
        require(len(infos) <= 600000, 'Cache ZIP contains too many entries')
        require(sum(row.file_size for row in infos) <= MAX_ZIP, 'Cache ZIP exceeds 8 GiB')
        names = set()
        for row in infos:
            p = Path(row.filename)
            require(row.filename and '\\' not in row.filename and not p.is_absolute()
                    and '..' not in p.parts and p.parts and p.parts[0] != '.'
                    and row.filename not in names, 'Unsafe or duplicate cache ZIP entry')
            require((row.external_attr >> 16) & 0o170000 != 0o120000, 'Cache ZIP contains a symbolic link')
            require(row.file_size <= 512 * 1024 * 1024, 'Cache ZIP entry exceeds capacity')
            names.add(row.filename)
        directory.mkdir(mode=0o700)
        for row in infos:
            target = directory / row.filename
            if row.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(row) as source, target.open('xb') as out:
                    shutil.copyfileobj(source, out)
    return find_cache(directory)


def find_cache(directory):
    directory = Path(directory).resolve(strict=True)
    candidates = [directory] + sorted(p for p in directory.glob('*') if p.is_dir())
    candidates += sorted(p for p in directory.glob('*/*') if p.is_dir())
    found = [(p, 'native') for p in candidates if (p/'0/2').is_file() and (p/'0/5').is_file()]
    found += [(p, 'js5') for p in candidates if (p/'main_file_cache.dat2').is_file()
              and all((p/f'main_file_cache.idx{i}').is_file() for i in (2, 5, 255))]
    require(len(found) == 1, 'Select a native 0/2 + 0/5 cache folder or a JS5 dat2 cache folder')
    return found[0]


class Workspace:
    def __init__(self, tools, directory, specifications=None, progress=None):
        self.tools = Path(tools).resolve(strict=True)
        require((self.tools/'scripts/xyren_maps/editor.py').is_file(), 'Xyren map tools checkout is unavailable')
        sys.path.insert(0, str(self.tools))
        from scripts.xyren_maps.editor_cli import DEFAULT_SOURCES
        from scripts.xyren_maps.editor import Projects
        from scripts.xyren_maps.editor_sources import Sources
        self.Projects, self.Sources = Projects, Sources
        self.directory = Path(directory).expanduser().resolve()
        require(not self.directory.is_relative_to(self.tools), 'Choose a workspace outside the source checkout')
        self.progress = progress or (lambda message: None)
        self.exports, self.palettes = {}, {}
        stored = self.directory/'sources.json'
        if specifications is None:
            specifications = json.loads(stored.read_bytes()) if stored.is_file() else DEFAULT_SOURCES
        self.specs = copy.deepcopy(specifications)
        if 'xyren' in self.specs:
            self.specs['xyren-world'] = self.specs.pop('xyren')
        self.require_workspace_separation()
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.base = None
        self.sources = None
        self.projects = None
        self.project = None
        self.change_base('xyren-world')

    def require_workspace_separation(self):
        for spec in self.specs.values():
            path = Path(spec['path']).expanduser().resolve()
            require(not self.directory.is_relative_to(path), 'Workspace must be outside every source cache')

    def close(self):
        if self.sources:
            self.sources.close()
        for archive in list(self.exports.values()):
            if archive.parent.is_dir():
                shutil.rmtree(archive.parent)
        self.exports.clear()

    def change_base(self, key):
        require(key in self.specs and self.specs[key]['kind'] == 'native', 'Destination base must be a native cache')
        configured = {**copy.deepcopy(self.specs), 'xyren': copy.deepcopy(self.specs[key])}
        candidate = self.Sources(configured)
        try:
            candidate.get('xyren')
            # Donors are opened lazily, so an unavailable optional source does
            # not prevent editing a valid destination.
            projects = self.Projects(self.directory/'projects'/key, 1, candidate)
        except Exception:
            candidate.close()
            raise
        self.close()
        self.base, self.sources, self.projects, self.project = key, candidate, projects, None

    def source_list(self):
        rows = []
        for key in self.specs:
            try:
                source = self.sources.get(key)
                rows.append({**source.manifest(), 'base': True})
            except (OSError, ValueError) as error:
                rows.append({'key': key, 'label': self.specs[key].get('label', key),
                             'kind': self.specs[key]['kind'], 'error': str(error), 'base': False})
        return rows

    def info(self):
        return {'sources': self.source_list(), 'base': self.base,
                'projects': self.projects.list()['projects'], 'project': self.project,
                'workspace': str(self.directory), 'format': 'Xyren native',
                'tools_revision': self.tools_revision(), 'capabilities': {'preview_3d': 1}}

    def preview_3d(self, body):
        """Bake visible scenery with the maintained native client, in a private scene.

        This is a rendering transport only. Authoring, namespaces, model conversion
        and collision continue to belong to the existing maintained map tools.
        """
        import subprocess
        from scripts.xyren_maps.editor_scene import Scenes
        from scripts.xyren_maps.editor_publication import reader_classes
        from scripts.xyren_maps.converter_runtime import reader_resources, build
        require(self.project is not None, 'Create or open a destination project first')
        regions = body.get('regions')
        require(isinstance(regions, list) and 0 < len(regions) <= 9
                and all(type(r) is int and 0 <= r <= 65535 for r in regions), 'View one to nine regions')
        source_key = body['source']
        viewed = self.view(source_key, regions)
        revision = self.summary()['revision']
        require(revision == body.get('revision'), 'Project changed; refresh the 3D view')
        x, y, plane = body.get('x'), body.get('y'), body.get('plane')
        require(type(x) is int and type(y) is int and 0 <= x <= 16383 and 0 <= y <= 16383
                and type(plane) is int and 0 <= plane < 4, 'Invalid 3D camera position')
        require((x >> 6 << 8 | y >> 6) in regions, 'Camera must be inside the visible regions')
        previous_runtime = os.environ.get('XYREN_MAP_CONVERTER_RUNTIME')
        scratch = self.directory/'preview-runtime'/uuid.uuid4().hex
        scene = None
        try:
            try:
                reader_resources(self.tools/'XyrenClient2')
            except ValueError as error:
                if not str(error).startswith('client resource changed; rebuild the converter runtime:'):
                    raise
                self.progress('Refreshing the private 3D codec runtime')
                scratch.parent.mkdir(exist_ok=True, mode=0o700)
                build(scratch)
                os.environ['XYREN_MAP_CONVERTER_RUNTIME'] = str(scratch)
            compiler = Scenes(self.projects, progress=self.progress)
            request = {'source': source_key, 'revision': revision, 'x': x, 'y': y, 'plane': plane}
            if source_key != 'draft': request['identity'] = self.sources.get(source_key).identity
            info = compiler.create(self.project, request)
            scene = compiler.path(info['id'])
            manifest = compiler.manifest(info['id'])
            source = self.sources.get(manifest['source'])
            # Foreign/draft rows bind to freshly allocated compiled native IDs.
            bindings = {row[6]: row for row in manifest.get('bindings', [])}
            rows = []
            for region in viewed['regions'].values():
                for obj in region['objects']:
                    if obj['plane'] != plane: continue
                    binding = bindings.get(obj['key'])
                    require(binding is not None or obj['source'] in ('xyren', source.key),
                            'Visible foreign object has no compiled native binding')
                    rows.append({**obj, 'native_id': binding[0] if binding else obj['id']})
            window = {rx << 8 | ry
                      for rx in range(max(0, (x >> 6)-1), min(255, (x >> 6)+1)+1)
                      for ry in range(max(0, (y >> 6)-1), min(255, (y >> 6)+1)+1)}
            require(set(regions) <= window, 'The 3D view must fit inside the camera region and its neighbors')
            original = self.sources.get('xyren' if source_key == 'draft' else source_key)
            height_ids = sorted((window & set(original.regions) - set(original.exclusions)) | set(regions))
            if source_key == 'draft':
                height_regions = self.projects.view(self.project, height_ids)['regions']
            else:
                height_regions = {str(r): original.region(r) for r in height_ids}
            job = {'cache': str(source.path), 'overlay': str(scene/'payload'), 'objects': rows,
                   'heights': {r: [cell[7] for cell in region['tiles']] for r, region in height_regions.items()},
                   'surfaces': self.preview_surfaces(scene, source, regions, plane), 'plane': plane}
            helper = Path(__file__).with_name('RSPSiPreview.java')
            require(helper.is_file(), 'Install RSPSiPreview.java beside the map bridge')
            self.progress('Building the matching client mesh reader')
            cp, pins = reader_classes(scene/'rspsi-reader', self.tools/'XyrenClient2')
            classes = scene/'rspsi-preview-classes'; classes.mkdir(mode=0o700)
            subprocess.run(['javac', '-cp', cp, '-d', str(classes), str(helper)], check=True,
                           stdout=sys.stderr, stderr=sys.stderr)
            (scene/'preview-job.json').write_bytes(json_bytes(job))
            self.progress('Decoding visible models and texture pixels')
            subprocess.run(['java', '-Xmx2g', '-cp', str(classes)+os.pathsep+cp,
                            'RSPSiPreview', str(scene/'preview-job.json'), str(scene/'preview.json')], check=True,
                           stdout=sys.stderr, stderr=sys.stderr)
            require(self.summary()['revision'] == revision, 'Project changed while building the 3D view')
            compiler.manifest(info['id'], check_generation=True)
            self.sources.assert_current()
            require(all(Path(p).is_file() and hashlib.sha256(Path(p).read_bytes()).hexdigest() == pin
                        for p, pin in pins.items()), 'Client reader changed during preview')
            preview = scene/'preview.json'
            require(preview.stat().st_size <= 256*1024*1024, 'Visible 3D geometry exceeds capacity')
            archive = scene/'rspsi-preview.zip'
            with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as z:
                z.write(preview, 'preview.json')
            digest = hashlib.sha256()
            with archive.open('rb') as f:
                for block in iter(lambda: f.read(1024*1024), b''): digest.update(block)
            self.exports[info['id']] = archive
            return {'token': info['id'], 'bytes': archive.stat().st_size,
                    'sha256': digest.hexdigest(), 'revision': revision, 'source': source_key}
        except Exception:
            if scene is not None and scene.is_dir(): shutil.rmtree(scene)
            raise
        finally:
            if previous_runtime is None: os.environ.pop('XYREN_MAP_CONVERTER_RUNTIME', None)
            else: os.environ['XYREN_MAP_CONVERTER_RUNTIME'] = previous_runtime
            if scratch.is_dir(): shutil.rmtree(scratch)

    def preview_surfaces(self, scene, source, regions, plane):
        """Use compiled floor IDs, including allocated foreign materials."""
        from scripts.xyren_maps.cache import read_named, named_get
        from scripts.xyren_maps.formats import read_floor_records, read_terrain
        from scripts.xyren_map_underlay import inflate
        def asset(relative):
            path = scene/'payload'/relative
            return path if path.is_file() else source.path/relative
        floors = read_floor_records(named_get(read_named(asset('0/2').read_bytes()), 'flo2.dat'), True)[0]
        index = self._index(asset('0/5'))
        result = {}
        for region in regions:
            require(region in index, 'Compiled preview region is unavailable')
            tiles = read_terrain(inflate(asset('4/'+str(index[region][0]+1)).read_bytes()), revision=235)
            materials = []
            for tile in tiles[plane*4096:(plane+1)*4096]:
                if not tile.overlay:
                    materials.append(-1)
                else:
                    require(tile.overlay <= len(floors), 'Compiled preview overlay is unavailable')
                    definition = floors[tile.overlay-1]
                    texture = definition.get(3, definition.get(2, -1))
                    materials.append(-1 if texture == 65535 else texture)
            result[str(region)] = materials
        return result

    def tools_revision(self):
        import subprocess
        result = subprocess.run(['git', '-C', str(self.tools), 'rev-parse', 'HEAD'],
                                capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None

    def native_destination(self, key, regions):
        """Open a JS5 map area as an independently editable native destination.

        All conversion and verification still goes through the maintained
        compiler. The full native asset base plus the selected JS5 layout is
        materialized once; later edits need no JS5-to-native guesses.
        """
        require(key in self.specs and self.specs[key]['kind'] == 'js5', 'Choose a JS5 source')
        require(isinstance(regions, list) and 0 < len(regions) <= 9
                and all(type(r) is int and 0 <= r <= 65535 for r in regions), 'Choose one to nine JS5 map regions')
        source = self.sources.get(key)
        require(all(r in source.regions and r not in source.exclusions for r in regions), 'Selected JS5 area is unavailable')
        identity = hashlib.sha256(json_bytes([key, source.identity, sorted(set(regions)),
                                               self.sources.get('xyren-world').identity])).hexdigest()
        normalized_key = 'native-' + identity[:20]
        if normalized_key not in self.specs:
            self.progress('Converting the selected map area to a native destination')
            owner = self.directory/'normalized-sources'
            owner.mkdir(exist_ok=True, mode=0o700)
            output = owner/identity
            require(not output.exists(), 'An unfinished normalization exists: '+str(output))
            with tempfile.TemporaryDirectory(prefix='.normalize-', dir=self.directory) as temporary:
                child = Workspace(self.tools, Path(temporary)/'workspace', self.specs, self.progress)
                try:
                    summary = child.dispatch('create', {'name': 'Native destination'})
                    for region in sorted(set(regions)):
                        x, y = (region >> 8)*64, (region & 255)*64
                        for operation in ({'kind':'copy','source':key,'rect':{'x0':x,'y0':y,'x1':x+63,'y1':y+63},'planes':[0,1,2,3]},
                                          {'kind':'paste','x':x,'y':y,'plane':0,'create_regions':True}):
                            summary = child.mutate(operation, summary['revision'])
                    result = child.cache_export({'revision':summary['revision'],'full_cache':True})
                    archive = child.exports[result['token']]
                    self.progress('Materializing the verified destination cache')
                    cache, kind = extract_cache(archive, output)
                    require(kind == 'native', 'Normalization returned a non-native cache')
                    from scripts.xyren_maps.editor import atomic_private
                    atomic_private(output/'normalization.json', json_bytes({'source':key,'identity':source.identity,
                        'regions':sorted(set(regions)),'export_sha256':result['sha256'],
                        'reader_inputs_sha256':result['manifest']['reader_inputs_sha256']}))
                    self.specs[normalized_key] = {'kind':'native','path':str(cache),
                        'label':source.label+' area '+','.join(map(str, sorted(set(regions))))}
                    atomic_private(self.directory/'sources.json', json_bytes(self.specs))
                except Exception:
                    if output.exists(): shutil.rmtree(output)
                    self.specs.pop(normalized_key, None)
                    raise
                finally:
                    child.close()
            self.sources.assert_current()
        self.change_base(normalized_key)
        return self.info()

    def summary(self, project=None):
        project = project or self.project
        require(project is not None, 'Create or open a destination project first')
        return self.projects.view(project, [])

    def view(self, source, regions):
        from scripts.xyren_maps.editor import shape_mask
        require(isinstance(regions, list) and 0 < len(regions) <= 9, 'View one to nine regions')
        if source == 'draft':
            require(self.project is not None, 'Create or open a destination project first')
            result = self.projects.view(self.project, regions)
        else:
            s = self.sources.get(source)
            result = {'regions': {str(r): s.region(r) for r in regions}, 'source': source, 'identity': s.identity}
        required_sources = set()
        for region in result['regions'].values():
            for cell in region['tiles']:
                required_sources.update(cell[5:7])
            for obj in region['objects']:
                required_sources.add(obj['source'])
                definition = self.sources.get(obj['source']).definition(obj['id'])
                obj['name'] = definition.get(2, 'Unnamed')
                obj['size_x'], obj['size_y'] = definition.get(14, 1), definition.get(15, 1)
                obj['actions'] = [definition[o] for o in range(30, 35) if definition.get(o)]
        result['palettes'] = {}
        result['shape_masks'] = [list(shape_mask(shape)) for shape in range(96)]
        for key in sorted(required_sources):
            if key not in self.palettes:
                self.palettes[key] = self.sources.get(key).palette()
            result['palettes'][key] = self.palettes[key]
        self.sources.assert_current()
        return result

    def mutate(self, operation, revision):
        require(self.project is not None, 'Create or open a destination project first')
        return self.projects.mutate(self.project, revision, operation)

    def add_source(self, body):
        key = body.get('key') or ('source-' + uuid.uuid4().hex[:12])
        require(isinstance(key, str) and re.fullmatch(r'[a-z][a-z0-9-]{0,50}', key)
                and key not in self.specs and key not in ('xyren', 'draft'), 'Source name is unavailable')
        path = Path(body['path']).expanduser().resolve(strict=True)
        extracted = None
        if path.is_file():
            require(zipfile.is_zipfile(path), 'Select a cache ZIP or a cache folder')
            self.progress('Extracting source cache')
            owner = self.directory/'imported-sources'
            owner.mkdir(exist_ok=True, mode=0o700)
            extracted = owner/uuid.uuid4().hex
            try:
                cache, kind = extract_cache(path, extracted)
            except Exception:
                if extracted.exists():
                    shutil.rmtree(extracted)
                raise
        else:
            cache, kind = find_cache(path)
        spec = {'kind': kind, 'path': str(cache), 'label': str(body.get('label') or cache.name)[:100]}
        if kind == 'js5':
            spec.update(revision=body.get('revision', 235), wide=body.get('wide', True))
            for field in ('keys', 'materials', 'coastal', 'preset'):
                if body.get(field):
                    spec[field] = str(Path(body[field]).expanduser().resolve(strict=True))
        from scripts.xyren_maps.editor_sources import MapSource
        try:
            candidate = MapSource(key, spec)
            candidate.manifest()
            candidate.close()
            self.specs[key] = spec
            self.require_workspace_separation()
            from scripts.xyren_maps.editor import atomic_private
            atomic_private(self.directory/'sources.json', json_bytes(self.specs))
            self.sources.specifications[key] = spec
        except Exception:
            self.specs.pop(key, None)
            if extracted:
                shutil.rmtree(extracted)
            raise
        return self.info()

    def cache_export(self, body):
        """Compile all authored regions, dependencies, and real-reader collision.

        Export uses a new private stage and a ZIP. The compiler never points a
        writer at any input cache, and its stage cannot be a publication input.
        """
        from scripts.xyren_maps.editor import atomic_private
        from scripts.xyren_maps.editor_scene import Scenes
        from scripts.xyren_maps.editor_publication import reader_classes, export_client, decoded_scenes
        from scripts.xyren_maps.repair_server import _compiled, _records
        from scripts.xyren_maps.collision import merge_collision, read_collision
        require(self.project is not None, 'Create or open a destination project first')
        root = self.directory/'exports'
        root.mkdir(exist_ok=True, mode=0o700)
        token = uuid.uuid4().hex
        stage = root/token
        stage.mkdir(mode=0o700)
        previous_runtime = os.environ.get('XYREN_MAP_CONVERTER_RUNTIME')
        try:
            from scripts.xyren_maps.converter_runtime import reader_resources, build
            try:
                reader_resources(self.tools/'XyrenClient2')
            except ValueError as error:
                if not str(error).startswith('client resource changed; rebuild the converter runtime:'):
                    raise
                self.progress('Refreshing the private sealed codec runtime')
                build(stage/'runtime')
                os.environ['XYREN_MAP_CONVERTER_RUNTIME'] = str(stage/'runtime')
            with self.projects.lock(self.project):
                document = self.projects._load(self.project)
                require(document['revision'] == body.get('revision'), 'Project changed; refresh before exporting')
                require(document['regions'], 'Edit or paste at least one region before exporting')
                for key, identity in document['sources'].items():
                    self.sources.get(key, identity)
                source = self.sources.get('xyren')
                portable = self.projects._export(document, 'gzip')
                self.progress('Compiling map and asset dependencies')
                compiler = Scenes(self.projects)
                payload, changed, bindings = compiler._compile_native_draft(stage, document, source)
                # This public JSON length manifest is a codec input on the
                # next import. Update it with the final compiled store-zero
                # files so exported full caches can be edited again.
                length_path = source.path/'update-config.php'
                if length_path.is_file():
                    source._track(length_path)
                    lengths = json.loads(length_path.read_bytes())
                else:
                    lengths = [(source.path/f'0/{i}').stat().st_size
                               if (source.path/f'0/{i}').is_file() else 0 for i in range(9)]
                require(isinstance(lengths, list) and len(lengths) >= 6
                        and all(type(n) is int and n >= 0 for n in lengths), 'Invalid cache length manifest')
                for name in payload:
                    if name.startswith('0/') and int(name[2:]) < len(lengths):
                        lengths[int(name[2:])] = (stage/'payload'/name).stat().st_size
                payload['update-config.php'] = Scenes._put(stage, 'update-config.php', json_bytes(lengths))
                changed = set(changed)
                # Include the complete neighboring ring for contours, walls,
                # bridge ownership, and cross-square furniture collision.
                index = self._index(stage/'payload/0/5')
                collision_regions = {r for r in index if any(abs((r >> 8)-(c >> 8)) <= 1
                    and abs((r & 255)-(c & 255)) <= 1 for c in changed)}
                self.progress('Building the actual Xyren map reader')
                reader_root = Path(body.get('client') or self.tools/'XyrenClient2').resolve(strict=True)
                require((reader_root/'src').is_dir(), 'Xyren client reader source is unavailable')
                # Fresh source classes and resources bind every export. They
                # are verification inputs, never a runnable game artifact.
                cp, pins = reader_classes(stage/'reader', reader_root)
                self.progress('Validating maps, models and collision')
                receipt = export_client(stage, source.path, collision_regions, cp)
                scenes = decoded_scenes(stage, source.path, collision_regions)
                records = _records((stage/'payload/0/2').read_bytes())
                cells = _compiled(scenes, records, receipt['properties'])
                properties = receipt['properties']
                headers = {}
                for rows in cells.values():
                    for _, _, identifier, _ in rows:
                        if identifier == 65535 or identifier in headers:
                            continue
                        prop = properties[str(identifier)]
                        headers[identifier] = {2: prop.get('name') if prop.get('name') is not None else 'null',
                                               14: prop['width'], 15: prop['length']}
                for region, rows in cells.items():
                    masks = (stage/'client-collision'/str(region)).read_bytes()
                    for i in range(16384):
                        rows[i][0], rows[i][1] = masks[i*2:i*2+2]
                collision = merge_collision(headers, {}, cells)
                read_collision(collision)
                atomic_private(stage/'collision.bin', collision)
                atomic_private(stage/'project.json', json_bytes(portable['document']))
                baseline = {name: hashlib.sha256((source.path/name).read_bytes()).hexdigest()
                            for name in ('0/2', '0/5')}
                if length_path.is_file():
                    baseline['update-config.php'] = hashlib.sha256(length_path.read_bytes()).hexdigest()
                manifest = {'schema': 'xyren-standalone-map-export-1', 'project': self.project,
                    'revision': document['revision'], 'base': self.base, 'sources': document['sources'],
                    'baseline': baseline, 'format': 'Xyren native', 'changed_regions': sorted(changed),
                    'collision_regions': sorted(cells), 'bindings': bindings,
                    'tools_revision': self.tools_revision(), 'reader_inputs_sha256': hashlib.sha256(json_bytes(pins)).hexdigest(),
                    'publication': False, 'full_cache': body.get('full_cache', False),
                    'files': {name: {'bytes': (stage/'payload'/name).stat().st_size,
                                    'sha256': hashlib.sha256((stage/'payload'/name).read_bytes()).hexdigest()}
                              for name in sorted(payload)}}
                # Only numeric cache stores and the public JSON lengths enter
                # a package; source-private files never enter an export.
                included = set(payload)
                if body.get('full_cache', False):
                    for store in (0, 1, 2, 3, 4, 5):
                        directory = source.path/str(store)
                        if directory.is_dir():
                            included.update(f'{store}/{p.name}' for p in directory.iterdir()
                                            if p.name.isdecimal() and p.is_file() and not p.is_symlink())
                else:
                    included.update(receipt['assets'])
                manifest['included_files'] = len(included)
                atomic_private(stage/'manifest.json', json_bytes(manifest))
                self.progress('Packing native cache export')
                archive = stage/'xyren-map.zip'
                with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_STORED) as z:
                    for name in sorted(included):
                        require(name == 'update-config.php' or re.fullmatch(r'[0-5]/[0-9]+', name), 'Invalid export cache path')
                        private = stage/'payload'/name
                        p = private if private.is_file() else source.path/name
                        if not private.is_file():
                            source._track(p)
                        z.write(p, 'cache/'+name)
                    for name in ('manifest.json', 'project.json', 'collision.bin', 'draft-allocations.json', 'editor-client.json'):
                        z.write(stage/name, name)
                    for p in sorted((stage/'client-collision').iterdir()):
                        z.write(p, 'collision-masks/'+p.name)
                self.sources.assert_current()
                require(all(Path(p).is_file() and hashlib.sha256(Path(p).read_bytes()).hexdigest() == pin
                            for p, pin in pins.items()), 'Reader changed during export')
                digest = hashlib.sha256()
                with archive.open('rb') as f:
                    for block in iter(lambda: f.read(1024*1024), b''):
                        digest.update(block)
                self.exports[token] = archive
                result = {'token': token, 'bytes': archive.stat().st_size, 'sha256': digest.hexdigest(), 'manifest': manifest}
            # Scratch classes, payload copies and logs are unnecessary after
            # the verified ZIP has been sealed.
            for p in list(stage.iterdir()):
                if p == archive or p.name == 'manifest.json':
                    continue
                if p.is_dir(): shutil.rmtree(p)
                else: p.unlink()
            return result
        except Exception:
            # Keep the small diagnostics until the next attempt, but remove
            # large failed candidates and reader classes.
            for name in ('payload', 'reader', 'conversion', 'runtime'):
                p = stage/name
                if p.is_dir(): shutil.rmtree(p)
            archive = stage/'xyren-map.zip'
            if archive.is_file(): archive.unlink()
            raise
        finally:
            if previous_runtime is None:
                os.environ.pop('XYREN_MAP_CONVERTER_RUNTIME', None)
            else:
                os.environ['XYREN_MAP_CONVERTER_RUNTIME'] = previous_runtime

    @staticmethod
    def _index(path):
        from scripts.xyren_maps.cache import read_named, named_get
        from scripts.xyren_maps.importer import map_index
        return {r: (m, l) for r, m, l in map_index(named_get(read_named(path.read_bytes()), 'map_index'))}

    def dispatch(self, action, body):
        if action == 'hello': return self.info()
        if action == 'base':
            if self.specs[body['source']]['kind'] == 'js5':
                return self.native_destination(body['source'], body.get('regions'))
            self.change_base(body['source'])
            return self.info()
        if action == 'add_source': return self.add_source(body)
        if action == 'create':
            self.project = self.projects.create(body['name'])['id']
            return self.summary()
        if action == 'open':
            self.summary(body['project'])
            self.project = body['project']
            return self.summary()
        if action == 'projects': return self.projects.list()
        if action == 'view': return self.view(body['source'], body['regions'])
        if action == 'preview_3d': return self.preview_3d(body)
        if action == 'summary': return self.summary()
        if action == 'palette': return self.sources.get(body['source']).palette()
        if action == 'catalog': return self.sources.get(body['source']).catalog(body.get('query', ''), body.get('page', 1), 100)
        if action == 'object': return self.sources.get(body['source']).object_detail(body['id'])
        if action == 'mutate': return self.mutate(body['operation'], body['revision'])
        if action == 'save':
            require(self.project is not None, 'Create or open a destination project first')
            return self.projects.export(self.project, 'gzip')
        if action == 'import':
            self.project = self.projects.import_project(body['document'], body.get('name'))['id']
            return self.summary()
        if action == 'rebase':
            identifier = body.get('project') or self.project
            require(identifier is not None, 'Select a project first')
            if not body.get('apply', False):
                self.change_base(self.base)
            expected = body.get('revision') or self.projects._load(identifier)['revision']
            result = self.projects.rebase(identifier, expected, apply=body.get('apply', False),
                                          target_identity=body.get('target_identity'), resolutions=body.get('resolutions'))
            self.project = identifier
            self.palettes.clear()
            return result
        if action == 'export_cache': return self.cache_export(body)
        if action == 'download':
            token = body['token']
            require(token in self.exports, 'Export is unavailable')
            p = self.exports[token]
            offset, size = body.get('offset', 0), p.stat().st_size
            require(type(offset) is int and 0 <= offset <= size, 'Invalid export download offset')
            with p.open('rb') as f:
                f.seek(offset)
                data = f.read(1024*1024)
            return {'offset': offset, 'data': base64.b64encode(data).decode(), 'eof': offset+len(data) == size}
        if action == 'release_export':
            p = self.exports.pop(body['token'], None)
            require(p is not None, 'Export is unavailable')
            shutil.rmtree(p.parent)
            return {'released': True}
        if action == 'quit': return {'closed': True}
        raise ValueError('Unknown workspace command: '+str(action))


def serve(workspace, incoming, outgoing):
    def emit(value):
        outgoing.write(json.dumps(value, separators=(',', ':'), allow_nan=False)+'\n')
        outgoing.flush()
    for raw in incoming:
        identifier = None
        try:
            require(len(raw.encode()) <= MAX_MESSAGE, 'Request exceeds 64 MiB')
            request = json.loads(raw)
            require(isinstance(request, dict), 'Invalid workspace request')
            identifier = request['id']
            workspace.progress = lambda message: emit({'event': 'progress', 'id': identifier, 'message': message})
            with redirect_stdout(sys.stderr):
                result = workspace.dispatch(request['action'], request.get('body', {}))
            emit({'id': identifier, 'ok': True, 'result': result})
            if request['action'] == 'quit': break
        except Exception as error:
            if not isinstance(error, (ValueError, OSError, KeyError)):
                traceback.print_exc(file=sys.stderr)
            emit({'id': identifier, 'ok': False, 'error': str(error), 'type': type(error).__name__})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tools', default=os.environ.get('XYREN_TOOLS_HOME', '/var/rsps'))
    parser.add_argument('--workspace', type=Path, default=Path.home()/'.xyren-map-editor')
    parser.add_argument('--sources', type=Path)
    args = parser.parse_args()
    try:
        with redirect_stdout(sys.stderr):
            workspace = Workspace(args.tools, args.workspace,
                                  json.loads(args.sources.read_bytes()) if args.sources else None)
        try:
            serve(workspace, sys.stdin, sys.stdout)
        finally:
            workspace.close()
    except Exception as error:
        print(json.dumps({'id': None, 'ok': False, 'error': str(error), 'type': type(error).__name__}), flush=True)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
