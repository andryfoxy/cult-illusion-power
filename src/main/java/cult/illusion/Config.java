package cult.illusion;

/** Все настраиваемые параметры способностей. Время указано в тиках (20 тиков = 1 секунда). */
public final class Config {
    private Config() {
    }

    // ---- Общая последовательность призыва ----
    public static final int BELL_COUNT = 2;                 // ударов колокола до особого третьего звука (общий шаблон всех способностей: 2 колокола + особый звук)
    public static final int STRIKE_INTERVAL_TICKS = 50;     // 2,5 секунды между ударами
    public static final float BELL_VOLUME = 1.0F;

    // ---- Тряска экрана ----
    public static final float[] STRIKE_SHAKE = {0.30F, 0.45F, 1.00F}; // пик после каждого из трёх ударов (два колокола и особый звук)
    public static final float SHAKE_FLOOR_RATIO = 0.25F;    // между ударами затухает до этой доли пика, но не до нуля
    public static final float SHAKE_DECAY_PER_TICK = 0.96F;
    public static final int SHAKE_FADE_OUT_TICKS = 30;      // плавное возвращение камеры после призыва
    public static final float SHAKE_MAX_YAW_DEGREES = 2.4F; // при интенсивности 1.0
    public static final float SHAKE_MAX_PITCH_DEGREES = 1.8F;

    // ---- Надпись призыва ----
    public static final int TITLE_FADE_IN_TICKS = 10;
    public static final int TITLE_STAY_TICKS = 50;
    public static final int TITLE_FADE_OUT_TICKS = 20;
    public static final float TITLE_SCALE = 3.0F;
    public static final int TITLE_COLOR_RGB = 0x4B0082;     // основной цвет всех надписей призыва
    public static final int TITLE_GLOW_RGB = 0xE2CCFF;      // светлая обводка, чтобы тёмный текст читался на любом фоне
    public static final String TITLE_POWER = "Да будет воля моя законом";
    public static final String TITLE_MIND = "Вы не отличите истину от лжи";

    // ---- Власть Божья: Божественный приговор ----
    public static final double JUDGMENT_RANGE = 64.0;           // максимальная дальность выбора цели
    public static final double JUDGMENT_CONE_COS = 0.985;       // цель «под прицелом»: примерно 10 градусов
    public static final double JUDGMENT_FALLBACK_DISTANCE = 12.0; // если цели нет, эффект в точке прицела
    public static final int JUDGMENT_BUILDUP_TICKS = 60;        // накопление энергии над целью
    public static final int JUDGMENT_AFTERGLOW_TICKS = 40;      // затухающие фиолетовые эффекты
    public static final int JUDGMENT_IMPACT_DELAY_TICKS = 3;    // пауза между громом и ударом
    public static final double JUDGMENT_BOLT_HEIGHT = 14.0;
    public static final float THUNDER_VOLUME = 4.0F;
    public static final float IMPACT_VOLUME = 3.0F;
    public static final float JUDGMENT_SHAKE = 0.9F;
    public static final int JUDGMENT_SHAKE_TICKS = 25;
    public static final int FLASH_RGB = 0xE8D8FF;

    // Клеймо приговора
    public static final int MARKER_RGB = 0x4B0082;              // основной цвет меток и печати
    public static final int OUTLINE_RGB = 0xB06CFF;             // цвет контура помеченной цели
    public static final int SEAL_FORM_TICKS = 25;               // малая метка превращается в печать
    public static final double SEAL_MAX_RADIUS = 0.55;
    public static final double SEAL_HEIGHT_OFFSET = 0.6;       // печать над головой цели
    public static final int CIRCLE_FORM_TICKS = 15;            // круг под ногами разгорается
    public static final double CIRCLE_RADIUS = 1.7;
    public static final double CIRCLE_SPIN_SLOW = 0.12;        // радиан за тик
    public static final double CIRCLE_SPIN_FAST = 0.60;
    public static final int SEAL_FAST_SPIN_TICKS = 25;         // последние тики перед ударом печать вращается быстро
    // Исполнение приговора
    public static final int JUDGMENT_DIM_TICKS = 12;           // затемнение экрана перед ударом
    public static final float JUDGMENT_DIM_MAX_ALPHA = 0.45F;
    public static final int JUDGMENT_DESCENT_TICKS = 8;        // столб молнии формируется сверху вниз перед ударом
    public static final int RING_WAVE_TICKS = 10;              // кольцо частиц по земле от точки удара
    // Взгляд власти
    public static final int GAZE_INTERVAL_TICKS = 2;           // как часто искать цель под прицелом
    public static final int GAZE_SUPPRESS_TICKS = 40;          // после приговора метка не возвращается сразу
    public static final int GAZE_EDGE_MARGIN = 24;             // отступ указателя от края экрана
    public static final int FLASH_TICKS = 8;
    public static final float FLASH_MAX_ALPHA = 0.75F;

