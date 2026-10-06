# Xyren map workspace

The existing RSPSi fork now has a source-aware map authoring mode. It reads
Xyren and Emps native caches and Near Reality JS5 caches, keeps each source's
object and material namespace, and produces Xyren native cache packages.

## Start the desktop

Install Java 17 or 21. On the host with the Xyren tools and caches, run
`./gradlew :WorldEditor:run` from this checkout. Alternatively build
`:WorldEditor:installDist` and run `bin/xyren-map-editor` from the resulting
installation. The distribution includes the bridge and these instructions.
The RSPSi launcher also offers **Xyren / Emps / Near Reality**.

The Python backend requires Python 3.10+, Java/Javac and the maintained
`/var/rsps/scripts/xyren_maps` checkout, including its verified converter
runtime and `/var/rsps/XyrenClient2` reader source/dependencies. Use `--tools`
or `XYREN_TOOLS_HOME` to choose another complete checkout. The backend invokes
the existing codecs and compiler; there is no second map converter in this
repository. A stale sealed resource is rebuilt in the private export stage
through the maintained sealing tool, which requires the tools-host owner.

The default backend is `python3 -u tools/xyren_bridge.py`. **Connection**
accepts a JSON array of command arguments. For a Windows desktop, extract
the `WorldEditor/build/distributions/xyren-map-editor-1.17.0.zip` package,
run `bin/xyren-map-editor.bat`, and configure your existing SSH access:

```json
["ssh", "-T", "-o", "BatchMode=yes", "your-user@your-tools-host", "python3", "-u", "/root/RSPSi-742/tools/xyren_bridge.py"]
```

SSH must already authenticate without an interactive password prompt and
verify the host's identity. Backend cache paths refer to the SSH host.
Project save/import and exported ZIP paths refer to your desktop. This mode
does not require a website account, launcher login or a public editor API.

## Choose maps and a destination

The tools-host defaults are the current Xyren world, the Emps reference
cache and Near Reality revision 235. Unavailable optional sources are shown
as unavailable while a valid Xyren base remains usable. **Add source** accepts
a native cache folder or ZIP containing `0/2` and `0/5`, or a JS5 cache folder
or ZIP containing `main_file_cache.dat2` and index files 2, 5 and 255. Near
Reality uses its matching XTEA JSON when location groups are encrypted.
Revisions 211 and 235 use the maintained JS5 adapters. A loose terrain file
alone cannot supply object definitions, meshes or materials.
One project can compile one JS5 source namespace together with multiple native
sources. Use a full exported native cache to combine further JS5 namespaces.

Xyren and Emps can be selected directly as **Destination base**. Select
Near Reality, enter the area's world coordinates in the donor pane, and
click **Use base**. The selected 64×64 map square and all four planes are
converted once, together with their assets, into a private native cache over
the full Xyren asset base. The editor then uses that area as an editable
destination. Other Near Reality areas can be copied into the project normally.
Original native and JS5 caches are read-only. Near Reality destinations and
exports use Xyren's native format rather than writing an original JS5 cache.

Click **New**, name the project, and load the donor and destination by world
X/Y. Each pane loads the available neighboring squares, up to nine squares.
Change **Plane** to inspect floors 0–3. Middle-drag pans, the mouse wheel
zooms, and left-drag selects a world rectangle. Gold outlines show the active
copy pane. Terrain uses the actual source floor palette and shaped overlays;
object outlines show source footprints, placement types and facing.
The workspace is a 2D authoring view; it does not preview textured 3D meshes.

## Copy and edit

1. Drag a rectangle in either pane and choose terrain, heights, flags and/or
   objects. **All four planes** is enabled initially.
2. Click **Copy**, or use Ctrl/Cmd+C. **Cut** works on the destination draft.
3. Right-click the destination to set its anchor, or enter paste X/Y. Choose
   rotation, Mirror X/Y, object replacement/merge, and absolute or relative
   heights. Height values and offsets are multiples of eight.
