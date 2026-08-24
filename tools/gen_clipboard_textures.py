#!/usr/bin/env python3
"""
Regenerates the miforeman clipboard GUI textures with a hand-painted-parchment
look modeled on MineColonies' research tree tiles (see
references/minecolonies-1.21.1/.../textures/gui/research/research_button_*.png):
chamfered (cut) corners, mottled parchment fill, a beveled brown border with a
highlight on the top/left and a shadow on the bottom/right, and a soft drop
shadow line under the top edge of the fill.

Usage:
    python tools/gen_clipboard_textures.py

Requires: Pillow (pip install pillow)

All noise is generated as a periodic (sine-sum) field so it tiles seamlessly
when the 9-slice center is stamped repeatedly by ClipboardScreen.blitStretched.
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw

OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/miforeman/textures/gui"

# ---- palette (kept close to ClipboardScreen's existing COLOR_* constants) ----
PARCHMENT_LIGHT = (255, 248, 224)
PARCHMENT_DARK = (224, 206, 168)
BORDER_DARK = (86, 58, 32)
BORDER_HILITE = (168, 122, 74)
BORDER_SHADOW = (78, 54, 32)  # lightened from (54, 36, 20) -- keep the bevel depth cue, drop the near-black edge
FILL_SHADOW_LINE = (150, 118, 78)
METAL_DARK = (78, 78, 88)
METAL_MID = (168, 168, 178)
METAL_LIGHT = (232, 232, 238)
METAL_HILITE = (250, 250, 255)
LEATHER_LIGHT = (146, 96, 54)
LEATHER_DARK = (94, 58, 30)
LEATHER_LIGHT_HOVER = (176, 122, 72)
LEATHER_DARK_HOVER = (118, 76, 42)
BORDER_HILITE_HOVER = (206, 158, 104)
LEATHER_LIGHT_DISABLED = (150, 140, 130)
LEATHER_DARK_DISABLED = (100, 94, 88)
BORDER_HILITE_DISABLED = (120, 108, 96)
BORDER_DARK_DISABLED = (90, 84, 78)
BORDER_SHADOW_DISABLED = (60, 56, 52)


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def seamless_field(w, h, seed, octaves=((3, 2), (5, 4), (9, 7))):
    """Periodic pseudo-noise in [0, 1]; tiles seamlessly by construction
    since every term is an integer number of full sine periods across w/h."""
    rnd = random.Random(seed)
    phases = [(rnd.uniform(0, math.tau), rnd.uniform(0, math.tau)) for _ in octaves]
    field = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            v, total = 0.0, 0.0
            for (fx, fy), (px, py) in zip(octaves, phases):
                amp = 1.0 / fx
                v += amp * math.sin(2 * math.pi * fx * x / w + px) * math.sin(2 * math.pi * fy * y / h + py)
                total += amp
            field[y][x] = (v / total + 1) / 2
    return field


def chamfer_polygon(w, h, cut):
    return [(cut, 0), (w - cut, 0), (w, cut), (w, h - cut),
            (w - cut, h), (cut, h), (0, h - cut), (0, cut)]


def chamfer_mask(w, h, cut):
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).polygon(chamfer_polygon(w, h, cut), fill=255)
    return mask


def paint_parchment(w, h, seed, light=PARCHMENT_LIGHT, dark=PARCHMENT_DARK):
    field = seamless_field(w, h, seed)
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = lerp(light, dark, field[y][x])
    return img


def add_grid_dots(img, mask, spacing, seed, color=(150, 130, 96)):
    """Faint periodic dot grid for the node-graph canvas texture."""
    w, h = img.size
    px = img.load()
    mpx = mask.load()
    rnd = random.Random(seed)
    jitter = {(gx, gy): (rnd.randint(-1, 1), rnd.randint(-1, 1))
              for gy in range(0, h + spacing, spacing) for gx in range(0, w + spacing, spacing)}
    for gy in range(0, h, spacing):
        for gx in range(0, w, spacing):
            jx, jy = jitter[(gx, gy)]
            x, y = gx + jx, gy + jy
            if 0 <= x < w and 0 <= y < h and mpx[x, y] > 0:
                px[x, y] = color


def bevel_panel(w, h, border, chamfer, seed, fill_fn, grid_spacing=None,
                 border_hilite=BORDER_HILITE, border_dark=BORDER_DARK, border_shadow=BORDER_SHADOW):
    """A 9-slice-friendly panel: chamfered brown bevel border around a
    mottled fill.

    IMPORTANT: ClipboardScreen's 9-slice blit tiles the edge strips and the
    center by repeatedly stamping the same border-sized (or center-sized)
    crop across the destination. Anything whose color depends on *both* x
    and y inside a tiled region (a diagonal wash, a single drawn line at a
    fixed y) reappears at every tile repeat and reads as a seam/stripe. So
    every border pixel's color here is a function of exactly one axis - the
    one perpendicular to that edge's tiling direction - and the fill/grid
    dots carry no one-off marks, only the periodic mottle field.
    """
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))

    outer_mask = chamfer_mask(w, h, chamfer)
    inner_mask = chamfer_mask(w - border * 2, h - border * 2, max(0, chamfer - border))

    # border ring: top/bottom strips shade only with y (tiled horizontally),
    # left/right strips shade only with x (tiled vertically) - each strip is
    # therefore identical at every repeat, so no visible seam forms.
    border_layer = Image.new("RGB", (w, h), border_dark)
    bpx = border_layer.load()
    for y in range(h):
        for x in range(w):
            if y < border:
                t = y / max(1, border - 1)
                bpx[x, y] = lerp(border_hilite, border_dark, t)
            elif y >= h - border:
                t = (y - (h - border)) / max(1, border - 1)
                bpx[x, y] = lerp(border_dark, border_shadow, t)
            elif x < border:
                t = x / max(1, border - 1)
                bpx[x, y] = lerp(border_hilite, border_dark, t)
            elif x >= w - border:
                t = (x - (w - border)) / max(1, border - 1)
                bpx[x, y] = lerp(border_dark, border_shadow, t)
            else:
                bpx[x, y] = border_dark
    img.paste(border_layer, (0, 0), outer_mask)

    # fill - pure periodic mottle, no one-off marks (those would repeat on tile)
    fill_img = fill_fn(w - border * 2, h - border * 2, seed)
    if grid_spacing:
        add_grid_dots(fill_img, inner_mask, grid_spacing, seed + 1)
    fill_rgba = fill_img.convert("RGBA")
    fill_rgba.putalpha(inner_mask)
    img.paste(fill_rgba, (border, border), inner_mask)

    return img


def make_clip(w, h):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    cut = 3
    mask = chamfer_mask(w, h, cut)
    metal = Image.new("RGB", (w, h))
    mpx = metal.load()
    for y in range(h):
        for x in range(w):
            # vertical brushed-metal gradient, darker at the extreme edges
            t = abs((y / (h - 1)) - 0.5) * 2
            base = lerp(METAL_HILITE, METAL_MID, t)
            edge = min(x, w - 1 - x) / 4.0
            mpx[x, y] = lerp(METAL_DARK, base, min(1.0, edge))
    img.paste(metal, (0, 0), mask)

    d = ImageDraw.Draw(img)
    d.line([(cut, 1), (w - cut, 1)], fill=(*METAL_HILITE, 200), width=1)
    d.line([(cut, h - 2), (w - cut, h - 2)], fill=(*METAL_DARK, 220), width=1)
    for rx in (w // 2 - 6, w // 2 + 6):
        d.ellipse([rx - 2, h // 2 - 2, rx + 2, h // 2 + 2], fill=(*METAL_DARK, 255))
        d.ellipse([rx - 1, h // 2 - 1, rx, h // 2], fill=(*METAL_HILITE, 255))
    return img


def leather_fill(w, h, seed):
    return paint_parchment(w, h, seed, light=LEATHER_LIGHT, dark=LEATHER_DARK)


def leather_fill_hover(w, h, seed):
    return paint_parchment(w, h, seed, light=LEATHER_LIGHT_HOVER, dark=LEATHER_DARK_HOVER)


def leather_fill_disabled(w, h, seed):
    return paint_parchment(w, h, seed, light=LEATHER_LIGHT_DISABLED, dark=LEATHER_DARK_DISABLED)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    main_tex = bevel_panel(64, 64, border=6, chamfer=3, seed=101, fill_fn=paint_parchment)
    main_tex.save(OUT_DIR / "clipboard_main.png")

    side_panel = bevel_panel(48, 48, border=4, chamfer=2, seed=202, fill_fn=paint_parchment)
    side_panel.save(OUT_DIR / "clipboard_side_panel.png")

    node_canvas = bevel_panel(48, 48, border=4, chamfer=2, seed=303, fill_fn=paint_parchment, grid_spacing=6)
    node_canvas.save(OUT_DIR / "clipboard_node_canvas.png")

    button = bevel_panel(32, 32, border=4, chamfer=2, seed=404, fill_fn=leather_fill)
    button.save(OUT_DIR / "clipboard_button.png")

    # Same seed as the button above -- only the palette shifts, so hover/disabled read as
    # the same button under different lighting rather than a different texture.
    button_hover = bevel_panel(32, 32, border=4, chamfer=2, seed=404,
                                fill_fn=leather_fill_hover, border_hilite=BORDER_HILITE_HOVER)
    button_hover.save(OUT_DIR / "clipboard_button_hover.png")

    button_disabled = bevel_panel(32, 32, border=4, chamfer=2, seed=404,
                                   fill_fn=leather_fill_disabled, border_hilite=BORDER_HILITE_DISABLED,
                                   border_dark=BORDER_DARK_DISABLED, border_shadow=BORDER_SHADOW_DISABLED)
    button_disabled.save(OUT_DIR / "clipboard_button_disabled.png")

    # Built as a horizontal bar (brushed-metal streaks run along the long
    # axis, rivets sit side by side) then rotated so it reads correctly as a
    # vertical clip mounted on the clipboard's left edge, matching
    # ClipboardScreen's CLIP_WIDTH=16/CLIP_HEIGHT=32 landscape layout.
    clip = make_clip(32, 16).transpose(Image.ROTATE_90)
    clip.save(OUT_DIR / "clipboard_clip.png")

    print(f"Wrote 7 textures to {OUT_DIR}")


if __name__ == "__main__":
    main()
