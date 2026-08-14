// Sodium compact chunk vertex decoding with an additional Colorful Lighting value.
vec3 _vert_position;
vec2 _vert_tex_diffuse_coord;
vec2 _vert_tex_diffuse_coord_bias;
vec2 _vert_tex_light_coord;
vec4 _vert_color;
uvec4 _vert_colorful_light;
uint _draw_id;
uint _material_params;

#ifdef USE_VERTEX_COMPRESSION
const uint POSITION_BITS = 20u;
const uint POSITION_MAX_COORD = 1u << POSITION_BITS;
const uint TEXTURE_BITS = 15u;
const uint TEXTURE_MAX_COORD = 1u << TEXTURE_BITS;
const uint TEXTURE_MAX_VALUE = TEXTURE_MAX_COORD - 1u;
const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);
const float VERTEX_OFFSET = -8.0;

in uvec2 a_Position;
in vec4 a_Color;
in uvec2 a_TexCoord;
in uvec4 a_LightAndData;
layout(location = 15) in uvec4 a_ColorfulLight;

uvec3 _deinterleave_u20x3(uvec2 data) {
    uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    return (hi << 10u) | lo;
}

vec2 _get_texcoord() {
    return vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);
}

vec2 _get_texcoord_bias() {
    return mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> TEXTURE_BITS));
}

void _vert_init() {
    _vert_position = (_deinterleave_u20x3(a_Position) * VERTEX_SCALE) + VERTEX_OFFSET;
    _vert_color = a_Color;
    _vert_tex_diffuse_coord = _get_texcoord();
    _vert_tex_diffuse_coord_bias = _get_texcoord_bias();
    _vert_tex_light_coord = vec2(a_LightAndData.xy) / vec2(256.0);
    _vert_colorful_light = a_ColorfulLight;
    _material_params = a_LightAndData[2];
    _draw_id = a_LightAndData[3];
}
#else
#error "Vertex compression must be enabled"
#endif
