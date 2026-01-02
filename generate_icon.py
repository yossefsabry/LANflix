from PIL import Image, ImageDraw

def generate_golden_icon(output_path, size=(512, 512), radius=100):
    try:
        # Create blank image with transparency
        img = Image.new('RGBA', size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        
        # Define colors for gradient (Golden Yellow to Deep Orange)
        full_rect = [(0, 0), size]
        
        # Since PIL doesn't have easy gradients for rounded rects, we'll draw a solid gold rounded rect
        # and overlay a gradient or just use a nice solid gold color "FFC107" (Amber 500) -> "FF9800" (Orange 500)
        # We will simulate a gradient by drawing multiple lines or just a solid rich gold.
        # Let's go with a solid rich Golden-Orange for a clean flat vector look.
        
        gold_color = (255, 193, 7) # Amber
        # Draw the rounded square container
        draw.rounded_rectangle(full_rect, radius=radius, fill=gold_color)
        
        # Draw the Play Symbol (Triangle)
        # Center coordinates
        cx, cy = size[0] // 2, size[1] // 2
        
        # Triangle size
        tri_radius = size[0] * 0.25
        
        # Points for an equilateral triangle pointing right
        # Tip (Right)
        p1 = (cx + tri_radius, cy)
        # Bottom Left
        p2 = (cx - tri_radius * 0.5, cy + tri_radius * 0.866)
        # Top Left
        p3 = (cx - tri_radius * 0.5, cy - tri_radius * 0.866)
        
        # Draw triangle in a darker/lighter shade for contrast
        # Let's use a very light yellow/white or a dark orange?
        # User said "i don't want any black or white". 
        # So let's use a Deep Red-Orange for the symbol to keep it "Gold/Warm".
        symbol_color = (255, 111, 0) # Amber 900
        
        draw.polygon([p1, p2, p3], fill=symbol_color)
        
        # Save
        img.save(output_path, "PNG")
        print(f"Successfully generated iconic gold icon at {output_path}")
        
    except Exception as e:
        print(f"Error generating icon: {e}")

if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1:
        generate_golden_icon(sys.argv[1])
    else:
        print("Usage: python generate_icon.py <output_path>")
