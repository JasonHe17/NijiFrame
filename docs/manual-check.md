# A short manual check

Use a copy of a small sRGB JPEG or opaque PNG (about 2MP is a useful starting point). On a daily-use phone, ordinary short editing sessions are sufficient: no root, bootloader changes, malformed inputs, stress loops or instrumentation test APKs are needed. These do not replace device compatibility testing. Development builds can crash or lose uncommitted edits; back up important projects and choose new export filenames.

1. Import and check orientation/color. Duplicate the project before experimenting.
2. Change exposure or saturation, try Cancel, then Apply, Undo and Redo.
3. Open Local, paint a small region, set local exposure near +1EV and hide the coverage overlay. Only that region should brighten.
4. Switch to move mode to zoom/pan; switch back to erase and remove part of the region. Apply, rotate/crop and confirm the adjustment follows the same photo content.
5. For the softening candidate, manually select skin away from eyes/lips/hair, select the experimental softening control and start with a low strength. Hide the overlay and wait for the exact preview. Compare pores, eyelashes and borders at native scale; do not treat smoother as automatically better.
6. Apply and wait for Saved. Close/reopen the app and confirm parameters and region. Export PNG and separately export/reopen the editable project.
7. Report app version, Android version/device model, steps, expected result and actual result. Share only images you are comfortable and authorized to publish.

The APK uses Android's normal app sandbox and user-selected files; that is not a promise that the worst possible outcome is limited to a crash. Avoid long processing sessions on a daily-use phone and keep backups. CI debug signing identities can vary; a signature mismatch is a reason to back up first, not to uninstall blindly.

Download/build instructions are in the [README](../README.md); details and draft persistence behavior are in the [user guide](user-guide.md). The Android instrumentation runner reinstalls/uninstalls the app, so run it on a dedicated emulator instead of a phone containing important projects.

For 0.5 large-image checks, start with one ordinary JPEG from your phone. The editor should retain the original dimensions and identify a reduced preview. Try a 1280-edge export first, then a larger size if desired. Original-size output can be refused when memory is insufficient; do not stress a daily-use phone by repeatedly retrying the same refusal. Keep high-volume, maximum-size and low-memory automation on a dedicated emulator. See [high-resolution photos](large-images.md).
