#ifndef SC_FPS_OVERLAY_H
#define SC_FPS_OVERLAY_H

#include <SDL3/SDL.h>
#include <stdbool.h>
#include <stdint.h>

enum sc_overlay_mode {
    SC_OVERLAY_MODE_USB,
    SC_OVERLAY_MODE_WIFI,
};

struct sc_fps_overlay {
    SDL_Renderer *renderer;
    SDL_Texture *texture;      // current text texture (e.g. "60fps 15M USB")
    int tex_w;                 // texture logical width
    int tex_h;                 // texture logical height
    unsigned frame_count;      // rendered frames in the 500ms window
    uint64_t last_tick;        // last update time (sc_tick)
    int fps;                   // currently displayed fps
    int bitrate;               // current ABR bitrate (server-reported)
    bool abr_dirty;            // bitrate or mode changed, re-render needed
    enum sc_overlay_mode mode; // connection mode: USB or WIFI
};

bool sc_fps_overlay_init(struct sc_fps_overlay *overlay, SDL_Renderer *renderer);
void sc_fps_overlay_destroy(struct sc_fps_overlay *overlay);
// call once per rendered frame (in sc_screen_apply_frame)
void sc_fps_overlay_on_frame(struct sc_fps_overlay *overlay);
// call before present (at the end of sc_screen_render)
void sc_fps_overlay_draw(struct sc_fps_overlay *overlay, SDL_Renderer *renderer);
// set the connection mode shown in the overlay (USB / WIFI); marks dirty
void sc_fps_overlay_set_mode(struct sc_fps_overlay *overlay,
                             enum sc_overlay_mode mode);
// update the ABR bitrate from a server ABR state message; marks dirty
// (the fps parameter is stored for future use, not displayed)
void sc_fps_overlay_update_abr(struct sc_fps_overlay *overlay, int bitrate,
                               int fps);

#endif
