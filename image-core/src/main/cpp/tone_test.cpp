// SPDX-License-Identifier: GPL-3.0-only
#include "tone.h"
#include <cassert>
#include <cmath>
#include <limits>
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
}
