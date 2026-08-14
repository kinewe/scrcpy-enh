#include "fps_overlay.h"

#include <stdio.h>
#include <string.h>

#include "util/log.h"
#include "util/tick.h"

// 5x7 dot-matrix font: one uint8_t per row, 5 bits per row, least
// significant bit on the right. Index: 0-9 are the digits, then
// 10='f', 11='p', 12='s' (also 'S'), 13='M', 14='k', 15='U', 16='B',
// 17='W', 18='I', 19='F', 20=' ' (space), 21='/' (rendered as '·'),
// 22='L', 23='A', 24='N'.
static const uint8_t FONT[25][7] = {
    {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}, // 0
    {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}, // 1
    {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}, // 2
    {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E}, // 3
    {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}, // 4
    {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}, // 5
    {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}, // 6
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}, // 7
    {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}, // 8
    {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}, // 9
    {0x06, 0x09, 0x08, 0x1E, 0x08, 0x08, 0x08}, // f
    {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}, // p
    {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}, // s
    {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}, // M
    {0x08, 0x08, 0x08, 0x08, 0x08, 0x0A, 0x04}, // k
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}, // U
    {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}, // B
    {0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11}, // W
    {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x1F}, // I
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}, // F
    {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, // ' '
    {0x00, 0x00, 0x0E, 0x0E, 0x0E, 0x00, 0x00}, // '·' middle dot (nicer divider)
    {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}, // L
    {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}, // A
    {0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11}, // N
};

#define FONT_SCALE 3
#define FONT_CHAR_W 5
#define FONT_CHAR_H 7
#define FONT_SPACING 2
#define OVERLAY_MARGIN_RIGHT 180
#define OVERLAY_MARGIN_TOP 10
#define OVERLAY_PADDING 6
#define FPS_UPDATE_INTERVAL SC_TICK_FROM_MS(500)

// map a character to its index in FONT, or -1 if unsupported
static int
glyph_of(char c) {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    switch (c) {
        case 'f':
            return 10;
        case 'p':
            return 11;
        case 's':
        case 'S': // same 5x7 glyph as 's', no dedicated capital bitmap
            return 12;
        case 'M':
            return 13;
        case 'k':
            return 14;
        case 'U':
            return 15;
        case 'B':
            return 16;
        case 'W':
            return 17;
        case 'I':
            return 18;
        case 'F':
            return 19;
        case ' ':
            return 20;
        case '/':
            return 21; // rendered as '·'
        case 'L':
            return 22;
        case 'A':
            return 23;
        case 'N':
            return 24;
        default:
            return -1;
    }
}

// render "text" (e.g. "60fps") into a texture, replacing the previous one
static bool
render_text(struct sc_fps_overlay *overlay, const char *text) {
    size_t len = strlen(text);

    size_t gaps = len ? len - 1 : 0;
    int w = (int) (len * FONT_CHAR_W * FONT_SCALE + gaps * FONT_SPACING);
    int h = FONT_CHAR_H * FONT_SCALE;

    SDL_Surface *surface = SDL_CreateSurface(w, h, SDL_PIXELFORMAT_RGBA8888);
    if (!surface) {
        LOGW("Could not create fps overlay surface: %s", SDL_GetError());
        return false;
    }

    // fully transparent background
    bool ok = SDL_FillSurfaceRect(surface, NULL, 0);
    if (!ok) {
        LOGW("Could not clear fps overlay surface: %s", SDL_GetError());
        SDL_DestroySurface(surface);
        return false;
    }

    for (size_t i = 0; i < len; ++i) {
        int glyph = glyph_of(text[i]);
        if (glyph < 0) {
            continue;
        }

        int x0 = (int) i * (FONT_CHAR_W * FONT_SCALE + FONT_SPACING);
        for (int row = 0; row < FONT_CHAR_H; ++row) {
            uint8_t bits = FONT[glyph][row];
            for (int col = 0; col < FONT_CHAR_W; ++col) {
                if (!(bits & (1u << (FONT_CHAR_W - 1 - col)))) {
                    continue;
                }
                // draw a scale x scale white block for this font pixel
                for (int dy = 0; dy < FONT_SCALE; ++dy) {
                    for (int dx = 0; dx < FONT_SCALE; ++dx) {
                        ok = SDL_WriteSurfacePixel(surface,
                                                   x0 + col * FONT_SCALE + dx,
                                                   row * FONT_SCALE + dy,
                                                   255, 255, 255, 255);
                        if (!ok) {
                            LOGW("Could not write fps overlay pixel: %s",
                                 SDL_GetError());
                            SDL_DestroySurface(surface);
                            return false;
                        }
                    }
                }
            }
        }
    }

    SDL_Texture *texture =
        SDL_CreateTextureFromSurface(overlay->renderer, surface);
    SDL_DestroySurface(surface);
    if (!texture) {
        LOGW("Could not create fps overlay texture: %s", SDL_GetError());
        return false;
    }

    if (overlay->texture) {
        SDL_DestroyTexture(overlay->texture);
    }
    overlay->texture = texture;
    overlay->tex_w = w;
    overlay->tex_h = h;
    return true;
}

bool
sc_fps_overlay_init(struct sc_fps_overlay *overlay, SDL_Renderer *renderer) {
    overlay->renderer = renderer;
    overlay->texture = NULL;
    overlay->tex_w = 0;
    overlay->tex_h = 0;
    overlay->frame_count = 0;
    overlay->last_tick = sc_tick_now();
    overlay->fps = 0;
    overlay->bitrate = 0;
    overlay->abr_fps = 0;
    overlay->abr_dirty = false;
    overlay->visible = true;
    overlay->mode = SC_OVERLAY_MODE_USB;
    overlay->pos_x = -1;
    overlay->pos_y = -1;

    return render_text(overlay, "0fps");
}

