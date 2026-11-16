#version 330

// Разум Божий: экранное искажение восприятия у выбранной жертвы.
// Три слоя в одном проходе: волновое искажение пространства, хроматическое раздвоение (с глитч-сдвигами)
// и остаточные изображения (обратная связь через постоянную цель History).

uniform sampler2D MainSampler;
uniform sampler2D HistorySampler;

layout(std140) uniform MindConfig {
    vec4 P0; // x = интенсивность 0..1, y = время в секундах, z = история кадров действительна (0/1), w = длительность кадра в секундах
    vec4 P1; // множители: x = волны, y = раздвоение, z = глитч, w = следы
};

in vec2 texCoord;

out vec4 fragColor;

float hash11(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 scene(vec2 uv) {
    return texture(MainSampler, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
    float k = clamp(P0.x, 0.0, 1.0);
    float t = P0.y;
    float dt = clamp(P0.w, 0.001, 0.1);

    if (k <= 0.0005) {
        fragColor = vec4(scene(texCoord), 1.0);
        return;
    }

    vec2 size = vec2(textureSize(MainSampler, 0));
    float aspect = size.x / size.y;
    vec2 uv = texCoord;
    vec2 c = uv - 0.5;
    vec2 cAspect = c * vec2(aspect, 1.0);
    float r = length(cAspect);

    // ---- 1. Волновое искажение пространства: прямые линии изгибаются, волны движутся ----
    float waveK = P1.x * pow(k, 1.15);
    float amp = 0.022 * waveK * (0.65 + 0.7 * smoothstep(0.0, 0.7, r)); // центр экрана читается лучше краёв
    vec2 p = uv * vec2(aspect, 1.0);
    vec2 w;
    w.x = sin(p.y * 9.0 + t * 2.1) * 0.6 + sin(p.y * 21.0 - t * 3.3 + p.x * 4.0) * 0.4;
    w.y = cos(p.x * 8.0 - t * 1.7) * 0.6 + cos(p.x * 19.0 + t * 2.9 + p.y * 3.0) * 0.4;
    float swell = sin(r * 14.0 - t * 4.0);
    uv += vec2(w.x / aspect, w.y) * amp + (c / (r + 1.0e-4)) * swell * amp * 0.5;

    // ---- 2. Глитч: с 3 стадии отдельные полосы изображения периодически смещаются ----
    float glitchK = P1.z * smoothstep(0.40, 0.85, k);
    float hit = 0.0;
    if (glitchK > 0.0) {
        float slot = floor(t * 9.0);
        float gate = step(hash11(slot * 1.7), 0.25 + 0.55 * glitchK);
        float rows = mix(16.0, 46.0, hash11(slot + 3.0));
        float rowId = floor(uv.y * rows);
        float rh = hash21(vec2(rowId, slot));
        hit = step(1.0 - 0.08 - 0.30 * glitchK, rh) * gate;
        uv.x += (hash21(vec2(rowId + 7.0, slot)) - 0.5) * 0.14 * glitchK * hit;
    }

    // ---- 3. Хроматическое раздвоение: каналы расходятся, контуры дублируются фиолетовыми копиями ----
    float chromaK = P1.y * pow(k, 0.9);
    float amount = 0.0015 + 0.022 * chromaK;
    vec2 dir = normalize(vec2(cos(t * 0.7), sin(t * 0.9)) * 0.5 + c * 1.2 + vec2(1.0e-3));
    vec2 off = dir * amount * (0.6 + 0.8 * r);
    off.x += hit * 0.02 * glitchK;

    vec3 col = vec3(scene(uv + off).r, scene(uv + off * vec2(-0.4, 0.9)).g, scene(uv - off).b);
    vec3 ghostA = scene(uv + off * 1.8);
    vec3 ghostB = scene(uv - off * 1.8);
    vec3 ghosts = (ghostA * vec3(0.75, 0.35, 1.0) + ghostB * vec3(0.35, 0.55, 1.0)) * 0.5;
    col = mix(col, ghosts + col * 0.35, 0.40 * chromaK);
    col += vec3(0.55, 0.25, 1.0) * length(ghostA - ghostB) * 0.25 * chromaK; // светящаяся кромка раздвоенных контуров
    col = mix(col, col * vec3(1.10, 0.82, 1.20), 0.45 * k);                   // фиолетовый оттенок
    col += vec3(0.5, 0.2, 0.9) * hit * 0.10 * glitchK;                         // глитч окрашен магией, а не белым шумом

    // ---- 4. Остаточные изображения: за движущимися объектами остаются фиолетовые полупрозрачные следы ----
    float trailK = P1.w * (0.12 + 0.88 * smoothstep(0.10, 0.75, k)) * smoothstep(0.02, 0.12, k) * P0.z;
    float decay = min(exp(-dt / (0.03 + 0.10 * k)), 0.80);
    vec3 hist = texture(HistorySampler, texCoord + c * 0.0008 * k).rgb;
    vec3 diff = hist - col; // там, где картинка не менялась, разницы нет, и общая яркость не страдает
    col += diff * decay * trailK;
    col += vec3(0.45, 0.2, 0.85) * abs(diff) * decay * trailK * 0.6;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
