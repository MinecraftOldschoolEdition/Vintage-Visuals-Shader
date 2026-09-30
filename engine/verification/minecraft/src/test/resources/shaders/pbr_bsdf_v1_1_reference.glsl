
// The grass tag identifies the whole block in both mesh paths and RT
// geometry. Its bottom and side faces are soil; only its top is turf.
float pbrSurfaceTag(float tag,vec3 normal) {
    return tag==194.0 && normal.y<0.5 ? 193.0 : tag;
}
const float PBR_PI = 3.141592653589793;
struct PbrMaterial {
    vec4 surface;
    vec4 absorption;
    vec4 scattering;
    vec4 emission;
    vec4 detail;
};
PbrMaterial pbrMakeMaterial(vec4 surface, vec4 absorption, vec4 scattering,
                            vec4 emission, vec4 detail) {
    return PbrMaterial(surface, absorption, scattering, emission, detail);
}
vec3 pbrLinear(vec3 color) {
    color = max(color, vec3(0.0));
    return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
               step(vec3(0.04045), color));
}
vec3 pbrSrgb(vec3 color) {
    color = max(color, vec3(0.0));
    return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055,
               step(vec3(0.0031308), color));
}
vec3 pbrFresnel(vec3 f0, float cosine) {
    return f0 + (vec3(1.0) - f0) * pow(1.0 - clamp(cosine, 0.0, 1.0), 5.0);
}
vec3 pbrInterfaceFresnel(vec3 f0, float grazingReflectance, float cosine) {
    return f0 + (vec3(grazingReflectance) - f0)
        * pow(1.0 - clamp(cosine, 0.0, 1.0), 5.0);
}
vec3 pbrBeerLambert(vec3 sigma, float distance) {
    return exp(-max(sigma, vec3(0.0)) * max(distance, 0.0));
}
vec3 pbrF0(vec3 albedo, PbrMaterial material) {
    float ior = max(material.surface.z, 1.0);
    float dielectric = (ior - 1.0) / (ior + 1.0);
    return mix(vec3(dielectric * dielectric), clamp(albedo, 0.0, 1.0),
        clamp(material.surface.y, 0.0, 1.0));
}
vec3 pbrSpecular(vec3 f0, float roughness, float nl, float nv, float nh, float vh) {
    float alpha = max(0.045, roughness) * max(0.045, roughness);
    float a2 = alpha * alpha;
    float denominator = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(PBR_PI * denominator * denominator, 1e-12);
    float lightMask = nl + sqrt(a2 + (1.0 - a2) * nl * nl);
    float viewMask = nv + sqrt(a2 + (1.0 - a2) * nv * nv);
    return pbrFresnel(f0, vh) * distribution / max(lightMask * viewMask, 1e-12);
}
// Oren-Nayar's rough-surface angular response, bounded by the Lambert
// envelope so its lobe cannot spend the specular/transmission budget.
float pbrRoughDiffuse(vec3 n, vec3 v, vec3 l, float roughness, float nv, float nl) {
    float sigma2 = roughness * roughness * 0.64;
    float a = 1.0 - 0.5 * sigma2 / (sigma2 + 0.33);
    float b = 0.45 * sigma2 / (sigma2 + 0.09);
    vec3 vp = v - n * nv;
    vec3 lp = l - n * nl;
    float sinV = length(vp), sinL = length(lp);
    float azimuth = max(dot(vp, lp) / max(sinV * sinL, 1e-7), 0.0);
    float sinAlpha = max(sinV, sinL);
    float tanBeta = min(sinV / max(nv, 1e-5), sinL / max(nl, 1e-5));
    return clamp(a + b * azimuth * sinAlpha * tanBeta, 0.0, 1.0);
}
void pbrEvaluateLobes(vec3 albedo, vec3 n, vec3 v, vec3 l, PbrMaterial material,
                      float thickness, out vec3 diffuse, out vec3 specular, out vec3 transmitted) {
    diffuse = vec3(0.0); specular = vec3(0.0); transmitted = vec3(0.0);
    if (dot(n,n) < 1e-10 || dot(v,v) < 1e-10 || dot(l,l) < 1e-10) return;
    n = normalize(n); v = normalize(v); l = normalize(l);
    if (dot(n,v) < 0.0) n = -n;
    float nv = max(dot(n,v), 0.0), signedNl = dot(n,l), nl = abs(signedNl);
    if (nv < 1e-6 || nl < 1e-6) return;
    albedo = clamp(albedo, 0.0, 1.0);
    float metal = clamp(material.surface.y, 0.0, 1.0);
    float roughness = clamp(material.surface.x, 0.045, 1.0);
    float transmission = clamp(material.scattering.w, 0.0, 1.0);
    float subsurface = clamp(material.surface.w, 0.0, 1.0);
    if (signedNl<=0.0 && transmission<=0.0) return;
    vec3 f0 = pbrF0(albedo, material);
    // An index-matched dielectric has no optical interface. Schlick's
    // usual F90=1 would invent grazing reflection even when F0=0.
    // Preserve conductor Fresnel, including partially metallic blends.
    float grazingReflectance = material.surface.z <= 1.0 ? metal : 1.0;
    // Fresnel at both interfaces, not F(V.H), keeps the dielectric
    // diffuse + specular integral bounded even at grazing angles.
    vec3 budget = (vec3(1.0) - pbrInterfaceFresnel(f0, grazingReflectance, nl))
        * (vec3(1.0) - pbrInterfaceFresnel(f0, grazingReflectance, nv)) * (1.0 - metal);
    // Opaque surfaces with no subsurface lobe never consume the volume
    // term. Avoid three exponentials per light on default stone/metal.
    vec3 attenuation = vec3(1.0);
    if (signedNl<=0.0 || subsurface>0.0) {
        float path = max(thickness, 0.0) * 0.5 * (1.0 / max(nl, 0.15) + 1.0 / max(nv, 0.15));
        attenuation = pbrBeerLambert(material.absorption.rgb, path);
    }
    if (signedNl > 0.0) {
        vec3 halfSum = v + l;
        if (dot(halfSum,halfSum) > 1e-10) {
            vec3 h = normalize(halfSum);
            float vh = max(dot(v,h), 0.0);
            specular = pbrSpecular(vec3(1.0), roughness, nl, nv, max(dot(n,h), 0.0), vh)
                * pbrInterfaceFresnel(f0, grazingReflectance, vh);
        }
        if (metal<1.0 && transmission<1.0) {
            float roughDiffuse = pbrRoughDiffuse(n,v,l,roughness,nv,nl);
            vec3 diffuseColor = albedo * roughDiffuse;
            if (subsurface>0.0) {
                vec3 scattered = clamp(material.scattering.rgb,0.0,1.0) * attenuation;
                diffuseColor = mix(diffuseColor, albedo * scattered, subsurface);
            }
            diffuse = budget * diffuseColor * (1.0 - transmission) / PBR_PI;
        }
    } else {
        // Thin-sheet volume approximation. Thickness and wavelength
        // absorption govern transmission; this is not a spatial BSSRDF.
        float forward = pow(max(dot(-l,v),0.0), 4.0);
        float phase = mix(1.0, 0.65 + 0.35 * forward, subsurface);
        transmitted = budget * clamp(material.scattering.rgb,0.0,1.0)
            * attenuation * transmission * phase / PBR_PI;
    }
}
vec3 pbrEvaluateBsdf(vec3 albedo, vec3 n, vec3 v, vec3 l, PbrMaterial material, float thickness) {
    vec3 diffuse, specular, transmitted;
    pbrEvaluateLobes(albedo,n,v,l,material,thickness,diffuse,specular,transmitted);
    return diffuse + specular + transmitted;
}
vec3 pbrIncidentRadiance(vec3 albedo, vec3 n, vec3 v, vec3 l, PbrMaterial material,
                         float thickness, vec3 incident) {
    incident = max(incident, vec3(0.0));
    if (dot(n,n) < 1e-10 || dot(l,l) < 1e-10 || all(lessThanEqual(incident, vec3(0.0))))
        return vec3(0.0);
    float projectedCosine = abs(dot(normalize(n), normalize(l)));
    return pbrEvaluateBsdf(albedo,n,v,l,material,thickness) * incident * projectedCosine;
}
