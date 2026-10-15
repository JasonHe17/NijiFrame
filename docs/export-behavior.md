# Export preparation, cancellation and destination writes

Photo export and project export prepare their entire encoded output in a uniquely named private cache file. Only after preparation completes and cancellation is checked does the app open the chosen destination with `ContentResolver.openOutputStream(uri, "wt")`. JPEG/PNG dimensions, color interpretation, metadata policy and the v6 project format are unchanged.

The editor reports these phases:

| Phase | Cancel available | Behavior |
| --- | --- | --- |
| Processing photo | Yes | Existing memory admission, source decode and rendering; no destination opened |
| Encoding photo / packaging project | Yes | Write into private temporary storage; check cancellation between output writes |
| Writing file | No | Copy the complete staged file to the chosen destination with a fixed 64 KiB buffer |

Preparation exceptions and cancellation delete the temporary file and leave an existing destination unopened. Cancellation arriving during the transition into the write phase is checked once more before opening the destination. After that boundary the transfer finishes independently of coroutine cancellation, including stream close. The editor stops accepting its Cancel action during this phase. Platform decoding or encoding can take time between cancellation checkpoints; cancellation is not necessarily instantaneous.

Export-only decoded and rendered bitmaps are released after encoding, before destination copying. No complete encoded result is buffered in a byte array. Temporary storage up to the full encoded output size is required, in addition to the destination's storage. Output size is not limited by the input's 64 MiB cap. Staging is cleaned on success and failure; matching leftovers older than 24 hours are removed during repository initialization after an interrupted process.

## Limits of destination protection

A complete staging file protects against preparation/encoding failures, but an external document provider does not offer a universal atomic replacement operation. Failure opening, copying, flushing or closing the provider stream can still leave an empty or partial destination. Process termination or storage removal can also interrupt the write. Use a new output name for important exports and retry after a provider failure; saved library projects remain independent.

Android's document picker may create an empty new document before returning its URI, even if the app subsequently cancels preparation. The app does not remove that provider-created document automatically. Internal library saves retain their existing atomic-file behavior. System sharing uses the same prepared-photo path, then grants access only to its generated shared file.

## Verification and API notes

`ExportSafetyTest` exercises real PNG encoding cancellation, injected preparation and provider failures, cancellation immediately before and during the commit boundary, fully prepared JPEG/PNG/project contents before destination opening, and the real editor's Cancel behavior. Existing source/geometry/color/project tests continue through this export path. Tests use synthetic content and dedicated installations.

`ProjectRepository.exportProject` is now suspending, like photo export. Export methods accept an optional suspending phase observer; callers running expensive work use a worker dispatcher, and UI observers switch to Main. `StagedExport` uses `withContext(NonCancellable + Dispatchers.IO)` only around the final copy, not around rendering or encoding.

References: [ContentResolver output streams and provider-specific modes](https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)), [Kotlin NonCancellable](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/). No new codec, runtime dependency, rendering algorithm, network-sharing protocol or project schema is introduced.
