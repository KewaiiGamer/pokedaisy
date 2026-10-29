// A DS game's 3D on the GPU: melonDS's OpenGL renderer (ported to OpenGL ES 3.2 in our patch) at 2x to 8x the DS's
// resolution, EXPAND's margins drawn with the screen as one picture (pk_nds_wide.h's GPU mode), and our own compositor
// putting the 2D lines over it - the screen's window into the game screen's SurfaceTexture at the 3D's resolution, both
// screens at 1x for everything that reads the picture (the bottom display, thumbnails, EXPAND's edge checks).
//
// The OpenGL context lives on the emu thread (melonDS draws the 3D in the middle of its frame, at line 215) and stays
// current there; frames reach EmulatorView through the SurfaceTexture, so the two contexts share nothing.
#pragma once

#include <cstdint>

struct ANativeWindow;
namespace melonDS { class NDS; }

/** Switches [nds] to the GPU renderer at [scale] texels a DS pixel (2-8), or back to the software renderer ([scale] < 2).
 * Emu thread, between frames. Returns the scale now in effect: 1 = software (asked for, or no OpenGL ES 3.2 here). */
int pk_nds_gl_set_scale(melonDS::NDS* nds, int scale);

/** The scale in effect (1: software). */
int pk_nds_gl_scale();

/** Whether the GPU renderer can run here: -1 not tried yet, 0 no (no OpenGL ES 3.2, or it failed to start), 1 yes. */
int pk_nds_gl_supported();

/** Before [nds] goes: back to the software renderer, everything of ours released. Emu thread. */
void pk_nds_gl_end(melonDS::NDS* nds);

/** Makes the context current on this thread before a frame runs (melonDS draws the 3D during it). Emu thread. */
void pk_nds_gl_begin_frame();

/** The game screen's window to put frames into (EmulatorView's SurfaceTexture), or null; takes the reference. Any
 * thread: picked up before the next frame is put out. */
void pk_nds_gl_set_window(ANativeWindow* window);

/** After a drawn frame: composes it - the top screen's window [x, x + w) x [y, y + h) in DS pixels, EXPAND's margins
 * by its plan - into the window at the 3D's scale, and both screens at 1x into [top] / [bottom] (RGBA, 256 x 192; the
 * copy lags a drawn frame: it's read back while the next one is made). Returns whether the window got the frame. */
bool pk_nds_gl_frame(melonDS::NDS* nds, int x, int y, int w, int h, uint32_t* top, uint32_t* bottom);

/** nds_dump (no window): each frame's window also composed offscreen and read back, for pk_nds_gl_captured - RGBA words,
 * the window's top row first, [w] x [h] texels. Null before a frame was. */
void pk_nds_gl_capture(bool on);
const uint32_t* pk_nds_gl_captured(int* w, int* h);

/** The frame presented at [timestamp] (the SurfaceTexture's): its window x, y, w, h in DS pixels and its size in
 * texels into out[6]: an unknown timestamp gets the newest frame's. False before any frame. Any thread. */
bool pk_nds_gl_frame_info(int64_t timestamp, int32_t out[6]);