4. Click **Paste**, or use Ctrl/Cmd+V. Missing destination squares are created
   privately. Object IDs retain their source identity even when the same
   numeric ID means something else in another cache.

**Undo** and **Redo** restore complete edits, including newly created squares.
**Delete** applies the selected layers and planes. **Fill terrain / water**
selects floors from a source palette and can change overlay shape, flags and
elevation. **Raise**, **Lower** and **Smooth** affect the selected draft area.
**New region** creates an empty square at the paste coordinates.

**Add object** searches source definitions by name or ID. Set its type and
facing, then place it at the destination anchor. Right-click an existing
destination object or select it in the table to edit its coordinates, plane,
facing and reflection, duplicate it or remove it. A source's geometry,
textures, animation dependencies and clipping are compiled with its placement.
This does not port NPC spawns, quests or source game scripts.

## Save and export

Edits save automatically on the backend in `~/.xyren-map-editor/projects`.
**Open** returns to an existing project. **Save project** writes a portable
`.xyren-map.json` file to the desktop; **Import project** creates a new project
from it. Source identities and optimistic revision checks prevent accidental
reuse against different caches. Donor sources must remain available and
unchanged for source-backed edits and exports. Imported cache ZIPs and converted
destination caches live under the backend workspace.

If the destination base changes later, select the project and click **Update
base**, including when its old identity prevents **Open**. Review the previous
base, draft and current values, choose a value for every conflict, and apply
the update. The editor retains a previous checkpoint. A changed foreign donor
still requires its original cache; the update never substitutes a different
donor's assets.

**Export cache** compiles every authored square, allocates source assets,
checks the actual Xyren definitions/model/material/map readers, and derives
server movement and projectile collision from the actual client reader.
Neighboring squares participate in contour and cross-square collision checks.
Export fails on missing assets, capacity exhaustion or changed inputs.

The ZIP contains:

- `cache/`: changed native data and source asset dependencies;
- `cache/update-config.php`: the public JSON store-zero length manifest;
- `collision.bin` and per-square client collision masks;
- `project.json`, allocation records and the reader receipt;
- `manifest.json`: source identities, base fingerprints, changed squares,
  file hashes, decoder inputs and whether the full base is included.

The default **Include the complete base cache** makes the cache independently
reopenable as another source/destination. Uncheck it for a smaller dependency
bundle that must be combined with the exact base fingerprints in the manifest.
Transfers verify the ZIP size and SHA-256 before replacing the destination
file. The backend removes the completed export stage after download.

Exports are reviewable private packages. Installing their maps and collision
in a live Xyren world remains subject to the existing cache, server and
protected-release procedure. No source cache, live world or immutable Emps
reference is overwritten by this workspace.

## Verification

`./gradlew :WorldEditor:check` runs real-codec fixture tests and Java viewport/
connection checks. `tools/check_maps.py` exercises all three real source caches,
four planes, reflected objects, map-square boundaries, undo/redo, portable
save/reopen, compiled export and verified download.
`tools/check_destinations.py` checks transfers into Xyren, Emps and a normalized
Near Reality destination with the actual Xyren readers. Both use disposable
temporary projects and remove their candidate caches.

An X display enables the desktop interaction test:

```sh
xvfb-run -a -s '-screen 0 1920x1080x24' ./gradlew -Dxyren.ui.test=true :WorldEditor:test
```

The retained JSON receipts and desktop screenshot document the tested Linux
workflow. `windows-launch-proof.json` records the exact distributed package
launching on the paired Windows desktop with a checksum-verified, Authenticode-
verified Temurin 21 runtime and connecting to the three real caches over SSH.
The desktop helper rejected foreground capture/input with `FOCUS_LOST`, so
Windows copy/paste, object edits, save/import and export remain untested there.
That run is stopped, its task artifacts are removed, and the original launch
approvals are restored. Local activation of the editor window is needed for
the next Windows interaction check. Legacy 742 visual fidelity remains a
separate acceptance check.