    // ---- Разум Божий: Иллюзорные двойники ----
    public static final int DOUBLE_COUNT = 4;                   // допустимо 3 или 4
    public static final int DOUBLES_DURATION_TICKS = 300;       // 15 секунд
    public static final int DOUBLES_FINISH_TICKS = 60;          // предельная длительность исчезновения (запас)
    public static final int DOUBLE_FADE_IN_TICKS = 20;          // силуэт становится полноценной копией
    public static final float DOUBLE_GHOST_ALPHA_START = 0.25F;
    public static final int DOUBLE_FADE_DELAY_TICKS = 6;        // пауза после остановки до начала растворения
    public static final int DOUBLE_FADE_OUT_TICKS = 18;
    public static final int DOUBLE_VANISH_STAGGER_TICKS = 6;    // копии исчезают по очереди
    public static final double DOUBLE_APPROACH_STOP_DISTANCE = 3.5;
    // Иллюзорные атаки
    public static final int DOUBLE_ATTACK_MIN_TICKS = 60;
    public static final int DOUBLE_ATTACK_RANDOM_TICKS = 70;
    public static final int DOUBLE_ATTACK_SWING_TICKS = 8;
    public static final double DOUBLE_ATTACK_RANGE = 8.0;
    // Ложный образ
    public static final int AFTERIMAGE_SAMPLE_TICKS = 4;        // скорость игрока считается по окнам, чтобы сгладить пакеты движения
    public static final int AFTERIMAGE_LIFETIME_TICKS = 26;
    public static final int AFTERIMAGE_COOLDOWN_TICKS = 12;
    public static final int AFTERIMAGE_MAX = 3;
    public static final double AFTERIMAGE_TURN_COS = 0.5;       // угол смены направления больше 60 градусов
    public static final double AFTERIMAGE_MIN_SPEED = 0.10;     // блоков за тик
    public static final double AFTERIMAGE_JUMP_SPEED = 0.30;    // резкий рывок
    public static final double AFTERIMAGE_TELEPORT_SPEED = 1.5; // телепорт
    public static final float AFTERIMAGE_ALPHA = 0.5F;
    public static final int ORIGINAL_HIDDEN_TICKS = 20;         // оригинал скрыт на короткое время
    public static final int DOUBLE_APPEAR_DELAY_TICKS = 10;
    public static final int DOUBLE_APPEAR_STAGGER_TICKS = 4;
    public static final double DOUBLES_SPAWN_RADIUS = 2.5;
    public static final double DOUBLES_MAX_RADIUS = 8.0;        // дальше этого от точки призыва копии разворачиваются
    public static final double DOUBLE_SPEED_FAST = 0.20;        // блоков за тик
    public static final double DOUBLE_SPEED_SLOW = 0.12;
    public static final double DOUBLE_TURN_DEGREES_PER_TICK = 8.0;
    public static final int DOUBLE_INITIAL_HOLD_TICKS = 40;     // первое поведение держится заданное время
    public static final int DOUBLE_BEHAVIOR_MIN_TICKS = 30;
    public static final int DOUBLE_BEHAVIOR_RANDOM_TICKS = 50;
    public static final int DOUBLE_PARTICLE_INTERVAL_TICKS = 20;
    public static final int DOUBLE_AMBIENT_SOUND_INTERVAL_TICKS = 120;
    public static final float DOUBLE_AMBIENT_VOLUME = 0.6F;

