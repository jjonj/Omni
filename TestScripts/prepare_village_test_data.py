
import os
from PIL import Image
import numpy as np

layers_dir = r'D:\SSDPROJECTS\OMNI\OmniSync.Web\www\IslandGenerator\ExampleLayers'
output_path = r'D:\SSDPROJECTS\OMNI\TestScripts\village_test_data.bin'

def load_layer(name):
    path = os.path.join(layers_dir, f'T_World_StaticLayer_{name}.png')
    img = Image.open(path).convert('RGB')
    return np.array(img)

print("Loading layers...")
shape = load_layer('Shape')
height_adv = load_layer('HeightAdv')
edge_depth = load_layer('EdgeDepth')
water = load_layer('Water')

# Shape: grayscale, white is land
land = (shape[:,:,0] > 128).astype(np.float32)

# HeightAdv: grayscale
h_adv = (height_adv[:,:,0] / 255.0).astype(np.float32)

# EdgeDepth: grayscale (1 - outline) * 255
outline = (1.0 - (edge_depth[:,:,0] / 255.0)).astype(np.float32)

# Water: composite
# Ocean: r=0, g=50, b=100
# Lake: r=50, g=100, b=255
# River: r=50, g=100, b=255
# Land: r=30, g=35, b=40
is_lake = ((water[:,:,0] == 50) & (water[:,:,1] == 100) & (water[:,:,2] == 255)).astype(np.int32)
# We'll treat lake pixels as id 3
ids = np.zeros_like(is_lake)
ids[is_lake == 1] = 3

# River depth: we can't perfectly reconstruct it, but we know river pixels are r=50, g=100, b=255
# Actually, let's just use the same blue check for rivers
is_river = is_lake # In the composite they look the same
river_depth = np.zeros_like(h_adv)
river_depth[is_river == 1] = 0.5 # Arbitrary value to trigger river check (> 0.02)

print("Writing binary data...")
with open(output_path, 'wb') as f:
    f.write(land.tobytes())
    f.write(h_adv.tobytes())
    f.write(outline.tobytes())
    f.write(ids.astype(np.float32).tobytes()) # Use float32 for consistency in reading
    f.write(river_depth.tobytes())

print(f"Done. Saved to {output_path}")
