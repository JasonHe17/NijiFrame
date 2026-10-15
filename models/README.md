# Portrait model provenance

The Android build packages `face_landmarker.task` from the official [Face Landmarker model bundle](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task). The exact URL, byte length and SHA-256 are in `face-landmarker.properties`. Do not replace the file in place without updating provenance, evaluating failure cases and reconsidering dependencies/patent claims.

The bundle includes BlazeFace short-range detection, Face Mesh V2 and Blendshape V2 weights. Blendshape output is disabled in this app, but the bundle still contains those weights. Each model's official card states Apache-2.0:

- [BlazeFace short range](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf)
- [Face Mesh V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf)
- [Blendshape V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf)

Copyright and attribution: Google / MediaPipe Authors. Apache-2.0 text is included in `licenses/`. Weights are used unmodified, not trained by this project. Model cards describe collection/evaluation conditions and limitations; this repository does not possess or independently audit the complete training corpus. A model license is not a guarantee of data coverage, accuracy or freedom from third-party patent claims.

The `.task` file is generated in `app/build/generated/modelAssets/`, not checked into Git. `preparePortraitModel` verifies its pinned digest even for a supplied offline file. The installed app works offline. See [development](../docs/development.md) for cache/offline instructions. No evaluation portraits are shipped with the app.