    // ---- Разум Божий: символы enchant вокруг активирующего игрока (на каждый удар колокола) ----
    public static final int MIND_GLYPH_BASE_COUNT = 10;         // символов при первом ударе
    public static final int MIND_GLYPH_PER_STRIKE = 4;          // на сколько больше символов с каждым следующим ударом (пятый удар самый плотный)
    public static final double MIND_GLYPH_RADIUS_MIN = 0.8;     // расстояние от игрока по горизонтали
    public static final double MIND_GLYPH_RADIUS_MAX = 2.2;
    public static final double MIND_GLYPH_HEIGHT_MIN = 0.1;     // высота над ногами
    public static final double MIND_GLYPH_HEIGHT_MAX = 2.6;
    public static final double MIND_GLYPH_DRIFT = 0.45;         // как далеко символ подплывает к своей точке (чем меньше, тем спокойнее висит)
    public static final int MIND_SPARK_PER_GLYPH = 2;           // фиолетовых искр рядом с каждым символом
    public static final float MIND_SPARK_SCALE = 0.9F;
    public static final int MIND_SYMBOL_DARK_RGB = 0x4B0082;    // палитра символов: тёмно-фиолетовый, средний, светлый
    public static final int MIND_SYMBOL_MID_RGB = 0x9B59FF;
    public static final int MIND_SYMBOL_LIGHT_RGB = 0xE2CCFF;

    // ---- Разум Божий: экранное искажение у выбранной жертвы (только её клиент) ----
    // Интенсивность: стадии 0-50 и 50-100 тиков (между ударами колокола), последнее значение достигается на особом третьем звуке (100 тиков)
    public static final float MIND_REDUCED_LEVEL_CAP = 0.10F;   // потолок интенсивности искажения, когда влияние способностей выключено (едва заметное присутствие)
    public static final float[] MIND_STAGE_INTENSITY = {0.15F, 0.35F, 1.00F};
    public static final int MIND_STAGE_BLEND_TICKS = 10;        // переход к интенсивности новой стадии после удара: плавно, без скачка
    public static final int MIND_FADE_OUT_TICKS = 20;           // затухание после особого звука: ровно 1 секунда (100 -> 120)
    public static final int MIND_DARK_RGB = 0x2A0049;           // тёмно-фиолетовый оттенок искажения
    public static final int MIND_MID_RGB = 0x7B3FE4;
    public static final int MIND_LIGHT_RGB = 0xD6B8FF;          // светло-фиолетовый оттенок
    public static final int MIND_ECHO_LINES_MAX = 40;           // контуры-«эхо» при полной интенсивности
    public static final int MIND_ECHO_MAX_ALPHA = 46;           // прозрачность эхо-контуров при полной интенсивности (из 255)
    public static final double MIND_ECHO_SPLIT_PX = 10.0;       // максимальное раздвоение контура, пикселей интерфейса
    public static final int MIND_TRAIL_BANDS_MAX = 9;           // полосы-следы (со 2 стадии)
    public static final int MIND_TRAIL_MAX_ALPHA = 24;
    public static final double MIND_TRAIL_SHIFT_PX = 14.0;      // смещение половин полосы друг относительно друга
    public static final double MIND_GHOST_SHIFT_PX = 7.0;       // смещение призрачных «рамок» наложенных реальностей (с 3 стадии)
    public static final int MIND_GHOST_FRAME_ALPHA = 70;
    public static final int MIND_TINT_MAX_ALPHA = 14;           // едва заметный общий оттенок; не затемнение
    public static final float MIND_SWAY_YAW_DEGREES = 0.9F;     // лёгкое «плавание» изображения при интенсивности 1.0 (только отображение камеры)
    public static final float MIND_SWAY_PITCH_DEGREES = 0.6F;
    // Тряска изображения жертвы: растёт вместе с интенсивностью стадий (1 стадия почти незаметна, 4 стадия максимум),
    // плавные синусы без случайных скачков; только отображение камеры, поворот игрока не меняется
    public static final float MIND_SHAKE_YAW_DEGREES = 1.2F;    // при интенсивности 1.0
    public static final float MIND_SHAKE_PITCH_DEGREES = 0.9F;
    public static final float MIND_SHAKE_ONSET = 0.15F;         // интенсивность первой стадии: тряска ещё равна нулю
    public static final float MIND_SHAKE_CURVE = 1.4F;          // >1: на ранних стадиях слабее, на поздних быстрее растёт

