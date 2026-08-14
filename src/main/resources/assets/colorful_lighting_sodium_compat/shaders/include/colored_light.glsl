vec4 colorful_sample_vanilla_lightmap(sampler2D lightMap, ivec2 uv) {
    vec2 coord = clamp(vec2(uv) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    return texture(lightMap, coord);
}

vec4 colorful_sample_lightmap(sampler2D lightMap, uvec4 packedBytes, vec2 vanillaCoord) {
    uint red8 = packedBytes.r;
    uint green8 = packedBytes.g;
    uint skyAndBlueLow = packedBytes.b;
    uint blueHighAndMarker = packedBytes.a;
    uint marker = blueHighAndMarker >> 4u;
    uint blue8 = ((blueHighAndMarker & 0xFu) << 4u) | (skyAndBlueLow >> 4u);

    if (marker != 0xFu || (red8 | green8 | blue8) == 0u) {
        return texture(lightMap, vanillaCoord);
    }

    uint sky4 = skyAndBlueLow & 0xFu;
    vec3 blockLight = vec3(red8, green8, blue8) / 255.0;
    vec3 sky = colorful_sample_vanilla_lightmap(lightMap, ivec2(0, int(sky4 << 4u))).rgb;
    vec3 block = pow(blockLight, vec3(1.3));
    return vec4(sky + block * max(0.3, 1.0 - sky.r), 1.0);
}
