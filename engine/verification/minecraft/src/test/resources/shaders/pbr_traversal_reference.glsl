// Scalar traversal before empty-brick acceleration, retained as a differential oracle.
        vec4 pbrTrace(vec3 position, vec3 normal, vec3 direction, float maxDistance, bool thinReceiver) {
            if (rtActive()) return rtTransmission(position,normal,direction,maxDistance,thinReceiver,effectQuality(3));
            vec3 origin = position + (thinReceiver ? direction : normal) * 0.015;
            vec3 local = origin - vec3(pbrData.originSize.xyz);
            ivec3 cell = ivec3(floor(local));
            ivec3 receiverCell = ivec3(floor(position - normal * 0.015 - vec3(pbrData.originSize.xyz)));
            vec3 stepDirection = sign(direction);
            vec3 inverseDirection = 1.0 / max(abs(direction),vec3(1e-6));
            vec3 boundary = vec3(cell) + step(vec3(0.0),direction);
            vec3 next = abs(boundary - local) * inverseDirection;
            vec3 transmission = vec3(1.0);
            float distance = 0.0, lastSheet = -1.0;
            for (int iteration = 0; iteration < 144; ++iteration) {
                if (any(lessThan(cell,ivec3(0))) || any(greaterThanEqual(cell,ivec3(pbrData.originSize.w)))) break;
                float end = min(maxDistance, min(next.x,min(next.y,next.z)));
                uint voxel = pbrVoxel(cell);
                int tag = int(voxel & 255u);
                bool ownCell = thinReceiver && all(equal(cell,receiverCell));
                if ((voxel & 4096u) != 0u) return vec4(0.0,0.0,0.0,lastSheet);
                if (pbrIsMaterialTag(float(tag)) && voxel != 0u) {
                    PbrMaterial encountered = pbrData.profiles[pbrSlot(float(tag))];
                    if (encountered.scattering.w > 0.0) {
                        lastSheet = end;
                        if (!ownCell) {
                            vec3 beer = pbrBeerLambert(encountered.absorption.rgb,
                                max(end-distance,0.0) * encountered.absorption.w);
                            // A leaf voxel is a cutout canopy cell: half its area
                            // is open, with the rest filtered through the sheet.
                            float coverage = tag == 196 ? 0.5 : 1.0;
                            transmission *= mix(vec3(1.0),beer * encountered.scattering.w,coverage);
                        }
                    } else if (((voxel >> 8u) & 15u) > 0u) {
                        transmission *= exp(-float((voxel >> 8u) & 15u) * max(end-distance,0.0));
                    }
                }
                if (end >= maxDistance || max(transmission.r,max(transmission.g,transmission.b)) < 0.002) break;
                bvec3 crossed = lessThanEqual(next,vec3(end+1e-5));
                cell += ivec3(stepDirection) * ivec3(crossed);
                next += inverseDirection * vec3(crossed);
                distance = end;
            }
            return vec4(transmission,lastSheet);
        }
