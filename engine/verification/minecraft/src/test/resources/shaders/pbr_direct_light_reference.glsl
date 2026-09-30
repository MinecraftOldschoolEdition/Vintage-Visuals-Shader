// Frozen direct-light selection and PBR caller before evaluation reuse (2026-09-19).

        uvec2 rtPointMaskForCountReference(int count) {
            count=clamp(count,0,64);
            uvec2 all=uvec2((1u<<uint(min(count,31)))-1u,(1u<<uint(clamp(count-32,0,31)))-1u);
            if(count>=32) all.x=0xffffffffu;
            if(count>=64) all.y=0xffffffffu;
            return all;
        }
        uvec2 rtPointCandidateMaskReference(vec3 position,float radiusLimit) {
            uvec2 all=rtPointMaskForCountReference(int(shadowData.emissiveMeta.y+0.5));
            if(shadowData.emissiveGridOriginSize.w!=8 || radiusLimit>24.0) return all;
            int x=int(floor((position.x-float(shadowData.emissiveGridOriginSize.x))/16.0));
            int y=int(floor((position.y-float(shadowData.emissiveGridOriginSize.y))/16.0));
            int z=int(floor((position.z-float(shadowData.emissiveGridOriginSize.z))/16.0));
            if(x<0 || y<0 || z<0 || x>=8 || y>=8 || z>=8) return all;
            int cell=x+8*(z+8*y);
            uvec4 cellMasks=shadowData.emissiveGridMasks[cell>>1];
            return all & ((cell&1)==0 ? cellMasks.xy : cellMasks.zw);
        }
        int rtNextPointIndexReference(inout uvec2 remaining) {
            if(remaining.x!=0u) {
                int index=findLSB(remaining.x); remaining.x &= remaining.x-1u; return index;
            }
            if(remaining.y!=0u) {
                int index=findLSB(remaining.y); remaining.y &= remaining.y-1u; return index+32;
            }
            return -1;
        }
        float rtPointImportanceReference(int index,vec3 position,vec3 normal,bool thin,float radiusLimit,float sunlight) {
            vec4 source=shadowData.emissivePositionRadius[index];
            vec3 delta=source.xyz-position;
            float distance=length(delta),radius=min(source.w,radiusLimit);
            if(distance<0.05 || distance>=radius) return 0.0;
            float cosine=dot(normal,delta/distance);
            if(!thin && cosine<=0.0) return 0.0;
            vec4 energy=shadowData.emissiveColorStrength[index];
            float strength=emissivePointStrength(energy.w,shadowData.emissiveProperties[index].z,sunlight);
            float window=1.0-pow(distance/radius,4.0);
            return shadowData.emissiveLinearColorLuminance[index].w*strength
                *window*window*max(abs(cosine),0.05)/(distance*distance+0.5);
        }
        // Direct lighting is not temporally accumulated. Selecting CDF strata
        // here makes occlusion changes between different lamps appear as hard
        // bands. Stream every locally contributing source once, with unit weight.
        // The spatial mask and eligibility checks bound this to at most 64
        // candidates without per-fragment arrays or probability compensation.
        int rtNextDirectPointReference(inout uvec2 remaining,vec3 position,vec3 normal,
                              bool thin,float radiusLimit,float sunlight) {
            for(int index=rtNextPointIndexReference(remaining);index>=0;index=rtNextPointIndexReference(remaining))
                if(rtPointImportanceReference(index,position,normal,thin,radiusLimit,sunlight)>0.0) return index;
            return -1;
        }
        vec3 rtPointSamplePositionReference(vec3 source,vec3 receiver,int index,int samples) {
            if(samples<=1) return source;
            vec3 axis=normalize(source-receiver);
            vec3 helper=abs(axis.y)<0.95 ? vec3(0,1,0) : vec3(1,0,0);
            vec3 tangent=normalize(cross(helper,axis)),bitangent=cross(axis,tangent);
            float angle=float(index)*2.39996322973;
            float radius=0.2*sqrt((float(index)+0.5)/float(samples));
            return source+(tangent*cos(angle)+bitangent*sin(angle))*radius;
        }

// PBR_POINT_LOOP
            float localSunlight = clamp(shadowData.emissiveMeta.w,0.0,1.0)*clamp(skyLight,0.0,1.0);
            bool hardware = rtActive();
            // Stream local sources exactly once. No selected lamp can stand in
            // for another lamp's visibility or color on an unfiltered receiver.
            uvec2 candidates = rtPointCandidateMaskReference(position,18.0);
            if (!hardware) candidates &= rtPointMaskForCountReference(8<<quality);
            for (int lightIndex=rtNextDirectPointReference(candidates,position,n,thin,18.0,localSunlight);
                 lightIndex>=0;lightIndex=rtNextDirectPointReference(candidates,position,n,thin,18.0,localSunlight)) {
                vec4 source = shadowData.emissivePositionRadius[lightIndex];
                vec3 delta = source.xyz-position;
                float distance = length(delta);
                float radius = min(source.w,18.0);
                if (distance<0.05 || distance>=radius) continue;
                vec3 direction = delta/distance;
                if (!thin && dot(n,direction)<=0.0) continue;
                vec4 properties = shadowData.emissiveProperties[lightIndex];
                vec4 energy = shadowData.emissiveColorStrength[lightIndex];
                float window = 1.0-pow(distance/radius,4.0);
                float strength = emissivePointStrength(energy.w,properties.z,localSunlight)*24.0;
                vec3 incident = shadowData.emissiveLinearColorLuminance[lightIndex].rgb
                    *strength*window*window/(distance*distance+0.5);
                vec4 path = hardware ? vec4(rtPointTransmissionSingle(position,geometricNormal,
                    source.xyz,thin,effectQuality(1)),-1.0)
                    : pbrTrace(position,geometricNormal,direction,max(distance-0.8,0.0),thin);
                incident *= path.rgb;
                radiance += pbrIncidentRadiance(albedo,n,view,direction,m,thickness,incident);
            }
