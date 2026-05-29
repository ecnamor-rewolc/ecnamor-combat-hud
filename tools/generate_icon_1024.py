# -*- coding: utf-8 -*-
import os
import datetime
from PIL import Image, ImageDraw, ImageFilter

def create_hud_icon_1024():
    base_size = 1024
    
    # Create the layers
    bg_layer = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    glow_layer = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    fg_layer = Image.new("RGBA", (base_size, base_size), (0, 0, 0, 0))
    
    bg_draw = ImageDraw.Draw(bg_layer)
    glow_draw = ImageDraw.Draw(glow_layer)
    fg_draw = ImageDraw.Draw(fg_layer)
    
    # Colors
    DARK_BG = (0, 0, 0, 255)            # Completely solid black background card (fills whole canvas)
    
    # Border (Red - enhanced intensity for a stronger glow/bloom)
    RED_BORDER = (255, 30, 20, 255)
    RED_GLOW = (255, 10, 5, 200)        # Significant opacity for a powerful bloom
    
    # HP Circle (Vanilla Green - enhanced intensity)
    VANILLA_GREEN = (70, 255, 110, 255)
    GREEN_GLOW = (0, 255, 50, 160)      # Increased opacity
    
    # Chevron (Vanilla Blue/White core - enhanced intensity)
    CYAN_BORDER = (70, 200, 255, 255)
    CYAN_GLOW = (0, 160, 255, 160)      # Increased opacity
    WHITE_CORE = (235, 250, 255, 255)
    
    # 3. Draw Dark Background Card (Fills the entire 1024x1024 canvas)
    bg_draw.rectangle([0, 0, 1024, 1024], fill=DARK_BG)
    
    # 4. Draw Outer Square Frame (Red - positioned virtually edge-to-edge)
    frame_box = [10, 10, 1014, 1014]
    # Glow pass (thick stroke for a strong bloom effect)
    glow_draw.rectangle(frame_box, outline=RED_GLOW, width=40)
    # Solid pass
    fg_draw.rectangle(frame_box, outline=RED_BORDER, width=20)
    
    # 5. Draw HP Circle (Health Ring - Scaled up further)
    circle_box = [512 - 410, 512 - 410, 512 + 410, 512 + 410]
    # Glow pass
    glow_draw.ellipse(circle_box, outline=GREEN_GLOW, width=32)
    # Solid pass
    fg_draw.ellipse(circle_box, outline=VANILLA_GREEN, width=14)
    
    # 6. Draw Central Movement Chevron (Chevron scaled up further)
    chevron_pts = [
        (512, 270),       # Top point (tip)
        (312, 730),       # Bottom left
        (512, 600),       # Inner bend point
        (712, 730)        # Bottom right
    ]
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
    
    # 8. Generate timestamp for unique filename
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    output_filename = f"hud_icon_1024_{timestamp}.png"
    output_path = os.path.join(r"c:\my\starsector-modding", output_filename)
    
    # 9. Save file at full 1024x1024 resolution
    final_img.save(output_path, "PNG")
    print(f"Generated and saved full-resolution 1024x1024 icon to: {output_path}")

if __name__ == "__main__":
    create_hud_icon_1024()
