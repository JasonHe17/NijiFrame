// SPDX-License-Identifier: GPL-3.0-only
#include "tone.h"
#include <cassert>
#include <cmath>
#include <limits>
#include <vector>
#include <algorithm>
int main() {
    uint32_t pixels[] = {0xff000000, 0xff808080, 0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff};
    assert(nf_tone(pixels, 6, 0, 1) == 0 && pixels[1] == 0xff808080);
    assert(nf_tone(pixels, 6, 1, 0) == 0);
    assert(pixels[0] == 0xff000000 && pixels[5] == 0xffffffff);
    // Linear-light +1 EV maps 128 to approximately 176, not 255.
    assert(std::abs(static_cast<int>(pixels[1] & 255) - 176) <= 1);
    for (auto p : pixels) assert(((p >> 16) & 255) == (p & 255) && ((p >> 8) & 255) == (p & 255));
    assert(nf_tone(nullptr, 1, 0, 1) == -1);
    assert(nf_tone(pixels, 6, std::numeric_limits<float>::quiet_NaN(), 1) == -1);
    assert(nf_tone(pixels, 6, 0, 3) == -1);
    nf_adjustments controls{0,1,0,0,0,0,0,0,0,0};
    uint32_t neutral[] = {0xff234567, 0xffaabbcc};
    assert(nf_basic_tone(neutral, 2, &controls) == 0 && neutral[0] == 0xff234567 && neutral[1] == 0xffaabbcc);
    controls.warmth = 1;
    uint32_t gray = 0xff808080;
    assert(nf_basic_tone(&gray, 1, &controls) == 0 && ((gray>>16)&255) > (gray&255));
    controls.saturation = 0;
    assert(nf_basic_tone(&gray, 1, &controls) == 0 && ((gray>>16)&255) == (gray&255));
    controls.tint = std::numeric_limits<float>::infinity();
    assert(nf_basic_tone(&gray, 1, &controls) == -1);
    std::vector<uint32_t> constant(81,0xff808080), sharp(81), vignette(81,0xff808080);
    assert(nf_sharpen(constant.data(),sharp.data(),9,9,2) == 0 && constant == sharp);
    constant[40] = 0xff909090;
    assert(nf_sharpen(constant.data(),sharp.data(),9,9,1) == 0 && (sharp[40]&255) > 144);
    assert(nf_sharpen(constant.data(),constant.data(),9,9,1) == -1);
    assert(nf_vignette(vignette.data(),9,9,0,9,1) == 0);
    assert(vignette[40] == 0xff808080 && (vignette[0]&255) < 128 && vignette[0] == vignette[80]);
    std::vector<uint32_t> chunks(81,0xff808080);
    assert(nf_vignette(chunks.data(),9,9,0,4,1) == 0);
    assert(nf_vignette(chunks.data()+36,9,9,4,5,1) == 0);
    assert(chunks == vignette);
    assert(nf_vignette(chunks.data(),9,9,8,2,1) == -1);
    // Local exposure leaves zero coverage untouched and uses linear-light gain at full coverage.
    uint32_t local[] = {0xff808080u,0xff808080u,0xff808080u};
    const uint8_t mask[] = {0,128,255};
    assert(nf_local_exposure(local,mask,3,1.0f)==0);
    assert(local[0]==0xff808080u);
    assert((local[1]&255)>128 && (local[1]&255)<(local[2]&255));
    uint32_t full=0xff808080u;assert(nf_tone(&full,1,1.0f,1.0f)==0);assert(local[2]==full);
    const uint32_t unchanged=local[1];assert(nf_local_exposure(local,mask,3,0.0f)==0);assert(local[1]==unchanged);
    assert(nf_local_exposure(local,nullptr,3,1.0f)==-1);
    assert(nf_local_exposure(local,mask,3,4.0f)==-1);
}
