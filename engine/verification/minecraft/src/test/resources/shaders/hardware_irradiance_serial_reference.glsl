// Frozen September 30 serial compute loop; production ray/material helpers are shared.
void main() {
    int invocation=int(gl_GlobalInvocationID.x);
    if(hardwareIrradiance.meta.y<=0 || invocation>=hardwareIrradiance.meta.w
        || hardwareIrradiance.meta.x<=0) return;
    int index=(hardwareIrradiance.meta.z+invocation)%hardwareIrradiance.meta.x;
    if(index<0 || index>=hardwareIrradiance.probes.length()) return;
    HardwareIrradianceProbe probe=hardwareIrradiance.probes[index];
    if(probe.position.w<0.0) return;
    vec4 hitR=vec4(0),hitG=vec4(0),hitB=vec4(0),skyR=vec4(0),skyG=vec4(0),skyB=vec4(0);
    ivec3 cell=ivec3(floor(probe.position.xyz))+hardwareIrradiance.reference.xyz;
    uint hash=uint(cell.x)*73856093u ^ uint(cell.y)*19349663u ^ uint(cell.z)*83492791u;
    float rotation=float(hash&65535u)*(6.28318530718/65536.0);
    int samples=clamp(hardwareIrradiance.dimensions.w,8,32);
    float ambientVisibility=0.0;
    for(int sampleIndex=0;sampleIndex<32;++sampleIndex) {
        if(sampleIndex>=samples) break;
        float y=1.0-2.0*(float(sampleIndex)+.5)/float(samples);
        float radial=sqrt(max(0.0,1.0-y*y)),angle=float(sampleIndex)*2.39996322973+rotation;
        vec3 ray=vec3(radial*cos(angle),y,radial*sin(angle));
        vec4 basis=vec4(1.0,ray*3.0)/float(samples);
        RtHit hit;
        if(rtClosest(probe.position.xyz,ray,96.0,1,hit)) {
            ambientVisibility+=smoothstep(0.0,2.0,hit.distance);
            vec3 radiance=clamp(rtHitRadiance(hit),vec3(0),vec3(32));
            hitR+=basis*radiance.r; hitG+=basis*radiance.g; hitB+=basis*radiance.b;
        } else {
            ambientVisibility+=1.0;
            vec3 radiance=pbrEnvironment(ray,1.0,1.0);
            skyR+=basis*radiance.r; skyG+=basis*radiance.g; skyB+=basis*radiance.b;
        }
    }
    probe.position.w=1.0+ambientVisibility/float(samples);
    probe.hitR=hitR; probe.hitG=hitG; probe.hitB=hitB;
    probe.skyR=skyR; probe.skyG=skyG; probe.skyB=skyB;
    hardwareIrradiance.probes[index]=probe;
}