    // Шейдер экранного искажения (множители силы слоёв; 1.0 = задуманная сила при той же интенсивности стадий)
    public static final float MIND_POST_WAVE = 1.0F;            // волновое искажение пространства
    public static final float MIND_POST_CHROMA = 1.0F;          // хроматическое раздвоение
    public static final float MIND_POST_GLITCH = 1.0F;          // глитч-смещения полос (появляются с 3 стадии)
    public static final float MIND_POST_TRAIL = 1.0F;           // остаточные изображения

    // ---- Разум Божий: фиолетовая отметка над выбранной целью (видна всем клиентам, получившим метку способности) ----
    public static final int MIND_MARK_CORE_RGB = MIND_SYMBOL_MID_RGB;     // фиолетовая палитра Разума
    public static final int MIND_MARK_BORDER_RGB = MIND_SYMBOL_LIGHT_RGB;
    public static final double MIND_MARK_HEIGHT_OFFSET = 0.6;   // над головой цели, как у метки взгляда Власти
    public static final int MIND_MARK_TAIL_TICKS = MIND_FADE_OUT_TICKS;   // после особого звука держится 20 тиков, как экранное искажение

    // ---- Тень божья: растворение в земле, невидимость, возвращение ----
    public static final int SHADOW_RISE_TICKS = 40;             // чёрные частицы поднимаются от земли до головы (растворение и возвращение)
    public static final int SHADOW_SETTLE_TICKS = 20;           // после полного покрытия частицы опускаются обратно в землю
    public static final int SHADOW_HIDDEN_TICKS = 400;          // невидимость: 20 секунд, отсчёт от конца исчезновения
    public static final int SHADOW_BLACK_RGB = 0x000000;        // dust: настоящий чёрный (0, 0, 0)
    public static final float SHADOW_DUST_SCALE = 1.1F;
    public static final int SHADOW_DUST_PER_TICK = 24;          // при подъёме и опускании; остальные виды частиц как приправа к облаку
    public static final int SHADOW_SMOKE_PER_TICK = 6;
    public static final int SHADOW_LARGE_SMOKE_PER_TICK = 2;    // объём облака (раньше в среднем 0,7 за тик)
    public static final int SHADOW_ASH_PER_TICK = 6;
    public static final double SHADOW_CLOUD_RADIUS = 0.7;       // радиус облака на уровне ног
    public static final double SHADOW_PARTICLE_DISTANCE = 48.0; // дальше частицы облака не создаются
    public static final double SHADOW_TRAIL_DISTANCE = 24.0;    // следы видны только вблизи, чтобы не выдавать положение издалека
    public static final double SHADOW_TRAIL_STEP = 0.7;         // след появляется раз в столько блоков пути по земле
    public static final float SHADOW_TRAIL_DUST_SCALE = 0.6F;

    // ---- Шепот божий (Hiro no Tsuki): галлюцинации и случайное переназначение управления у выбранной жертвы ----
    public static final int WHISPER_DURATION_TICKS = 300;       // 15 секунд: ровно столько управление остаётся переназначенным
    public static final int WHISPER_FADE_IN_TICKS = 10;         // звуки и искажения набирают силу плавно
    public static final int WHISPER_FADE_OUT_TICKS = 40;        // после 15 секунд звуки и искажения плавно затихают (управление уже возвращено)
    public static final int WHISPER_REMAP_MIN_TICKS = 40;       // интервал между переназначениями клавиш: от 2 до 5 секунд
    public static final int WHISPER_REMAP_RANDOM_TICKS = 60;
    public static final int WHISPER_VOICE_MIN_TICKS = 25;       // тип А: шёпот
    public static final int WHISPER_VOICE_RANDOM_TICKS = 55;
    public static final float WHISPER_VOICE_VOLUME = 0.9F;
    public static final int WHISPER_NOISE_MIN_TICKS = 30;       // тип Б: искажённые игровые звуки
    public static final int WHISPER_NOISE_RANDOM_TICKS = 70;
    public static final float WHISPER_NOISE_VOLUME = 0.7F;
    public static final double WHISPER_SOUND_RADIUS_MIN = 2.0;  // звук возникает вокруг головы жертвы на таком расстоянии
    public static final double WHISPER_SOUND_RADIUS_MAX = 6.0;
    public static final int WHISPER_GLITCH_MIN_TICKS = 40;      // пауза между экранными глитчами
    public static final int WHISPER_GLITCH_RANDOM_TICKS = 80;
    public static final int WHISPER_GLITCH_LENGTH_MIN_TICKS = 3; // один глитч длится 3-7 тиков
    public static final int WHISPER_GLITCH_LENGTH_RANDOM_TICKS = 5;
    public static final float WHISPER_DARK_MAX_ALPHA = 0.55F;   // кратковременное затемнение
    public static final int WHISPER_STATIC_MAX_ALPHA = 110;     // помехи
    public static final int WHISPER_SLICE_MAX_ALPHA = 80;       // сдвинутые полосы
    public static final float WHISPER_SHAKE_DEGREES = 0.8F;     // лёгкая дрожь изображения во время глитча (только отображение камеры)

