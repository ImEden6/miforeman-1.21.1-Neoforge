#!/usr/bin/env python3
"""
Composites a static mockup of ClipboardScreen's Review Plan step (the main
9-slice panel, clip accent, GraphCanvas, and DetailCard) using the *exact*
layout math from ClipboardScreen.buildStepReviewPlan/render, so texture
changes can be eyeballed without launching Minecraft.

Blockbench won't help here: Minecraft's GUI 9-slice blit (fixed corners,
tiled edges/center) isn't something a 3D model viewer replicates -- this
script ports NineSliceTexture.blit's exact pixel math to PIL instead.

Usage:
    python tools/preview_clipboard_layout.py [output.png]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

TEX_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/miforeman/textures/gui"
SCALE = 4  # nearest-neighbor upscale so it's readable

# ---- constants copied from ClipboardScreen.java -------------------------
MAIN_TEX_SIZE = 64
MAIN_BORDER = 6
MIN_GUI_WIDTH = 440
MIN_GUI_HEIGHT = 230
PADDING = 8
CLIP_WIDTH, CLIP_HEIGHT, CLIP_OVERHANG = 16, 32, 6
NODE_CANVAS_BORDER, NODE_CANVAS_TEX_SIZE = 4, 48
SIDE_PANEL_BORDER, SIDE_PANEL_TEX_SIZE = 4, 48

COLOR_TITLE = (218, 165, 32)
COLOR_BORDER = (107, 80, 48)
COLOR_TEXT = (58, 42, 24)
COLOR_MUTED = (138, 122, 104)
NODE_FILL = (255, 248, 220, 221)


def nine_slice_blit(dst, tex, x, y, w, h, border, tex_size):
    """Direct port of NineSliceTexture.blit's pixel math (nearest-neighbor,
    same tiling behavior as GuiGraphics.blit's repeated-stamp loop)."""
    inner_tex = tex_size - border * 2
    inner_w, inner_h = w - border * 2, h - border * 2

    def stamp(sx, sy, sw, sh, dx, dy):
        region = tex.resize((sw, sh), Image.NEAREST) if tex.crop((sx, sy, sx + sw, sy + sh)).size != (sw, sh) else tex.crop((sx, sy, sx + sw, sy + sh))
        dst.alpha_composite(region, (dx, dy))

    def crop(sx, sy, sw, sh):
        return tex.crop((sx, sy, sx + sw, sy + sh))

    # corners
    dst.alpha_composite(crop(0, 0, border, border), (x, y))
    dst.alpha_composite(crop(tex_size - border, 0, border, border), (x + w - border, y))
    dst.alpha_composite(crop(0, tex_size - border, border, border), (x, y + h - border))
    dst.alpha_composite(crop(tex_size - border, tex_size - border, border, border), (x + w - border, y + h - border))

    def stretch_tile(dx, dy, dw, dh, u, v, uw, vh):
        ty = 0
        while ty < dh:
            drawh = min(vh, dh - ty)
            tx = 0
            while tx < dw:
                draww = min(uw, dw - tx)
                dst.alpha_composite(crop(u, v, draww, drawh), (dx + tx, dy + ty))
                tx += uw
            ty += vh

    stretch_tile(x + border, y, inner_w, border, border, 0, inner_tex, border)
    stretch_tile(x + border, y + h - border, inner_w, border, border, tex_size - border, inner_tex, border)
    stretch_tile(x, y + border, border, inner_h, 0, border, border, inner_tex)
    stretch_tile(x + w - border, y + border, border, inner_h, tex_size - border, border, border, inner_tex)
    stretch_tile(x + border, y + border, inner_w, inner_h, border, border, inner_tex, inner_tex)


