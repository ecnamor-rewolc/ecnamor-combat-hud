"""
Starsector mod icon generator — ecnamor mods
Requires: pip install Pillow

Usage:
    python gen_icons.py

Output:
    ../../Starsector/mods/ecnamor_psi-1.2.0/graphics/icons/on/icon_psi.png
    ../../Starsector/mods/ecnamor_psi-1.2.0/icon.png
    ../../Starsector/mods/ecnamor_boundary-1.0.0/graphics/icons/on/icon.png
    ../../Starsector/mods/ecnamor_boundary-1.0.0/icon.png
    ../../Starsector/mods/ecnamor_arcs-1.0.0/graphics/icons/on/icon_arcs.png
    ../../Starsector/mods/ecnamor_arcs-1.0.0/icon.png

Colors:
    VANILLA_BLUE   = (70, 200, 255)  #46C8FF — default for ecnamor_psi and ecnamor_arcs
    VANILLA_GREEN  = (70, 255, 110)
    VANILLA_YELLOW = (255, 220, 70)
    RED            = (255, 40, 40)   — used for ecnamor_boundary
"""

from PIL import Image, ImageDraw, ImageFilter
import math, os

SIZE = 256
CX = CY = SIZE // 2

VANILLA_BLUE   = (70, 200, 255)
VANILLA_GREEN  = (70, 255, 110)
VANILLA_YELLOW = (255, 220, 70)
RED            = (255, 40,  40)

BASE   = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "Starsector", "mods"))
SOURCE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Source"))


def dark_bg_circle(img):
    cx = cy = SIZE // 2
    pix = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x - cx, y - cy
            d = math.sqrt(dx * dx + dy * dy)
            if d > cx + 0.5:
                continue
            t = d / cx
            v = int(22 - 14 * t * t)
            v = max(5, min(30, v))
            alpha = 255 if d <= cx - 1.5 else max(0, int(255 * (cx - 0.5 - d)))
            pix[x, y] = (v, v, v + 4, alpha)


def dark_bg_square(img):
    cx = cy = SIZE // 2
    pix = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x - cx, y - cy
            t = math.sqrt(dx * dx + dy * dy) / (cx * math.sqrt(2))
            v = int(22 - 12 * t * t)
            v = max(5, min(28, v))
            pix[x, y] = (v, v, v + 4, 255)


def circle_border(draw, r, color, alpha, width=2):
    for w in range(width):
        rr = r - w
        a = max(0, alpha - w * 30)
        draw.ellipse([CX - rr, CY - rr, CX + rr, CY + rr], outline=color + (a,), width=1)


def nav_arrow(draw, color, cx, cy, size, alpha):
    s = size
    tip   = (cx,      cy - s)
    bl    = (cx - s,  cy + int(s * 0.65))
    br    = (cx + s,  cy + int(s * 0.65))
    notch = (cx,      cy + int(s * 0.05))
    draw.polygon([tip, br, notch, bl], fill=color + (alpha,))


def apply_glow(img, radius=8, strength=0.55):
    glow = img.filter(ImageFilter.GaussianBlur(radius))
    ga = glow.split()[3]
    ga = ga.point(lambda p: int(p * strength))
    glow.putalpha(ga)
    return Image.alpha_composite(glow, img)


def psi_icon():
    """Single thick anti-aliased blue ring — ecnamor_psi (Player Ship Indicator)."""
    RENDER = SIZE * 4
    rcx = rcy = RENDER // 2

    ring_img = Image.new("RGBA", (RENDER, RENDER), (0, 0, 0, 0))
    draw_r = ImageDraw.Draw(ring_img)
    c = VANILLA_BLUE
    outer_r = int(RENDER * 0.42)
    inner_r = int(RENDER * 0.30)
    draw_r.ellipse([rcx - outer_r, rcy - outer_r, rcx + outer_r, rcy + outer_r], fill=c + (228,))
    draw_r.ellipse([rcx - inner_r, rcy - inner_r, rcx + inner_r, rcy + inner_r], fill=(0, 0, 0, 0))

    ring_small = ring_img.resize((SIZE, SIZE), Image.LANCZOS)

    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    dark_bg_circle(img)
    img = Image.alpha_composite(img, ring_small)

    draw = ImageDraw.Draw(img)
    nav_arrow(draw, VANILLA_BLUE, CX, CY + 6, 38, 210)

    return apply_glow(img, radius=6, strength=0.5)


