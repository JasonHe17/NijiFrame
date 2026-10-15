#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Optional regeneration of synthetic codec fixtures; normal builds use committed assets.
Requires Pillow==12.3.0 and pillow-heif==1.1.1. No photographs or downloaded media.
"""
from pathlib import Path
from PIL import Image, ImageCms
import pillow_heif
pillow_heif.register_heif_opener()
out=Path(__file__).resolve().parents[1]/'app/src/androidTest/assets/formats'
out.mkdir(parents=True,exist_ok=True)
im=Image.new('RGB',(192,128))
colors=[(180,60,70),(50,160,80),(60,80,180),(170,150,50)]
for y in range(im.height):
    for x in range(im.width):im.putpixel((x,y),colors[(y//64)*2+x//96])
icc=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
for ext,fmt in [('heic','HEIF'),('avif','AVIF'),('webp','WEBP')]:
    for orientation in [1,2,6]:
        exif=Image.Exif();exif[274]=orientation
        im.save(out/f'quadrants-{orientation}.{ext}',format=fmt,quality=95,exif=exif,icc_profile=icc,
                **({"full_range_flag":0} if ext=="heic" else {}))
im.save(out/"full-range.heic",format="HEIF",quality=95,icc_profile=icc,full_range_flag=1)
# Constant colors labeled PQ are rejection fixtures, not real HDR render references.
im.save(out/'pq.heic',format='HEIF',quality=95,color_primaries=9,transfer_characteristics=16,matrix_coefficients=9)
im.save(out/'animated.webp',format='WEBP',save_all=True,append_images=[Image.new('RGB',im.size,(80,80,80))],duration=100,loop=0)
