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

In 0.6, open **查看细节**, tap a position in the overview, and compare **查看原图／查看调整**. Close it and confirm a pending tool draft remains pending; cancel the draft and reopen the saved project. For ordinary phone trials, check a few positions rather than repeatedly moving through a maximum-sized input.

- 人像区域：清晰单人照片 → 局部 → 建议区域 → 保留原区域／采用并修正；检查替换提示、撤销／重做、补画／擦除、取消／应用、重开与导出。无脸／多人／遮挡失败时原区域保留。检查眼镜、胡须、头发和脸缘；用原尺寸细节比较最终效果，不把区域出现当作效果合格。普通手机试用只使用小图与短操作；故障、压力和自动化重装仅在专用设备／模拟器执行。

- Format compatibility: try a normal phone JPEG that previously failed the sRGB check, then inspect its source-format/color banner, compare the SDR result, reopen the project and export to a new PNG/JPEG. The original file must remain unchanged. A phone gallery can show HDR highlights or wide-gamut colors that the current SDR export does not retain. See [photo input](photo-input.md) for HEIF/AVIF device and memory limits.
