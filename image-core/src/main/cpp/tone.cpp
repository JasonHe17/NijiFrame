// SPDX-License-Identifier: GPL-3.0-only
#include "tone.h"
#include <algorithm>
#include <cmath>

static float linear(float v) { return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f); }
static uint32_t encoded(float v) {
    v = std::clamp(v, 0.0f, 1.0f);
    const float s = v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
    return static_cast<uint32_t>(std::lround(s * 255.0f));
}
int nf_tone(uint32_t* pixels, size_t count, float ev, float sat) {
    if ((!pixels && count) || !std::isfinite(ev) || !std::isfinite(sat) ||
        ev < -3 || ev > 3 || sat < 0 || sat > 2) return -1;
    if (ev == 0 && sat == 1) return 0;
    float lut[256];
    const float gain = std::exp2(ev);
    for (int i = 0; i < 256; ++i) lut[i] = linear(static_cast<float>(i) / 255.0f) * gain;
    for (size_t i = 0; i < count; ++i) {
        const auto p = pixels[i];
        const float r = lut[(p >> 16) & 255], g = lut[(p >> 8) & 255], b = lut[p & 255];
        const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        pixels[i] = 0xff000000u | (encoded(y + sat * (r - y)) << 16) |
                    (encoded(y + sat * (g - y)) << 8) | encoded(y + sat * (b - y));
    }
    return 0;
}
