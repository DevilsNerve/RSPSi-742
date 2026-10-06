# RSPSi source-aware 3D editing

RSPSi's JavaFX launcher now opens its source-aware 3D editing mode for **Xyren / Emps / Near Reality**. It runs in the Editor application, with Xyren on the left and one donor on the right. The donor menu switches between the configured caches. The original 742 editing mode remains available through the legacy launch controls.

This mode uses independent JavaFX 3D cameras, depth buffers, meshes and materials. It does not open the separate Swing 2D workspace. The existing software-rendered 742 editor remains a separate legacy mode; its global cache loaders do not own this multi-source view.

## Windows use

Build `:Editor:installDist`, then run `RSPSi.bat --xyren3d`, or use the source button in the launcher. The backend command uses the connection already saved for the map workspace.

1. Create or open a Xyren project.
2. Enter coordinates in each pane and choose **Load 3D**. Switch the donor between Emps World and Near Reality. Enable **3×3 regions** to view adjoining map squares together.
3. Drag with the left mouse button to select tiles. Orbit with the right button, pan with the middle button and zoom with the wheel.
4. Select the layers and planes to transfer, then **Copy**. Click the target tile in Xyren to set the paste coordinates. Choose rotation, reflection, replace/merge and height options, then **Paste**.
5. Use undo/redo, deletion, floor painting, height editing or object placement as needed. Save a portable project or export a complete native cache ZIP.

Edits and exports use the maintained source-aware backend, including source identities, asset allocation and collision verification. The original donor caches and live game caches are not editing destinations.

## 3D bridge installation

The server needs both `tools/xyren_bridge.py` and `tools/RSPSiPreview.java` from the same revision, installed beside one another. Preserve the existing SSH forced command and key restrictions. No server game or web process needs to be started or modified.

`hello.capabilities.preview_3d = 1` enables scenery requests. An older bridge still supports terrain viewing and project editing; Windows reports that model preview is waiting for the bridge update.

The `preview_3d` request builds a private scene with `scripts.xyren_maps.editor_scene.Scenes`, preserving the existing asset compiler. Foreign object keys are resolved through the compiled scene's bindings, rather than substituting objects with matching numeric IDs. A freshly built, pinned client reader bakes static geometry and decodes texture pixels in an isolated process. The response is a checksum-verified ZIP downloaded using the existing transport. Compiler logs stay on stderr to protect the JSON protocol.

Previews show the selected plane and static scenery. Animated playback, particles, dynamic lights, water shaders and gameplay simulation are not implemented in this viewport. A decoder failure is reported in the packet and Windows status; it must not be treated as a complete preview. The actual maintained native cache compiler still owns exported animation and gameplay dependencies.

## Verification

- Windows: `gradlew.bat :Editor:test -Dxyren.ui.test=true :Editor:installDist` exercises the real JavaFX launcher, two independent depth-buffered cameras, all 96 overlay shape codes and two textured model packets that deliberately reuse the same texture ID. The rendered fixture is written to `Editor/build/test-preview/rspsi-two-views.png`.
- Transport: `python3 -m unittest discover -s tools -p test_preview_transport.py -v` verifies foreign ID bindings, revision conflicts, private scene cleanup, ZIP checksums and JSON output isolation using substituted compiler owners.
- Linux integration still needs the real cache and reader checks: create a private Xyren project, request `preview_3d` for Xyren, Emps World and Near Reality at region 12850, download each packet, and inspect model/texture counts and failures. Then paste a donor section into the Xyren draft and request its preview. Verify original source hashes before and after. Local fixture tests alone do not establish decoder compatibility with the live sources.
