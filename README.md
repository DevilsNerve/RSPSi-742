# RSPSi-742

This fork includes a **Xyren / Emps World / Near Reality map workspace**.
Open two maps, drag an area, copy its terrain and/or objects, and paste it into
a destination project. Source object IDs stay tied to their source cache;
export brings the required models, floors, textures and animations with them.

The workspace supports all four planes, selections across map squares,
rotation/reflection, height matching, terrain editing, object placement,
undo/redo, automatic project saving, portable project files and validated
Xyren cache exports. Its viewport is a 2D terrain and object-footprint view.
The original experimental 742 3D editor remains a separate launcher option.

Use **Java 17 or 21**. On the Xyren tools host:

```sh
./gradlew :WorldEditor:run
```

For a desktop package:

```sh
./gradlew :WorldEditor:installDist :WorldEditor:distZip
```

Run `WorldEditor/build/install/xyren-map-editor/bin/xyren-map-editor`
(`xyren-map-editor.bat` on Windows). The desktop connects to the maintained
Xyren map tools locally or over SSH. See [setup and editing instructions](docs/xyren-workspace.md)
for cache inputs, Windows connections and the export format.

Verification:

```sh
./gradlew :WorldEditor:check
python3 tools/check_maps.py --output docs/map-round-trip-proof.json
python3 tools/check_destinations.py --output docs/destination-proof.json
xvfb-run -a ./gradlew -Dxyren.ui.test=true :WorldEditor:test
```

These checks use private temporary projects and the actual Xyren map/model/
collision readers. They do not publish a live world. The retained receipts
record the tested source identities and decoder inputs.

## Original 742 editor

![Custom Lletya made with RSPSi-742](https://i.imgur.com/vHNucy9.jpeg)
###### Custom Lletya made with RSPSi-742
____________________________________________________________________
**<u>The following has been changed from the original**</u><br><br>
Added client-side 742 compatibility in:
- `Cache.java`
- `Chunk.java`
- `Client.java`
- `GameRasterizer.java`
- `KeyCombination.java`
- `MapRegion.java`
- `Mesh.java`
- `Mesh525.java`
- `Mesh622.java`
- `MeshLoader.java`
- `ObjectDefinition.java`

Converted `Plugin667` in `\plugins\` to `Plugin742` by extending and/or modifying:
- `AnimationDefLoader.java`  
- `FloorDefLoader.java`
- `ObjectDefLoader.java`
- `RSAreaLoaderOSRS.java`
- `SpotAnimationLoader.java`
- `TextureLoaderOSRS.java`
- `VarbitLoaderOSRS.java`
- `Plugin742.java` (from `Plugin667.java`)

All other plugin files remain the same as they were in the 667 build.

# Compatibility
- Use Java 17 or 21; Gradle supplies the JavaFX libraries.
- This has only been tested using the below resources. Results with other caches may vary.

# Disclaimers
- This build is **<u>EXPERIMENTAL</u>**, it (in my opinion) pushes the limit of what the internal client can achieve.
<br><br>
- This build **WILL** fire console errors while you work, mostly around mesh face issues. The majority of them can be ignored unless workspace issues occur.
  <br><br>
- Some models may appear incorrect or have missing textures. This was somewhat present in the 667 build, but even more so in 742. These issues are the cause of the console errors.
  <br><br>
- Your real client will not fail to decode meshes the same way this might, however, always double-check once you import that nothing was missed or removed.
  <br><br>
- RSPSi does not contain a method to modify under-map landscape. This is usually (I think?) a third data file. As it sits, maps with water will not have under-map landscape generated and will appear incorrect with HD water enabled.
  <br><br>
- If an estimate for "how functional is this?" were given, it would be `75%-80%` due to the remaining bugs.
  <br><br>

**<u><i>ALWAYS back up your cache before importing map files to it!</i></u>**
  
# Resources
- [742 Cache](https://archive.openrs2.org/caches/runescape/544/disk.zip)
- [742 XTEAs](https://archive.openrs2.org/caches/runescape/544/keys.json)
- JavaFX dependencies are declared in `Client/build.gradle`.

# Credits
- [The original RSPSi](https://github.com/RSPSi/RSPSi) - Creating the editor.
- [2011Scape/RSPSi-667](https://github.com/2011Scape/RSPSi-667) - Extending the editor closer to the state needed for 742.
- [LostCityRS/RS742](https://github.com/LostCityRS/RS742) - Amazing deobfuscated 742 client used for reference to make this work.

A massive thank-you to all the talented developers who put their time and effort into these projects which let me get this done. It could never have been done without their hard work.