    // ---- Отметка цели в руке: Разум (Black Iron Greatsword) и Шепот (Hiro no Tsuki) ----
    public static final int HOLD_MARK_ALPHA_DIM = 0xC0;         // цель выбрана, но не точно под прицелом

    // ---- Власть Божья: выбор цели ----
    public static final int HIT_CONFIRM_TICKS = 10;             // сколько тиков ждём подтверждения попадания (анимация урона у цели)

    // ---- Власть Божья: цепи и магический круг за спиной ----
    public static final int CHAINS_BIND_TICKS = 300;            // цепи и обездвиживание после попадания молнии: 15 секунд (было 200, +100 тиков)
    public static final int CHAINS_PER_HAND = 5;                // цепей из каждой руки (3, 5 или 7)
    public static final int CHAIN_GROW_TICKS = 8;               // за сколько тиков цепи опускаются от рук к земле
    public static final int BACK_CIRCLE_FORM_TICKS = BELL_COUNT * STRIKE_INTERVAL_TICKS; // медленное вырисовывание: от первого удара до особого звука
    public static final int BACK_CIRCLE_DISSOLVE_TICKS = 30;    // плавное растворение круга после окончания цепей
    public static final int CIRCLE_RGB = 0xB834B4;              // цвет символа по референсу
    public static final int CHAINS_RGB = 0x4B0082;              // основной цвет цепей и круга
    public static final int CHAINS_DARK_RGB = 0x2A0049;         // тёмный фиолетовый оттенок
    public static final int CHAINS_FLASH_RGB = 0xE2CCFF;        // белые вспышки
    public static final int LOCK_SAFETY_TICKS = CHAINS_BIND_TICKS + 20; // предохранитель: блокировка не длится дольше
    public static final float CHAIN_SOUND_VOLUME = 1.2F;
    public static final double CHAIN_RENDER_DISTANCE = 64.0;    // дальше цепи и круг не рисуются

    // ---- Власть Божья: постоянная аура ----
    public static final int AURA_RGB = 0x4B0082;
    public static final int AURA_FLASH_RGB = 0xE2CCFF;
    public static final double AURA_RANGE = 32.0;               // дальность отображения, как у способностей (RADIUS)
    public static final int AURA_MAX_HOLDERS = 8;               // предел одновременно рисуемых аур
    public static final int AURA_BOOST_RISE_TICKS = 10;         // усиление при применении способности
    public static final int AURA_BOOST_FALL_TICKS = JUDGMENT_AFTERGLOW_TICKS; // плавное возвращение к обычному состоянию
    public static final int AURA_SIGHT_INTERVAL_TICKS = 5;      // как часто проверяется прямая видимость обладателя

    // ---- Власть Божья: экранный эффект у поражённого игрока ----
    public static final int SCREEN_FADE_IN_TICKS = 20;          // постепенное появление
    public static final int SCREEN_FADE_OUT_TICKS = 10;         // плавное исчезновение: 0,5 секунды
    public static final int SCREEN_VIGNETTE_RGB = 0x1A0033;     // тёмно-фиолетовая виньетка
    public static final int SCREEN_TINT_RGB = 0x4A3A9A;         // холодный фиолетовый оттенок
    public static final int SCREEN_PULSE_TICKS = 90;            // период медленной пульсации

    // Печать божья (Lycanbane): цепи вокруг головы жертвы и блокировка её текстового и голосового чата
    public static final int SEAL_DURATION_TICKS = 300;          // 15 секунд, ровно
    public static final float SEAL_SOUND_VOLUME = CHAIN_SOUND_VOLUME; // block.chain.place: один раз при появлении цепей
    public static final double SEAL_LINK_PITCH = 0.105;         // шаг звеньев вдоль цепи (как у цепей Власти: около 0,1 блока)
}
