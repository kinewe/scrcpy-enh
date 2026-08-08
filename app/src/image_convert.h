#ifndef SC_IMAGE_CONVERT_H
#define SC_IMAGE_CONVERT_H

#include "common.h"

#include <stddef.h>
#include <stdint.h>

// Convert a BMP image (complete BMP file, e.g. from the Windows clipboard
// "image/bmp" MIME type) to a JPEG image (quality 85).
// On success, *out_data is a newly malloc()'d buffer (to be freed with free())
// and *out_size is its size. On failure, false is returned.
bool
sc_image_bmp_to_jpeg(const uint8_t *bmp_data, size_t bmp_size,
                     uint8_t **out_data, size_t *out_size);

#endif
