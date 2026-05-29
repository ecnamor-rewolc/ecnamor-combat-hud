# -*- coding: utf-8 -*-
import os
from PIL import Image, ImageDraw, ImageFilter

def create_hud_icon():
    # 1. Image dimensions (draw at 1024x1024 for clean anti-aliased resizing to 64x64)
    base_size = 1024
    
    # Create the layers
    bg_layer = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    glow_layer = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    fg_layer = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    
    bg_draw = ImageDraw.Draw(bg_layer)
    glow_draw = ImageDraw.Draw(glow_layer)
    fg_draw = ImageDraw.Draw(fg_layer)
    
    # 2. Colors
    DARK_BG = (0, 0, 0, 255)            # Completely solid black background card (fills whole canvas)
    
    # Border (Red - enhanced intensity for a stronger glow/bloom)
    RED_BORDER = (255, 30, 20, 255)
    RED_GLOW = (255, 10, 5, 200)        # Significantly increased opacity for a powerful bloom
    
    # HP Circle (Vanilla Green - enhanced intensity)
    VANILLA_GREEN = (70, 255, 110, 255)
    GREEN_GLOW = (0, 255, 50, 160)      # Increased opacity
    
    # Chevron (Vanilla Blue/White core - enhanced intensity)
    CYAN_BORDER = (70, 200, 255, 255)
    CYAN_GLOW = (0, 160, 255, 160)      # Increased opacity
    WHITE_CORE = (235, 250, 255, 255)
    
    # 3. Draw Dark Background Card (Fills the entire 1024x1024 canvas)
    bg_draw.rectangle([0, 0, 1024, 1024], fill=DARK_BG)
    
    # Note: Diagonal stripes have been completely removed as requested.
    
    # 4. Draw Outer Square Frame (Red - positioned virtually edge-to-edge)
    # Bounding box is slightly inset by 10px so the blur has space to bleed outwards
    # to the very edge of the canvas, creating a perfect emissive bloom effect.
    frame_box = [10, 10, 1014, 1014]
    # Glow pass (thick stroke for a strong bloom effect)
    glow_draw.rectangle(frame_box, outline=RED_GLOW, width=40)
    # Solid pass
    fg_draw.rectangle(frame_box, outline=RED_BORDER, width=20)
    
    # 5. Draw HP Circle (Health Ring - Scaled up further)
    # Radius 410 occupies a huge area inside the frame
    circle_box = [512 - 410, 512 - 410, 512 + 410, 512 + 410]
    # Glow pass
    glow_draw.ellipse(circle_box, outline=GREEN_GLOW, width=32)
    # Solid pass
    fg_draw.ellipse(circle_box, outline=VANILLA_GREEN, width=14)
    
    # 6. Draw Central Movement Chevron (Chevron scaled up further)
    # Chevron coordinates (Tip Y=270, bottoms Y=730, Width 400)
    chevron_pts = [
        (512, 270),       # Top point (tip)
        (312, 730),       # Bottom left
        (512, 600),       # Inner bend point
        (712, 730)        # Bottom right
    ]
    # Inner core coordinates
    inner_chevron_pts = [
        (512, 310),
        (345, 710),
        (512, 590),
        (679, 710)
    ]
    
    # Glow pass
    glow_draw.polygon(chevron_pts, fill=CYAN_GLOW)
    # Solid border
    fg_draw.polygon(chevron_pts, fill=CYAN_BORDER)
    # Solid white core
    fg_draw.polygon(inner_chevron_pts, fill=WHITE_CORE)
    
    # 7. Apply Glow Effect (blur the glow layer with a 14px radius for a rich neon bloom)
    blurred_glow = glow_layer.filter(ImageFilter.GaussianBlur(radius=14))
    
    # Merge layers: base background card -> blurred glow overlay -> solid foreground elements
    final_img = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    final_img.alpha_composite(bg_layer)
    final_img.alpha_composite(blurred_glow)
    final_img.alpha_composite(fg_layer)
    
    # 8. Resize to 64x64 using Lanczos for clean, smooth anti-aliased output
    output_size = 64
    resized_img = final_img.resize((output_size, output_size), Image.Resampling.LANCZOS)
    
    # 9. Save file to mod directory
    output_path = r"Source/graphics/icons/on/icon_hud.png"
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    resized_img.save(output_path, "PNG")
    print(f"Generated new glowing HUD icon with full frame and saved to: {output_path}")

    root_icon_path = r"Source/icon.png"
    resized_img.save(root_icon_path, "PNG")
    print(f"Also saved to mod root: {root_icon_path}")

if __name__ == "__main__":
    create_hud_icon()