def boundary_icon():
    """Square bg + red frame + diagonal stripes + nav arrow — ecnamor_boundary."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    dark_bg_square(img)
    draw = ImageDraw.Draw(img)

    c = RED
    m = 18

    for w in range(14):
        a = max(0, 235 - w * 14)
        draw.rectangle([m + w, m + w, SIZE - m - w - 1, SIZE - m - w - 1], outline=c + (a,), width=1)

    inner = m + 14
    stripe_alpha = 155
    step = 24
    for d in range(-SIZE, SIZE * 2, step):
        candidates = []
        x = d + inner
        if inner <= x <= SIZE - inner - 1:
            candidates.append((x, inner))
        x = d + (SIZE - inner - 1)
        if inner <= x <= SIZE - inner - 1:
            candidates.append((x, SIZE - inner - 1))
        y = inner - d
        if inner <= y <= SIZE - inner - 1:
            candidates.append((inner, y))
        y = (SIZE - inner - 1) - d
        if inner <= y <= SIZE - inner - 1:
            candidates.append((SIZE - inner - 1, y))
        if len(candidates) >= 2:
            draw.line([candidates[0], candidates[-1]], fill=c + (stripe_alpha,), width=2)

    nav_arrow(draw, c, CX, CY - 8, 44, 235)

    return apply_glow(img)


def arcs_icon():
    """Two diverging arc edges + outer arc curve + facing tick — ecnamor_arcs."""
    RENDER = SIZE * 4
    rcx = rcy = RENDER // 2
    c = VANILLA_BLUE

    overlay = Image.new("RGBA", (RENDER, RENDER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    # Weapon origin: lower portion of icon, looking upward
    ox = rcx
    oy = int(RENDER * 0.72)
    half_arc_deg = 55       # half angular spread (degrees from vertical)
    inner_r = int(RENDER * 0.05)
    outer_r = int(RENDER * 0.55)
    line_w  = int(RENDER * 0.035)

    # Two radial edge lines from origin outward
    for sign in (-1, 1):
        a_rad = math.radians(90 + sign * half_arc_deg)   # 90° = straight up in screen coords (y- in PIL)
        x1 = ox + inner_r * math.cos(a_rad)
        y1 = oy - inner_r * math.sin(a_rad)
        x2 = ox + outer_r * math.cos(a_rad)
        y2 = oy - outer_r * math.sin(a_rad)
        draw.line([(x1, y1), (x2, y2)], fill=c + (210,), width=line_w)

    # Outer arc curve connecting the two edges
    arc_box = [ox - outer_r, oy - outer_r, ox + outer_r, oy + outer_r]
    # PIL arc angles: 0° = 3 o'clock, increasing clockwise
    arc_start = 270 - half_arc_deg
    arc_end   = 270 + half_arc_deg
    for w in range(line_w):
        rr = outer_r - w
        draw.arc([ox - rr, oy - rr, ox + rr, oy + rr],
                 arc_start, arc_end, fill=c + (200,))

    # Facing tick (current weapon direction, brighter, straight up)
    facing_r = int(RENDER * 0.42)
    facing_w = int(RENDER * 0.025)
    draw.line([(ox, oy - inner_r * 2), (ox, oy - facing_r)],
              fill=c + (240,), width=facing_w)

    # Origin dot
    dot_r = int(RENDER * 0.025)
    draw.ellipse([ox - dot_r, oy - dot_r, ox + dot_r, oy + dot_r], fill=c + (240,))

    overlay_small = overlay.resize((SIZE, SIZE), Image.LANCZOS)

    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    dark_bg_circle(img)
    img = Image.alpha_composite(img, overlay_small)

    return apply_glow(img, radius=5, strength=0.5)


def save(icon, mod_folder, icon_name="icon.png", also_source=False):
    icon_dir = os.path.join(BASE, mod_folder, "graphics", "icons", "on")
    os.makedirs(icon_dir, exist_ok=True)
    icon.save(os.path.join(icon_dir, icon_name))
    icon.save(os.path.join(BASE, mod_folder, "icon.png"))
    if also_source:
        src_dir = os.path.join(SOURCE, "graphics", "icons", "on")
        os.makedirs(src_dir, exist_ok=True)
        icon.save(os.path.join(src_dir, icon_name))
        icon.save(os.path.join(SOURCE, "icon.png"))
    print(f"  saved -> {mod_folder}")


def hud_icon():
    """Unified ecnamor Combat HUD icon — circular ring (psi) + crosshair tick (arcs) + bracket marker."""
    RENDER = SIZE * 4
    rcx = rcy = RENDER // 2
    c = VANILLA_BLUE

    overlay = Image.new("RGBA", (RENDER, RENDER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    # Outer ring (psi)
    outer_r = int(RENDER * 0.42)
    inner_r = int(RENDER * 0.34)
    draw.ellipse([rcx - outer_r, rcy - outer_r, rcx + outer_r, rcy + outer_r], fill=c + (215,))
    draw.ellipse([rcx - inner_r, rcy - inner_r, rcx + inner_r, rcy + inner_r], fill=(0, 0, 0, 0))

    # Crosshair tick marks at 4 cardinal directions (radar/HUD feel)
    tick_outer = int(RENDER * 0.30)
    tick_inner = int(RENDER * 0.18)
    tick_w     = int(RENDER * 0.04)
    # vertical
    draw.line([(rcx, rcy - tick_outer), (rcx, rcy - tick_inner)], fill=c + (235,), width=tick_w)
    draw.line([(rcx, rcy + tick_inner), (rcx, rcy + tick_outer)], fill=c + (235,), width=tick_w)
    # horizontal
    draw.line([(rcx - tick_outer, rcy), (rcx - tick_inner, rcy)], fill=c + (235,), width=tick_w)
    draw.line([(rcx + tick_inner, rcy), (rcx + tick_outer, rcy)], fill=c + (235,), width=tick_w)

    # Center dot (ship marker)
    dot_r = int(RENDER * 0.06)
    draw.ellipse([rcx - dot_r, rcy - dot_r, rcx + dot_r, rcy + dot_r], fill=c + (240,))

    overlay_small = overlay.resize((SIZE, SIZE), Image.LANCZOS)

    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    dark_bg_circle(img)
    img = Image.alpha_composite(img, overlay_small)

    return apply_glow(img, radius=6, strength=0.5)


if __name__ == "__main__":
    import json
    with open(os.path.join(SOURCE, "mod_info.json")) as f:
        ver = json.load(f)["version"]
    print("Generating icons...")
    save(psi_icon(),      "ecnamor_psi-1.2.0",                   "icon_psi.png")
    save(boundary_icon(), "ecnamor_boundary-1.0.0",              "icon.png")
    save(arcs_icon(),     "ecnamor_arcs-1.0.0",                  "icon_arcs.png")
    save(hud_icon(),      f"ecnamor_combat_hud-{ver}",           "icon_hud.png", also_source=True)
    print("Done.")
