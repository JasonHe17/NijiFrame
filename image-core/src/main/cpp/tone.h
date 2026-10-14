// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// In-place opaque ARGB sRGB, tightly packed; returns 0 on success, -1 on invalid input.
int nf_tone(uint32_t* pixels, size_t count, float exposure_ev, float saturation);
#ifdef __cplusplus
}
#endif
