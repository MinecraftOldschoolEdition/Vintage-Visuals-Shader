// Frozen map wrapping before the exact power-of-two mask (2026-09-19).
        layout(std430, set = 3, binding = 7) readonly buffer PbrData {
            // xyz: authored normal/ORM/emission map slot bitmasks; w: enabled.
            ivec4 meta;
            vec4 sky;
            vec4 ground;
            ivec4 originSize;
            PbrMaterial profiles[32];
            uint maps[131040];
            uint voxels[];
        } pbrData;
        bool pbrIsMaterialTag(float tag) { return tag >= 192.0 && tag <= 223.0; }
        int pbrSlot(float tag) { return clamp(int(tag) - 192, 0, 31); }
        vec4 pbrMapTexel(int slot, int map, ivec2 pixel, int level) {
            slot = clamp(slot,0,31);
            map = clamp(map,0,2);
            level = clamp(level,0,5);
            int size = 32 >> level;
            const int offsets[6] = int[6](0,1024,1280,1344,1360,1364);
            pixel = ivec2((pixel.x % size + size) % size, (pixel.y % size + size) % size);
            uint value = pbrData.maps[(slot * 3 + map) * 1365 + offsets[level] + pixel.x + size * pixel.y];
            vec4 decoded = vec4(float(value & 255u),float((value >> 8u) & 255u),
                float((value >> 16u) & 255u),float(value >> 24u)) / 255.0;
            if (map == 2) decoded.rgb = pbrLinear(decoded.rgb);
            return decoded;
        }
        vec4 pbrMapLevel(int slot, int map, vec2 uv, int level) {
            level = clamp(level,0,5);
            // Bound floating inputs before converting them to texel coordinates.
            // NaN-to-integer conversion cannot be repaired by clamping afterwards.
            uv = vec2(isnan(uv.x) || isinf(uv.x) ? 0.0 : fract(uv.x),
                isnan(uv.y) || isinf(uv.y) ? 0.0 : fract(uv.y));
            vec2 p = uv * float(32 >> level) - 0.5;
            ivec2 base = ivec2(floor(p));
            vec2 f = fract(p);
            return mix(mix(pbrMapTexel(slot,map,base,level), pbrMapTexel(slot,map,base+ivec2(1,0),level),f.x),
                mix(pbrMapTexel(slot,map,base+ivec2(0,1),level),pbrMapTexel(slot,map,base+ivec2(1,1),level),f.x),f.y);
        }
        vec4 pbrMap(int slot, int map, vec2 uv, float lod) {
            lod = isnan(lod) ? 0.0 : isinf(lod) ? (lod>0.0 ? 5.0 : 0.0) : clamp(lod,0.0,5.0);
            int level = int(floor(lod));
            vec4 lower = pbrMapLevel(slot,map,uv,level);
            float blend = fract(lod);
            if (blend==0.0 || level==5) return lower;
            return mix(lower,pbrMapLevel(slot,map,uv,level+1),blend);
        }
