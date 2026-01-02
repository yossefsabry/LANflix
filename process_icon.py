from PIL import Image, ImageDraw

def create_rounded_icon(input_path, output_path, size=(512, 512), radius=100):
    try:
        # Open source image
        img = Image.open(input_path).convert("RGBA")
        
        # Resize to standard icon size with high quality
        img = img.resize(size, Image.Resampling.LANCZOS)
        
        # Create a mask for the rounded squared (Squircle-ish)
        mask = Image.new('L', size, 0)
        draw = ImageDraw.Draw(mask)
        
        # Draw rounded rectangle on mask (white = keep, black = transparent)
        draw.rounded_rectangle([(0, 0), size], radius=radius, fill=255)
        
        # Create a new blank image with transparent background
        output = Image.new('RGBA', size, (0, 0, 0, 0))
        
        # Paste the original image using the mask
        output.paste(img, (0, 0), mask=mask)
        
        # Save
        output.save(output_path, "PNG")
        print(f"Successfully saved rounded transparent icon to {output_path}")
        
    except Exception as e:
        print(f"Error processing icon: {e}")

if __name__ == "__main__":
    # We will assume the generated image path is passed or hardcoded for this one-off task
    # Using the artifact path we expect the agent to know
    import sys
    if len(sys.argv) > 2:
        create_rounded_icon(sys.argv[1], sys.argv[2])
    else:
        print("Usage: python process_icon.py <input_path> <output_path>")
