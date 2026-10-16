# Modern editor interaction contract

The Android editor uses a neutral, photo-centered interface with light, dark and system-following appearances. This is part of the `dev/editor-experience` milestone. It reuses the existing image pipeline, local region and editable project format; it does not introduce layers, cloud processing, RAW/HDR editing or a Windows client.

## Appearance and layout

Appearance and canvas background are independent application preferences. Automatic canvas color follows the appearance; explicit light, middle or dark gray remains selected across appearance changes. Neither preference enters the project manifest or image renderer. Material surfaces, dialogs and controls use the same neutral color scheme; line icons are drawn by project-owned code.

The editor places project status and export above the photo, viewing and history actions beneath it, and a contextual tool area at the bottom. Wide windows use a right-hand panel. Tool content can scroll while cancel and finish remain accessible; narrow or short windows cap panel height to preserve canvas space. Project information distinguishes draft, rendering, saving and output states.

The four categories are **调整 / 修饰 / 构图 / 预设**. Category selection does not edit the image. Active tools replace category navigation, so switching tools requires resolving the current session. Parameter selection uses labeled icons, a short selection underline and a changed-value marker. A list of all parameters provides an alternative to horizontally scrolling the ribbon.

## Parameter controls

| Parameter type | Control | Interaction |
| --- | --- | --- |
| Signed adjustments and angle | Relative tape ruler with a fixed center needle | Move the tape left to increase the value; touching it does not jump the value. No inertia. Reversing at an endpoint responds immediately. |
| Softening, detail protection, sharpening and brush properties | Thin absolute rail | Full range stays visible; touch/drag the control area to position the value. |
| All numeric controls | Clickable numeric value | Validated exact input, units, range and increment/decrement buttons. Cancel changes nothing; confirmation records one draft step. |

Exposure uses 0.05 EV per 8 dp, or 0.01 EV in fine mode. Angle uses 0.5° / 0.1°. Signed integer controls use one unit per 8 dp, or per 24 dp in fine mode. Android touch slop separates horizontal adjustments from vertical panel scrolling. An interrupted adjustment restores its starting value. Each completed gesture creates one draft history step, not one step per sampled motion event.

Display units preserve processing ranges: exposure ±3 EV, straighten ±45°, signed normalized values ±100, saturation −100…100 around the engine's neutral value 1, sharpening 0…200%, and local strength/protection 0…100%. Reset uses the actual parameter default, including 75% detail protection. Input rejects non-finite, malformed and out-of-range values rather than silently clipping them.

Custom controls expose range/set-progress accessibility semantics, state and units, a keyboard focus indicator, arrow-key adjustment and a non-dragging input route. The control is 68 dp tall. Accessibility semantics are implemented; automated interaction does not establish a TalkBack usability certification or a physical-device usability result.

## Sessions and history

Opening a tool snapshots its baseline. Parameter changes remain drafts until **完成**. Draft undo/redo works for every tool, including geometry and presets. Finish creates one main history step for the tool session and schedules saving; an unchanged session adds no history entry. Cancel discards only that session. Stale callbacks are checked against the tool session and parameter before updating state.

System Back leaves the local effect view for selection without applying it. Leaving a changed tool asks whether to apply, discard or continue. The project-return button uses the same decision and then returns home. Read-only project information, detail inspection, comparison and export settings do not commit a draft. In-memory drafts and history survive activity recreation; process death does not imply draft recovery.

## Local editing and viewing

Softening and local exposure share **one** existing region. Empty-region entry starts in viewing mode; explicitly choose 添加区域 or 擦除区域 to draw. Suggestions require a user action, remain experimental and do not automatically enable an effect. Switching to effects hides the overlay and stops drawing; returning restores the selection mode. Region controls and precise brush settings are shared with the native-resolution inspector.

In the overview, one finger paints in drawing mode and two fingers navigate. Adding a second finger cancels the pending stroke; drawing cannot resume until all fingers lift. Moving mode supports pan/zoom. Crop handles retain their own coordinate mapping and a completed drag is undoable within the crop session.

Comparison is read-only and retains the existing renderer semantics: compare image adjustments while retaining current geometry. The overlay explicitly says **原图 · 保持构图**; it does not claim pixel alignment with an uncropped original. Detail comparison uses the same geometry contract. Fit-view is a viewing action only.

## Export

The output sheet shows photo/project targets. Photo output exposes format, quality, size and the existing SDR/metadata limitations. Project output omits photo-only controls. With a draft, the action explicitly reads **应用并导出** or **应用并分享**. Opening or dismissing the sheet leaves the draft intact. Confirmation applies the edit, then follows the existing save/prepare/system-picker/write pipeline. Cancelling the system picker leaves an already applied edit saved; it does not report a successful export.

## Design sources and boundaries

The design combines labeled parameter selection, reversible tool sessions, precise numeric entry and restrained visual hierarchy. Sources used for the design review include [Apple Photos editing](https://support.apple.com/guide/iphone/edit-photos-and-videos-iphb08064d57/ios), [Samsung Gallery editing](https://www.samsung.com/us/support/answer/ANS10003229/), [Lightroom Android color adjustment](https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/adjust-colors.html), [VSCO exposure](https://support.vsco.co/en/articles/12698571-using-the-exposure-tool), [Snapseed gesture-based adjustment](https://support.google.com/snapseed/answer/6158224?hl=en), and [Darkroom's interface overview](https://darkroom.co/help/guide/interface-overview). Screenshots and documentation do not establish those products' exact touch gains or hidden gestures; the ruler direction and gains above are NijiFrame choices to evaluate.

Method references include [Shneiderman's direct-manipulation work](https://www.cs.umd.edu/~ben/publications.html), [numeric exploration versus exact controls](https://www.nngroup.com/articles/sliders-knobs/), [visual hierarchy principles](https://www.nngroup.com/articles/principles-visual-design/), [alternatives to dragging](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html), and [Android accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults). No competitor assets, fonts or screenshots are bundled.

The existing [portrait patent investigation](portrait-region-patents.md) and [milestone patent boundaries](editor-experience-milestone.md#笔刷交互复查) remain applicable. This change uses an explicit shared mask, ordinary numeric controls and independent view preferences; it adds no edge-detecting effect brush, face manipulation algorithm, snapping or haptic technique. GUI design drawings and interaction claim combinations still require review. Common use and independent code do not establish patent clearance.

## Verification and remaining evaluation

Pure JVM tests cover parameter-unit round trips, invalid numeric input, fine movement accumulation and endpoint reversal. Instrumented tests exercise actual exact input, draft/main histories, appearance changes, recreation, comparison, two-finger navigation, crop, brush, detail inspection, save, export and import using synthetic images. Platform and run-specific results belong in `docs/validation.md`.

Physical-device evaluation should compare reaching +0.35 EV, returning to neutral, selecting a strength, cancelling and finding exact input. Record direction mistakes, corrections and task completion rather than assuming a new appearance improves efficiency. Haptic feedback, press-and-hold comparison and preset thumbnail generation are not added in this implementation. Animated panel transitions are not required for operation. Existing codec and 16 KiB runtime failures are tracked independently from UI acceptance.
