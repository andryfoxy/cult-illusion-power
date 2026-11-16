#version 330

// Безымянный: аналоговые помехи (повреждённая видеозапись) у игрока, для которого идёт проявление.
// Слои: вертикальный сбой синхронизации, горизонтальные разрывы строк, блоки-артефакты, расхождение цветовых каналов,
// строчная развёртка, шум, потеря цвета и вспышки яркости.

uniform sampler2D MainSampler;

layout(std140) uniform NamelessConfig {
    vec4 P0; // x = интенсивность 0..1, y = время в секундах, z = зерно текущего всплеска
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
    float seed = P0.z;

    if (k <= 0.0005) {
        fragColor = vec4(scene(texCoord), 1.0);
        return;
    }

    // Быстро меняющийся номер кадра помехи: картинка «рвётся» рывками, а не плавно
    float slot = floor(t * 24.0) + seed * 13.0;
    vec2 uv = texCoord;

    // 1. Сбой вертикальной синхронизации: кадр сдвигается по вертикали
    float rollGate = step(0.80, hash11(slot * 1.31));
    uv.y = fract(uv.y + rollGate * (hash11(slot + 5.0) - 0.5) * 0.30 * k);

    // 2. Горизонтальные разрывы: отдельные строки изображения смещаются вбок
    float rows = mix(10.0, 64.0, hash11(slot + 2.0));
    float rowId = floor(uv.y * rows);
    float tear = step(1.0 - (0.08 + 0.40 * k), hash21(vec2(rowId, slot)));
    uv.x += (hash21(vec2(rowId + 9.0, slot)) - 0.5) * 0.40 * k * tear;

    // 3. Блоки-артефакты сжатия: прямоугольники изображения съезжают
    vec2 cells = vec2(mix(6.0, 22.0, hash11(slot + 8.0)), mix(5.0, 16.0, hash11(slot + 9.0)));
    vec2 blockId = floor(uv * cells);
    float blockGate = step(1.0 - 0.14 * k, hash21(blockId + slot));
    uv += (vec2(hash21(blockId + 3.0 + slot), hash21(blockId + 7.0 + slot)) - 0.5) * 0.14 * k * blockGate;

    // 4. Расхождение цветовых каналов
    float amount = (0.004 + 0.032 * k) * (0.5 + hash11(slot + 11.0));
    vec2 dir = vec2(1.0, (hash11(slot + 12.0) - 0.5) * 0.6);
    vec3 col = vec3(scene(uv + dir * amount).r, scene(uv).g, scene(uv - dir * amount).b);
    col.r += tear * 0.18 * k; // разорванные строки подсвечены, как на плохой ленте

    // 5. Строчная развёртка и шум
    float height = float(textureSize(MainSampler, 0).y);
    float scan = 0.5 + 0.5 * sin(texCoord.y * height * 3.14159265);
    col *= 1.0 - 0.28 * k * scan;
    float noise = hash21(texCoord * vec2(1920.0, 1080.0) + slot);
    float noiseGate = step(0.35, hash11(slot + 20.0));
    col = mix(col, vec3(noise), 0.22 * k * noiseGate);

    // 6. Бегущая полоса помех
    float bandPos = fract(t * 0.9 + seed);
    float band = smoothstep(0.05, 0.0, abs(texCoord.y - bandPos));
    col = mix(col, vec3(hash21(texCoord * 600.0 + slot)), band * 0.55 * k);

    // 7. Потеря цвета и вспышки яркости
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(luma), 0.40 * k);
    col *= 1.0 + (hash11(slot + 30.0) - 0.5) * 0.60 * k;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
