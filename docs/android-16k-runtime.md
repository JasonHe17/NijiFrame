# Android 15 emulator runtime and the 16KiB test kernel

Android 15 and 16KiB devices remain supported development targets. The observed
native crashes are confined to the API35 `google_apis_ps16k` x86_64 rev5 emulator
in the available evidence; they have not been reproduced on a physical ARM64
phone. A simulator failure alone does not establish an Android 15 phone defect.
Google documents [16KiB device support](https://developer.android.com/guide/practices/page-sizes)
starting with Android 15.

## The incompatible legacy configuration

The affected image has fingerprint
`google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`
and kernel `6.6.50-android15-8-g8adecb593e9b-ab12525588`, built in October 2024.
It advertises 16384-byte pages to userspace while x86 hardware uses 4096-byte
pages. It also exposes userfaultfd and enables ART's concurrent mark compact
(CMC) collector.

AOSP subsequently disabled userfaultfd for this emulated page-size configuration
in [kernel commit 38447e018c92](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73)
(December 2024), referencing bugs 385144325, 379001861, 384985178 and 377361489.
Its validation explicitly expects ART to select concurrent copying (CC) instead
of CMC and checks that system restarts cease. The legacy image predates that fix.

This is a version-specific compatibility problem, not a rule that all 16KiB or
UFFD configurations are broken. AOSP later
[re-enabled UFFD in newer kernels](https://android.googlesource.com/kernel/common/+/7aa7a88586d9f98dc3f97c55ec4e06961e2dabd0)
and describes how aligned UFFD operations can work with page-size emulation.
Changing arbitrary modern phone collectors is not part of this project.

## Fix applied to CI

Keep the API35 16KiB userspace and all application tests. Boot it with the
official Android 15 GKI shipped in `system-images;android-36;default;x86_64`
revision 2, using the emulator's `-kernel` argument:

| Identity | Value |
| --- | --- |
| Kernel | `6.6.66-android15-8-gb66429556fb8-ab13070261`, February 2025 |
| File inside the installed SDK | `system-images/android-36/default/x86_64/kernel-ranchu` |
| SHA-256 | `3f366c20522314f8ccbef89210eabec3491d18997799882e1f0d4d8134e8b937` |
| Required runtime page size | 16384 |
| Required zygote collector | CC, selected by ART after the kernel rejects UFFD |

The checksum is checked before boot, and the page size and actual zygote
collector are checked before application tests. Kernel identity and boot command
line are retained as evidence. An unexpected replacement fails setup rather
than silently changing the validated runtime. A future kernel update requires
reviewing this identity and rerunning the suite.

No JIT flag or phone runtime setting is changed. This adopts a corrected official
kernel for a dedicated emulator. Android 15 with ordinary 4KiB pages is also a
separate CI row; 16KiB coverage is retained. A newer SDK kernel's presence alone
does not count as an application test pass.

## Evidence and limits

The old image produced application SIGSEGVs in different JIT/interpreter paths,
as well as independent MediaProvider and system_server crashes. The subsequent
[develop run 38913733755](https://github.com/JasonHe17/NijiFrame/actions/runs/38913733755)
completed 54 tests with one UI failure; its full log records a Google Play
services native crash and a SystemUI ReferenceQueueDaemon null pointer exception.
The UI failure follows a SystemUI restart during interaction and is retained
separately; it is not a new application SIGSEGV.

Controlled local comparison keeps the same API35 userspace and 16KiB page size.
The old kernel permits a userfaultfd syscall; the corrected kernel returns
`EOPNOTSUPP` and ART selects CC automatically. This directly verifies the upstream
fix boundary. Pure Java allocation/reference/GC probes without NijiFrame,
Compose, MediaPipe or native image code are negative observations on both
kernels and the ordinary 4KiB image; they do not reproduce the historical crash.

The legacy kernel's known incompatibility explains why this is an unreliable
test environment. Attribution of every historical JIT stack to that one cause
remains an inference, not a deterministic application reproducer. Preserve the
original failures. Passing the corrected environment does not prove ARM64 phone
performance, full TalkBack acceptance or that unrelated future crashes are fixed.

Local full application suites passed 54/54 on ordinary API35/4KiB and 54/54
on corrected-kernel API35/16KiB, with zero failures or skips. A startup
`DeadSystemException` during an intentional framework restart is retained
separately; it preceded the application suite and is not an application SIGSEGV.

The [workflow](../.github/workflows/android.yml) and
[preparation helper](../scripts/prepare-test-emulator.py) encode the fix and its
checks. Current test outcomes and outstanding items belong in the delivery
record; do not infer successful execution merely from this configuration.