void
sc_fps_overlay_destroy(struct sc_fps_overlay *overlay) {
    if (overlay->texture) {
        SDL_DestroyTexture(overlay->texture);
        overlay->texture = NULL;
    }
}

void
sc_fps_overlay_get_pos(struct sc_fps_overlay *overlay, int out_w, int *x,
                       int *y) {
    if (overlay->pos_x < 0) {
        *x = out_w - overlay->tex_w - OVERLAY_MARGIN_RIGHT;
        *y = OVERLAY_MARGIN_TOP;
    } else {
        *x = overlay->pos_x;
        *y = overlay->pos_y;
    }
}

void
sc_fps_overlay_set_pos(struct sc_fps_overlay *overlay, int x, int y) {
    overlay->pos_x = x;
    overlay->pos_y = y;
}

bool
sc_fps_overlay_is_visible(const struct sc_fps_overlay *overlay) {
    return overlay->visible;
}

void
sc_fps_overlay_set_visible(struct sc_fps_overlay *overlay, bool visible) {
    overlay->visible = visible;
}

void
sc_fps_overlay_set_mode(struct sc_fps_overlay *overlay,
                        enum sc_overlay_mode mode) {
    if (overlay->mode == mode) {
        return;
    }
    overlay->mode = mode;
    overlay->abr_dirty = true;
}

void
sc_fps_overlay_update_abr(struct sc_fps_overlay *overlay, int bitrate,
                          int fps) {
    if (overlay->bitrate == bitrate && overlay->abr_fps == fps) {
        return;
    }
    overlay->bitrate = bitrate;
    overlay->abr_fps = fps;
    overlay->abr_dirty = true;
}

void
sc_fps_overlay_on_frame(struct sc_fps_overlay *overlay) {
    if (!overlay->texture) {
        return;
    }

    ++overlay->frame_count;

    sc_tick now = sc_tick_now();
    if ((uint64_t) now - overlay->last_tick < (uint64_t) FPS_UPDATE_INTERVAL) {
        return;
    }

    // 500ms window: convert the frame count to a per-second value
    int fps = overlay->frame_count * 2;
    overlay->frame_count = 0;
    overlay->last_tick = now;

    if (fps == overlay->fps && !overlay->abr_dirty) {
        return;
    }
    overlay->fps = fps;
    overlay->abr_dirty = false;

    // "<fps>fps[/<abr_fps>] <bitrate><unit> <mode>"
    // e.g. "60/60fps 15M USB", "60fps 15M USB" (before ABR state)
    const char *mode = overlay->mode == SC_OVERLAY_MODE_WIFI ? "LAN" : "USB";
    char text[48];
    if (overlay->bitrate >= 1000000) {
        if (overlay->abr_fps > 0) {
            snprintf(text, sizeof(text), "%d/%dfps %dM %s", fps,
                     overlay->abr_fps, overlay->bitrate / 1000000, mode);
        } else {
            // ABR state not received yet: show actual fps only
            snprintf(text, sizeof(text), "%dfps %dM %s", fps,
                     overlay->bitrate / 1000000, mode);
        }
    } else {
        if (overlay->abr_fps > 0) {
            snprintf(text, sizeof(text), "%d/%dfps %dk %s", fps,
                     overlay->abr_fps, overlay->bitrate / 1000, mode);
        } else {
            // ABR state not received yet: show actual fps only
            snprintf(text, sizeof(text), "%dfps %dk %s", fps,
                     overlay->bitrate / 1000, mode);
        }
    }
    // on failure the previous texture is kept
    render_text(overlay, text);
}

void
sc_fps_overlay_draw(struct sc_fps_overlay *overlay, SDL_Renderer *renderer) {
    if (!overlay->texture) {
        return;
    }

    int out_w;
    int out_h;
    bool ok = SDL_GetRenderOutputSize(renderer, &out_w, &out_h);
    if (!ok) {
        LOGW("Could not get render output size: %s", SDL_GetError());
        return;
    }

    // top-right corner by default, or the user-set position (Alt+drag);
    // semi-transparent black background extended by 6px on each side
    int tex_x;
    int tex_y;
    sc_fps_overlay_get_pos(overlay, out_w, &tex_x, &tex_y);

    SDL_FRect bg_rect = {
        .x = tex_x - OVERLAY_PADDING,
        .y = tex_y - OVERLAY_PADDING,
        .w = overlay->tex_w + 2 * OVERLAY_PADDING,
        .h = overlay->tex_h + 2 * OVERLAY_PADDING,
    };
    // the renderer default draw blend mode is NONE: enable blending for the
    // semi-transparent background
    ok = SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
    if (ok) {
        ok = SDL_SetRenderDrawColor(renderer, 0, 0, 0, 140);
    }
    if (ok) {
        ok = SDL_RenderFillRect(renderer, &bg_rect);
    }
    if (!ok) {
        LOGW("Could not draw fps overlay background: %s", SDL_GetError());
        return;
    }

    SDL_FRect dst_rect = {
        .x = tex_x,
        .y = tex_y,
        .w = overlay->tex_w,
        .h = overlay->tex_h,
    };
    ok = SDL_RenderTexture(renderer, overlay->texture, NULL, &dst_rect);
    if (!ok) {
        LOGW("Could not draw fps overlay: %s", SDL_GetError());
    }
}
