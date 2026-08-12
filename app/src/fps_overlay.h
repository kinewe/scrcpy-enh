#ifndef SC_FPS_OVERLAY_H
#define SC_FPS_OVERLAY_H

#include <SDL3/SDL.h>
#include <stdbool.h>
#include <stdint.h>

struct sc_fps_overlay {
    SDL_Renderer *renderer;
    SDL_Texture *texture;      // current text texture (e.g. "60fps")
    int tex_w;                 // texture logical width
    int tex_h;                 // texture logical height
    unsigned frame_count;      // rendered frames in the 500ms window
    uint64_t last_tick;        // last update time (sc_tick)
    int fps;                   // currently displayed fps
};

bool sc_fps_overlay_init(struct sc_fps_overlay *overlay, SDL_Renderer *renderer);
void sc_fps_overlay_destroy(struct sc_fps_overlay *overlay);
// call once per rendered frame (in sc_screen_apply_frame)
void sc_fps_overlay_on_frame(struct sc_fps_overlay *overlay);
// call before present (at the end of sc_screen_render)
void sc_fps_overlay_draw(struct sc_fps_overlay *overlay, SDL_Renderer *renderer);

#endif