def mock_node(dst, x, y, w, h, label, warn=False):
    box = Image.new("RGBA", (w, h), NODE_FILL)
    d = ImageDraw.Draw(box)
    d.rectangle([0, 0, w - 1, h - 1], outline=(*COLOR_BORDER, 255))
    dst.alpha_composite(box, (x, y))
    d2 = ImageDraw.Draw(dst)
    text = ("!! " if warn else "") + label
    d2.text((x + 3, y + 3), text, fill=COLOR_TEXT)


def main():
    out_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("clipboard_preview.png")

    tex_main = Image.open(TEX_DIR / "clipboard_main.png").convert("RGBA")
    tex_clip = Image.open(TEX_DIR / "clipboard_clip.png").convert("RGBA")
    tex_canvas = Image.open(TEX_DIR / "clipboard_node_canvas.png").convert("RGBA")
    tex_panel = Image.open(TEX_DIR / "clipboard_side_panel.png").convert("RGBA")

    gui_w, gui_h = MIN_GUI_WIDTH, MIN_GUI_HEIGHT
    margin = 20
    canvas_w, canvas_h = gui_w + margin * 2, gui_h + margin * 2
    scene = Image.new("RGBA", (canvas_w, canvas_h), (30, 30, 34, 255))

    left, top = margin, margin
    nine_slice_blit(scene, tex_main, left, top, gui_w, gui_h, MAIN_BORDER, MAIN_TEX_SIZE)

    clip_x = left - CLIP_OVERHANG
    clip_y = top + (gui_h - CLIP_HEIGHT) // 2
    scene.alpha_composite(tex_clip, (clip_x, clip_y))

    d = ImageDraw.Draw(scene)
    d.text((left + PADDING + MAIN_BORDER + 2, top + PADDING + MAIN_BORDER), "Factory Plan", fill=COLOR_TITLE)

    content_x = left + PADDING + MAIN_BORDER + 2
    content_y = top + PADDING + MAIN_BORDER + 18
    content_w = gui_w - (PADDING + MAIN_BORDER) * 2 - 4
    content_h = gui_h - (PADDING + MAIN_BORDER) * 2 - 40

    detail_w = int(content_w * 0.42)
    canvas_widget_w = content_w - (detail_w + 6)

    gx, gy = content_x, content_y + 12
    gw, gh = canvas_widget_w, content_h
    nine_slice_blit(scene, tex_canvas, gx, gy, gw, gh, NODE_CANVAS_BORDER, NODE_CANVAS_TEX_SIZE)
    mock_node(scene, gx + 20, gy + 20, 90, 24, "Circuit")
    mock_node(scene, gx + 150, gy + 20, 90, 24, "Copper Wire")
    mock_node(scene, gx + 150, gy + 60, 90, 24, "Copper Ingot", warn=True)
    d.line([(gx + 110, gy + 32), (gx + 150, gy + 32)], fill=COLOR_MUTED, width=2)
    d.line([(gx + 195, gy + 44), (gx + 195, gy + 60)], fill=COLOR_MUTED, width=2)

    dx, dy = content_x + canvas_widget_w + 6, content_y + 12
    dw, dh = detail_w, content_h
    nine_slice_blit(scene, tex_panel, dx, dy, dw, dh, SIDE_PANEL_BORDER, SIDE_PANEL_TEX_SIZE)
    d.text((dx + 6, dy + 4), "Copper Wire", fill=(74, 46, 10))
    d.text((dx + 6, dy + 20), "Required Flow Rate:", fill=COLOR_MUTED)
    d.text((dx + 10, dy + 30), "2.00 units / min", fill=COLOR_TEXT)
    d.text((dx + 6, dy + 46), "Produced By:", fill=(46, 125, 50))
    d.text((dx + 10, dy + 56), "- Copper Ingot", fill=COLOR_TEXT)

    big = scene.resize((canvas_w * SCALE, canvas_h * SCALE), Image.NEAREST)
    big.save(out_path)
    print(f"Wrote {out_path} ({big.size[0]}x{big.size[1]})")


if __name__ == "__main__":
    main()
