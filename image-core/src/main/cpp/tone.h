// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// In-place opaque ARGB sRGB, tightly packed; returns 0 on success, -1 on invalid input.
int nf_tone(uint32_t* pixels, size_t count, float exposure_ev, float saturation);
typedef struct {
    float exposure_ev, saturation, contrast, highlights, shadows, whites, blacks, warmth, tint, vibrance;
} nf_adjustments;
int nf_basic_tone(uint32_t* pixels, size_t count, const nf_adjustments* parameters);
// Separate source/destination, tightly packed ARGB. Borders use clamped neighbors.
int nf_sharpen(const uint32_t* source, uint32_t* destination, int width, int height, float amount);
int nf_vignette(uint32_t* pixels, int width, int full_height, int first_row, int rows, float amount);
int nf_local_exposure(uint32_t* pixels, const uint8_t* mask, size_t count, float ev);
// Source includes neighboring halo rows; destination/mask contain width*rows elements.
int nf_soften(const uint32_t* source, uint32_t* destination, const uint8_t* mask,
              int width, int height, int first_row, int rows, int radius, float strength, float protection);
#ifdef __cplusplus
}
#endif
