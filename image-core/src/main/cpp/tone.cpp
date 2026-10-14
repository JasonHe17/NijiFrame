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


int nf_basic_tone(uint32_t* pixels, size_t count, const nf_adjustments* p) {
    if (!p || (!pixels && count)) return -1;
    const float bounded[] = {p->contrast, p->highlights, p->shadows, p->whites,
                             p->blacks, p->warmth, p->tint, p->vibrance};
    bool neutral = true;
    for (float value : bounded) {
        if (!std::isfinite(value) || value < -1 || value > 1) return -1;
        neutral = neutral && value == 0;
    }
    if (neutral) return nf_tone(pixels, count, p->exposure_ev, p->saturation);
    if (!std::isfinite(p->exposure_ev) || p->exposure_ev < -3 || p->exposure_ev > 3 ||
        !std::isfinite(p->saturation) || p->saturation < 0 || p->saturation > 2) return -1;
    const float exposure = std::exp2(p->exposure_ev);
    const float contrast = std::exp2(p->contrast);
    const float red_gain = std::exp2(p->warmth * 0.4f);
    const float green_gain = std::exp2(-p->tint * 0.3f);
    const float blue_gain = std::exp2(-p->warmth * 0.4f);
    float lut[256];
    for (int i = 0; i < 256; ++i) lut[i] = linear(static_cast<float>(i) / 255.0f) * exposure;
    for (size_t i = 0; i < count; ++i) {
        const auto pixel = pixels[i];
        float r = lut[(pixel >> 16) & 255], g = lut[(pixel >> 8) & 255], b = lut[pixel & 255];
        const float y = std::clamp(0.2126f*r + 0.7152f*g + 0.0722f*b, 0.0f, 1.0f);
        const float shadow = (1-y)*(1-y), highlight = y*y;
        const float lift = 0.25f * (p->shadows*shadow + p->highlights*highlight + p->whites*highlight*highlight)
                           + 0.15f * p->blacks*shadow*shadow;
        r = std::max(0.0f, (r + lift - 0.18f)*contrast + 0.18f) * red_gain;
        g = std::max(0.0f, (g + lift - 0.18f)*contrast + 0.18f) * green_gain;
        b = std::max(0.0f, (b + lift - 0.18f)*contrast + 0.18f) * blue_gain;
        const float luma = 0.2126f*r + 0.7152f*g + 0.0722f*b;
        const float maximum = std::max({r,g,b}), minimum = std::min({r,g,b});
        const float chroma = maximum > 0 ? (maximum-minimum)/maximum : 0;
        const float saturation = p->saturation * (1 + p->vibrance*(1-chroma));
        pixels[i] = 0xff000000u | (encoded(luma + saturation*(r-luma)) << 16) |
                    (encoded(luma + saturation*(g-luma)) << 8) | encoded(luma + saturation*(b-luma));
    }
    return 0;
}

int nf_sharpen(const uint32_t* source, uint32_t* destination, int width, int height, float amount) {
    if (!source || !destination || source == destination || width <= 0 || height <= 0 ||
        !std::isfinite(amount) || amount < 0 || amount > 2) return -1;
    // A separable [1,2,1] Gaussian stencil, evaluated directly to avoid a full-image scratch buffer.
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        const size_t index = static_cast<size_t>(y)*width + x;
        if (amount == 0) { destination[index] = source[index]; continue; }
        uint32_t result = 0xff000000u;
        for (int shift : {16, 8, 0}) {
            float blur = 0;
            for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
                const size_t neighbor = static_cast<size_t>(std::clamp(y+dy, 0, height-1))*width + std::clamp(x+dx, 0, width-1);
                blur += static_cast<float>((source[neighbor] >> shift) & 255) * (dy == 0 ? 2 : 1) * (dx == 0 ? 2 : 1) / 16.0f;
            }
            const float center = static_cast<float>((source[index] >> shift) & 255);
            const auto value = static_cast<uint32_t>(std::lround(std::clamp(center + amount*(center-blur), 0.0f, 255.0f)));
            result |= value << shift;
        }
        destination[index] = result;
    }
    return 0;
}

int nf_vignette(uint32_t* pixels, int width, int full_height, int first_row, int rows, float amount) {
    if (!pixels || width <= 0 || full_height <= 0 || first_row < 0 || rows <= 0 ||
        static_cast<int64_t>(first_row)+rows > full_height || !std::isfinite(amount) || amount < -1 || amount > 1) return -1;
    if (amount == 0) return 0;
    for (int y = 0; y < rows; ++y) for (int x = 0; x < width; ++x) {
        const float nx = width == 1 ? 0 : (2.0f*x/(width-1)-1);
        const float ny = full_height == 1 ? 0 : (2.0f*(y+first_row)/(full_height-1)-1);
        const float radius = (nx*nx+ny*ny)*0.5f;
        const float gain = std::exp2(-1.5f*amount*radius*radius);
        const size_t index = static_cast<size_t>(y)*width+x;
        const auto p = pixels[index];
        pixels[index] = 0xff000000u | (encoded(linear(((p >> 16)&255)/255.0f)*gain) << 16) |
                        (encoded(linear(((p >> 8)&255)/255.0f)*gain) << 8) | encoded(linear((p&255)/255.0f)*gain);
    }
    return 0;
}
