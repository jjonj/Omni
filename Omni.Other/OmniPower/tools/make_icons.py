"""Generates the OmniSleep and OmniShutdown icons.

Writes icon.svg (editable source) and icon.ico (embedded into the exe at build time) for each launcher.
Both icons are drawn on a dark rounded square: yellow crescent moon for sleep, red power symbol for shutdown.
The SVG and the drawing below use the same geometry, so change both together.

Run: python tools/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SIZES = [16, 32, 48, 64, 128, 256]
SS = 4  # supersampling factor, drawn at 1024 and downscaled for smooth edges
CANVAS = 256
BACKGROUND = "#1E1E1E"
YELLOW = "#FFC83D"
RED = "#E53935"

SLEEP_SVG = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}">
  <rect x="8" y="8" width="240" height="240" rx="48" fill="{BACKGROUND}"/>
  <path d="M150 52 A84 84 0 1 0 204 166 A70 70 0 0 1 150 52 Z" fill="{YELLOW}"/>
</svg>
"""

SHUTDOWN_SVG = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}">
  <rect x="8" y="8" width="240" height="240" rx="48" fill="{BACKGROUND}"/>
  <path d="M183.2 81.7 A72 72 0 1 1 72.8 81.7" fill="none" stroke="{RED}" stroke-width="18" stroke-linecap="round"/>
  <line x1="128" y1="40" x2="128" y2="128" stroke="{RED}" stroke-width="18" stroke-linecap="round"/>
</svg>
"""


def new_canvas():
    size = CANVAS * SS
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    radius = 48 * SS
    draw.rounded_rectangle([8 * SS, 8 * SS, 248 * SS, 248 * SS], radius=radius, fill=BACKGROUND)
    return image, draw


def scaled(points):
    return [(x * SS, y * SS) for x, y in points]


def draw_sleep():
    image, draw = new_canvas()
    # Crescent = big yellow disc minus an offset disc of the background colour, both drawn on a layer.
    layer = Image.new("L", image.size, 0)
    layer_draw = ImageDraw.Draw(layer)
    cx, cy, r = 128 * SS, 128 * SS, 84 * SS
    layer_draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    ox, oy, orad = 180 * SS, 100 * SS, 70 * SS
    layer_draw.ellipse([ox - orad, oy - orad, ox + orad, oy + orad], fill=0)
    yellow = Image.new("RGBA", image.size, YELLOW)
    image.paste(yellow, (0, 0), layer)
    return image


def draw_shutdown():
    image, draw = new_canvas()
    width = 18 * SS
    centre = 128 * SS
    radius = 72 * SS
    # Pillow draws the stroke inside the bounding box, so the box is widened by half the stroke to centre it on radius 72.
    outer = radius + width / 2
    # Arc with the gap at the top: from -40 to 220 degrees, clockwise (PIL angles start at 3 o'clock).
    draw.arc(
        [centre - outer, centre - outer, centre + outer, centre + outer],
        start=-40, end=220, fill=RED, width=width,
    )
    for cx, cy in [(183.2, 81.7), (72.8, 81.7)]:
        r = width / 2
        draw.ellipse([cx * SS - r, cy * SS - r, cx * SS + r, cy * SS + r], fill=RED)
    draw.line(scaled([(128, 40), (128, 128)]), fill=RED, width=width)
    for cx, cy in [(128, 40), (128, 128)]:
        r = width / 2
        draw.ellipse([cx * SS - r, cy * SS - r, cx * SS + r, cy * SS + r], fill=RED)
    return image


def write_icon(image, path):
    small = image.resize((CANVAS, CANVAS), Image.LANCZOS)
    small.save(path, format="ICO", sizes=[(s, s) for s in SIZES])


def main():
    targets = {
        "OmniSleep": (SLEEP_SVG, draw_sleep),
        "OmniShutdown": (SHUTDOWN_SVG, draw_shutdown),
    }
    for name, (svg, draw) in targets.items():
        folder = ROOT / "src" / name
        (folder / "icon.svg").write_text(svg, encoding="utf-8")
        write_icon(draw(), folder / "icon.ico")
        print(f"wrote {folder / 'icon.ico'}")


if __name__ == "__main__":
    main()
