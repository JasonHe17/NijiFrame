// SPDX-License-Identifier: GPL-3.0-only
#include "tone.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

// Explicitly selected region; encoded-sRGB bilateral candidate, not semantic skin recognition.
static int soften(const uint32_t* source, uint32_t* destination, const uint8_t* mask,
              int width, int height, int first_row, int rows, int radius, float strength, float protection, bool merge) {
    if (!source || !destination || !mask || source==destination || width<1 || height<1 || rows<1 ||
        static_cast<int64_t>(width)*height>8000000 || first_row<0 || first_row>height-rows ||
        radius<1 || radius>6 || !std::isfinite(strength) || strength<0 || strength>1 ||
        !std::isfinite(protection) || protection<0 || protection>1) return -1;
    float range[256], spatial[13][13];
    for(int d=0;d<256;++d) range[d]=std::exp(-d*d/(2.0f*10.0f*10.0f));
    const float sigma=std::max(1.0f,radius*.65f);
    for(int dy=-radius;dy<=radius;++dy) for(int dx=-radius;dx<=radius;++dx)
        spatial[dy+radius][dx+radius]=std::exp(-(dx*dx+dy*dy)/(2*sigma*sigma));
    for(int row=0;row<rows;++row) for(int x=0;x<width;++x) {
        const int y=first_row+row;
        const size_t out=static_cast<size_t>(row)*width+x;
        const auto center=source[static_cast<size_t>(y)*width+x];
        if(strength==0 || mask[out]==0) {if(!merge) destination[out]=center;continue;}
        const int r=(center>>16)&255,g=(center>>8)&255,b=center&255;
        float sum_r=0,sum_g=0,sum_b=0,total=0;
        int edge=0;
        for(int dy=-radius;dy<=radius;++dy) for(int dx=-radius;dx<=radius;++dx) {
            const int xx=std::clamp(x+dx,0,width-1),yy=std::clamp(y+dy,0,height-1);
            const auto pixel=source[static_cast<size_t>(yy)*width+xx];
            const int rr=(pixel>>16)&255,gg=(pixel>>8)&255,bb=pixel&255;
            const int difference=std::max({std::abs(rr-r),std::abs(gg-g),std::abs(bb-b)});
            if(std::abs(dx)<=1 && std::abs(dy)<=1) edge=std::max(edge,difference);
            const float weight=spatial[dy+radius][dx+radius]*range[difference];
            sum_r+=rr*weight;sum_g+=gg*weight;sum_b+=bb*weight;total+=weight;
        }
        const float amount=strength*(mask[out]/255.0f)*(1.0f-protection*std::min(1.0f,edge/24.0f));
        const auto mix=[&](int c,float sum) {return static_cast<uint32_t>(std::clamp(std::lround(c+amount*(sum/total-c)),0L,255L));};
        const auto previous=merge ? destination[out] : center;
        destination[out]=0xff000000u | (mix((previous>>16)&255,sum_r)<<16) | (mix((previous>>8)&255,sum_g)<<8) | mix(previous&255,sum_b);
    }
    return 0;
}
int nf_soften(const uint32_t* source, uint32_t* destination, const uint8_t* mask,
              int width, int height, int first_row, int rows, int radius, float strength, float protection) {
    return soften(source,destination,mask,width,height,first_row,rows,radius,strength,protection,false);
}
int nf_soften_merge(const uint32_t* source, uint32_t* destination, const uint8_t* mask,
              int width, int height, int first_row, int rows, int radius, float strength, float protection) {
    return soften(source,destination,mask,width,height,first_row,rows,radius,strength,protection,true);
}
