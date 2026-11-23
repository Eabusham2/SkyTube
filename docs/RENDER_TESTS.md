# Sky screen rendering tests

The cloud mask and the sky video plane both link against Minecraft's own shader stages. The smoke test reads those stages and their GLSL includes from a local Minecraft 1.21.11 client JAR. It runs on Linux using Python 3, EGL and desktop OpenGL 3.3 or newer. Mesa's software renderer works on machines without a physical GPU.

```sh
python3 tests/render/cloud_mask_smoke.py --client-jar /path/to/1.21.11.jar
```

The recorded run used Mesa 25.0.7, llvmpipe with LLVM 19.1.7, and an OpenGL 4.5 core context. Both the masked fragment shader with Minecraft's cloud vertex shader and the SkyTube vertex shader with Minecraft's texture and color fragment shader linked successfully.

The pixel test draws an opaque red cloud over a blue background with depth testing and alpha blending enabled. It reads a center pixel inside the video and a pixel outside it. A depth of 1.0 is the cleared background, and 0.5 is the cloud surface.

| Cloud opacity over video | Center RGB | Center depth | Outside RGB | Outside depth |
| --- | --- | --- | --- | --- |
| 0% | 0, 0, 255 | 1.0 | 255, 0, 0 | 0.5 |
| 50% | 128, 0, 127 | 0.5 | 255, 0, 0 | 0.5 |
| 100% | 255, 0, 0 | 0.5 | 255, 0, 0 | 0.5 |

The Java geometry tests compare frustum clipping with 1,000 seeded analytic rectangle intersections and classify 4,000 seeded points against the exact silhouette of a tilted video. Additional cases cover both corner orderings, every viewport edge, geometry crossing the camera and near plane, and a rectangle seen edge-on. Run them with `./gradlew test`.

The EGL test uses a controlled framebuffer. Checking resource packs, third-party rendering mods and the appearance of actual terrain or entity models requires an in-game session. Visibility sampling uses Minecraft's block visual shapes and interpolated entity bounding boxes, so partially transparent geometry and irregular entity silhouettes can differ from the sampled estimate.
