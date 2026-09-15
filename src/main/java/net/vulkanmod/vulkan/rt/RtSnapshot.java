package net.vulkanmod.vulkan.rt;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * M8.3 шаг-2/Фаза 1 — ПОЛНОЭКРАННАЯ RT-картинка из живой камеры → PNG, теперь С
 * ТЕКСТУРАМИ: первичный луч сэмплит атлас блоков по UV попавшего треугольника
 * (UV/color берём из НАШИХ копий вершин через buffer_reference; таблица
 * «инстанс→адрес вершин» — {@link RtWorld#vtxTableBuffer()}). Плюс солнце+тени.
 */
public class RtSnapshot {

    // ⚠️ РАЗРЕШЕНИЕ РЕНДЕРА — НАСТРОЙКА, А НЕ КОНСТАНТА. Было зашито 1280x720 («67% от FHD») —
    // но на другом мониторе это совсем другая доля, а на 4K — вчетверо меньше пикселей, чем нужно.
    // Теперь считаем от размера окна: W = экран * (процент из настроек). Это ровно то разрешение,
    // в котором работает трассировка и с которого DLSS восстанавливает полный кадр.
    private static volatile int W = 1280, H = 720;
    private static int lastScalePct = -1;

    // ⚠️ Шейдер >64 КБ (лимит одной String-константы в class-файле). Держим ТРЕМЯ text-block'ами
    // и склеиваем в рантайме через String.join — иначе `+` литералов свернулся бы обратно в одну
    // константу и снова упёрся бы в лимит.
    private static final String COMP_SRC = String.join("",
            """
            #version 460
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_buffer_reference : require
            //DEFINES
            #ifdef ENT_TEX
            #extension GL_EXT_nonuniform_qualifier : require
            #endif
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(binding = 0) uniform accelerationStructureEXT tlas;
            // === M8.27: выход — ЛИНЕЙНЫЙ HDR-ОБРАЗ, а не готовый 8-битный пиксель ===
            // Тонмаппинг и экранные эффекты уехали в RtPost: DLSS Ray Reconstruction принимает
            // на вход ШУМНЫЙ ЛИНЕЙНЫЙ цвет ДО тонмаппера (тонмаппер ломает ей яркостную
            // статистику), а эффекты (огонь/урон) она приняла бы за часть сцены и размазала.
            layout(binding = 1, rgba16f) uniform writeonly image2D outColor;

            // === M8.28: GUIDE-БУФЕРЫ ДЛЯ DLSS RAY RECONSTRUCTION ===
            // RR — не «фильтр поверх картинки»: она реконструирует кадр, зная, ЧТО в пикселе.
            // Обязательный набор: глубина, вектор движения, нормаль, шероховатость, диффузное и
            // зеркальное альбедо. Плюс камера прошлого кадра — чтобы понять, куда уехал пиксель.
            layout(binding = 14, r32f)    uniform writeonly image2D outDepth;   // NDC-глубина [0..1]
            layout(binding = 15, rg16f)   uniform writeonly image2D outMotion;  // сдвиг в ПИКСЕЛЯХ (в прошлый кадр)
            layout(binding = 16, rgba16f) uniform writeonly image2D outNormal;  // мировая нормаль + шероховатость в w
            layout(binding = 17, rgba16f) uniform writeonly image2D outDiffAlb; // диффузное альбедо
            layout(binding = 18, rgba16f) uniform writeonly image2D outSpecAlb; // зеркальное альбедо (F0)
            // РЕАКТИВНОСТЬ: «здесь не накапливай по времени, верь текущему кадру».
            // ⚠️ Нужна воде: её поверхность геометрически НЕПОДВИЖНА (вектор движения ~0), а рябь по
            // ней бежит. Для сети это «пиксель стоит, а яркость скачет» = шум, и она его усредняет —
            // волны залипают и кажутся медленными, будто обновляются раз в несколько кадров.
            layout(binding = 19, r16f) uniform writeonly image2D outReact;

            layout(binding = 13) uniform Cam {
                vec4 pOrigin;    // позиция камеры ПРОШЛОГО кадра
                vec4 pForward;   // xyz базис прошлого кадра
                vec4 pUp;        // w = tanY прошлого кадра
                vec4 pLeft;      // w = tanX прошлого кадра
                vec4 jitterNF;   // x,y = субпиксельный jitter (в пикселях), z = near, w = far
                vec4 frameInfo;  // x = номер кадра, y = число рёбер обводки, z = ВРЕМЯ КАДРА (сек)
                // ⚠️ ОБВОДКА — ЭТО РЁБРА, А НЕ КОРОБКИ. Рисуя рёбра каждой коробки формы, мы получали
                // ЛИШНИЕ ЛИНИИ на внутренних стыках — кровать выглядела расчерченной сеткой. Ваниль
                // обводит только ВНЕШНИЙ контур формы (shape.forAllEdges), и мы теперь берём его же:
                // пары точек (a, b) — концы отрезка.
                vec4 oedge[64];   // до 32 рёбер
                // === RT PATCH === НАСТРОЙКИ (страница «Трассировка»). Push-константы забиты под
                // предел 256 Б, поэтому ручки едут сюда, в UBO камеры (тут ещё свободно).
                vec4 rtCfg;   // x = тени солнца (0/1), y = ЛУЧЕЙ небесного света (0/2/4),
                              // z = отражения (0/1), w = объёмные облака (0/1)
                vec4 rtCfg2;  // ТУМАН ОТ ДАЛЬНОСТИ ПРОРИСОВКИ: x = начало (блоки), y = длина набора,
                              // z = потолок плотности; w = множитель яркости ФАКЕЛОВ
                vec4 rtCfg3;  // x = множитель ЭМИССИИ, y = МИНИМАЛЬНОГО света, z = ЗВЁЗД, w = СЕВЕРНОЕ СИЯНИЕ (0..1)
                // КАЧЕСТВО ТРАССИРОВКИ — это ДАЛЬНОСТЬ ЛУЧЕЙ. Луч, летящий на 4096 блоков, обходит
                // всё дерево BVH; обрезанный до 256 — только ближние ветви. Картинка почти та же
                // (дальняя тень всё равно тонет в тумане), а работы кратно меньше.
                vec4 rtCfg4;  // x = дальность ТЕНЕВОГО луча, y = дальность ОТРАЖЕНИЯ,
                              // z = дальность АМБИЕНТА, w = качество марша ОБЛАКОВ (0 = полное)
                vec4 rtCfg5;  // x = АЛЬФА-МЕТКИ пака (M8.126), y = ИЗМЕРЕНИЕ (0 верх, 1 ад, 2 энд), zw = ЦЕНТР XZ ГЛАДИ ПОРТАЛА (M8.144)
                vec4 rtCfg6;  // xyz = ЦВЕТ ТУМАНА АДА по биому (M8.133), w = Y ГЛАДИ ПОРТАЛА ЭНДА (M8.144, <-1e8 = нет)
                vec4 rtCfg7;  // M8.146: x = индекс TLAS-инстанса ОСАДКОВ (-1 = нет), y = слот их текстуры, zw — резерв
                vec4 rtCfg8;  // M8.153 ОБЪЁМ ЦВЕТА СВЕТА: xyz = НАЧАЛО решётки в блоках, w = готова ли (0/1)
            } cam;

            // ⚠️⚠️ НОМЕР КАДРА В ЗЕРНЕ — БЕЗ ЭТОГО ДЕНОЙЗЕР БЕСПОЛЕЗЕН.
            // Зерно раньше зависело только от координат пикселя: случайные лучи амбиента каждый кадр
            // летели в ОДНИ И ТЕ ЖЕ стороны, шум стоял намертво. Временной денойзер (и DLSS тоже)
            // усредняет сэмплы ПО ВРЕМЕНИ — а усреднять нечего: для него это не шум, а «текстура»
            // на поверхности. Так и выглядело: пастельные полосы в тенях, которые не уходят.
            uint frameSeed() { return uint(cam.frameInfo.x) * 26699u; }

            // Что лежит в пикселе — заполняется по ходу шейдинга, пишется в guide-буферы в конце.
            vec3  g_normal = vec3(0.0, 1.0, 0.0);
            float g_rough  = 1.0;
            vec3  g_diff   = vec3(0.0);
            vec3  g_spec   = vec3(0.0);
            float g_viewZ  = -1.0;    // < 0 = небо (геометрии нет)
            bool  g_isHand = false;   // рука/предмет: приклеены к камере, на экране НЕ движутся
            float g_react  = 0.0;     // 1 = не накапливай этот пиксель по времени (анимированная поверхность)
            vec3  g_vel    = vec3(0.0);   // МИРОВАЯ скорость того, что видно в пикселе (блоки/сек)

            // Вершины секции: 4 uint на вершину (16 байт): pos(0,1) uv(2) color(3).
            layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Verts {
                uint w[];
            };
            // Таблица инстанс(instanceCustomIndex) → ссылка на вершины его секции.
            layout(std430, binding = 2) readonly buffer VtxTable { Verts refs[]; };
            // M8.153: решётка ОТТЕНКА света вокруг камеры (64^3 ячеек по 2 блока = 128^3 блоков).
            // Упаковка RGBA8 в uint: цвет уже СМЕШАН и НОРМИРОВАН (чистый оттенок, без яркости),
            // альфа = «в этой ячейке вообще есть свет». Яркость по-прежнему из вершинного lightmap.
            layout(std430, binding = 20) readonly buffer LightVol { uint lvox[]; };

            layout(binding = 3) uniform sampler2D atlas;
            layout(binding = 7) uniform sampler2D noisetex;    // НАША текстура шума (M8.95: эффекты, облака)
            layout(binding = 8) uniform sampler2D crackTex;    // трещины ломания: 10 стадий стопкой (16x160)
            layout(binding = 9) uniform sampler2D moonTex;     // ЛУНА Eclipse: карта сферы (equirect)
            layout(binding = 10) uniform sampler2D rainTex;    // ОСАДКИ: атлас MC дождь(верх)+снег(низ) 64x512
            layout(binding = 12) uniform sampler2D materialMap; // ОТРАЖЕНИЯ: matId по UV атласа (0=обычный)
            #ifdef RT_PBR
            // === M8.162 PBR ИЗ РЕСУРСПАКОВ (labPBR) === — те же UV, что у атласа и карты материалов.
            // R,G = нормаль (DirectX: зелёный вниз), B = z (пересчитан при сборке, в паке там AO);
            // R = гладкость, G = F0/металл, B = пористость, A = эмиссия. См. RtPbrMaps.
            layout(binding = 21) uniform sampler2D pbrNormalTex;
            layout(binding = 22) uniform sampler2D pbrMaterialTex;
            #endif

            #ifdef ENT_TEX
            layout(binding = 4) uniform sampler2D entityTex[256];   // текстуры сущностей кадра
            layout(std430, binding = 5) readonly buffer QuadTex { uint quadTex[]; };
            #endif

            layout(push_constant) uniform Push {
                vec4 origin;   // xyz камера, w = ширина
                vec4 forward;  // xyz, w = высота
                vec4 up;       // xyz, w = tanY
                vec4 left;     // xyz, w = tanX
                vec4 params;   // x=камера под водой (0/1), y=время (тики), z=tanX руки, w=tanY руки (фикс. FOV вьюмодела)
                vec4 wparams;  // x = Y глади над камерой (скан колонки воды; -1e9 = нет)
                               // y = block-light В ПОЗИЦИИ ИГРОКА (0..1) — для руки
                               // z = свет ПРЕДМЕТА В РУКЕ (0..1) — динамический источник
                               // w = ЧИСЛО цветных источников света в буфере lights[]
                vec4 handCol;  // xyz = ЦВЕТ света предмета в руке; w = глаз В ЛАВЕ (0/1)
                vec4 outlineInfo; // x = коробки обводки; y = ГОРИМ; z = момент ПОГРУЖЕНИЯ; w = момент ВЫНЫРИВАНИЯ
                // ⚠️ Форма блока — это НАБОР коробок (у фонаря Shapes.or из двух), ваниль обводит
                // каждую. Одна общая AABB давала рамку шире модели. Кладём до 4 коробок подряд:
                // по 6 float (min.xyz, max.xyz) => 24 float => 6 vec4.
                vec4 obox[3];   // до 2 коробок формы (12 float) — ужато под лимит push 256
                vec4 skyP;      // xyz = НАПРАВЛЕНИЕ НА СОЛНЦЕ (мир), w = время суток (0..1)
                // M8.10/M8.12 ЭФФЕКТЫ КАМЕРЫ. Униформы — как у Eclipse (lib/gameplay_effects.glsl)
                vec4 fx1;  // x=слепота, y=ночное зрение, z=яд, w=иссушение
                vec4 fx2;  // x=тошнота, y=тьма(Страж), z=oneHeart, w=threeHeart
                vec4 fx3;  // x=MinorDamageTaken, y=CriticalDamageTaken, z=exitLava, w=ПРОГРЕСС ЛОМАНИЯ (0..1)
                // ⚠️ ПОРЯДОК ПОЛЕЙ = ПОРЯДОК ЗАПИСИ В JAVA. skyP2 стоит ПОСЛЕДНИМ (смещение 240),
                // потому что Java пишет его туда. Когда он стоял сразу после skyP, смещения
                // разъехались: шейдер читал покрытие облаков из поля «яд» (=0), и формула Eclipse
                // .../sqrt(coverage) давала 0/0 = NaN -> ВЕСЬ КАДР ЧЁРНЫЙ.
                vec4 skyP2;     // x = ФАЗА ЛУНЫ, y = ВРЕМЯ ОБЛАКОВ, z = ПОКРЫТИЕ, w = ДОЖДЬ (0..1)
            };
            float obf(int i){ return obox[i >> 2][i & 3]; }

            // M8.16 ТРЕЩИНЫ ЛОМАНИЯ. Ломаемый блок = блок ПОД ПРИЦЕЛОМ (его коробки уже пришли для
            // обводки), из Java нужен лишь ПРОГРЕСС (fx3.w). Накладываем поверх альбедо: UV берём по
            // грани (по доминирующей оси нормали), стадию (0..9) — смещением по атласу 16x160.
            // Ваниль рисует трещины УМНОЖЕНИЕМ (crumbling), поэтому и мы затемняем, а не заливаем.
            vec3 applyCracks(vec3 c, vec3 hp, vec3 nrm){
                float prog = fx3.w;
                if (prog <= 0.001 || int(outlineInfo.x) <= 0) return c;
                // точка должна лежать НА ломаемом блоке (первая коробка формы)
                vec3 bmin = vec3(obf(0), obf(1), obf(2));
                vec3 bmax = vec3(obf(3), obf(4), obf(5));
                vec3 q = hp - nrm * 0.01;                     // чуть внутрь грани
                if (any(lessThan(q, bmin - 0.02)) || any(greaterThan(q, bmax + 0.02))) return c;

                vec3 f = fract(hp);                            // положение внутри блока
                vec2 uv = abs(nrm.y) > 0.5 ? vec2(f.x, f.z)
                        : abs(nrm.x) > 0.5 ? vec2(f.z, f.y)
                                           : vec2(f.x, f.y);
                float stage = clamp(floor(prog * 10.0), 0.0, 9.0);
                vec2 cuv = vec2(uv.x, (uv.y + stage) / 10.0);  // стадии стопкой в атласе
                vec4 cr = texture(crackTex, cuv);
                return c * mix(vec3(1.0), cr.rgb, cr.a);       // умножение, как ванильный crumbling
            }

            // M8.15 ОБВОДКА как ФУНКЦИЯ от произвольного луча — чтобы рисовать её не только на
            // первичном, но и на ПРЕЛОМЛЁННОМ (блок под водой). Возвращает: попали ли в ребро.
            // maxT — дистанция до ближайшей геометрии на этом луче (перекрытие).
            // Ближайшее расстояние между ЛУЧОМ (ro,rd) и ОТРЕЗКОМ (a,b); tRay — где это на луче.
            float segRayDist(vec3 ro, vec3 rd, vec3 a, vec3 b, out float tRay){
                vec3 v = b - a, w = ro - a;
                float A = dot(rd, rd), B = dot(rd, v), C = dot(v, v), D = dot(rd, w), E = dot(v, w);
                float den = A * C - B * B;
                float tc = (den < 1e-6) ? 0.0 : clamp((A * E - B * D) / den, 0.0, 1.0);
                float sc = max((B * tc - D) / max(A, 1e-6), 0.0);
                tRay = sc;
                return length((ro + rd * sc) - (a + v * tc));
            }

            bool outlineHit(vec3 ro, vec3 rd, float maxT){
                int n = int(cam.frameInfo.y);
                for (int i = 0; i < n; i++) {
                    vec3 a = cam.oedge[i*2 + 0].xyz;
                    vec3 b = cam.oedge[i*2 + 1].xyz;
                    float tRay;
                    float d = segRayDist(ro, rd, a, b, tRay);
                    if (tRay <= 0.0 || tRay > maxT + 0.03) continue;
                    if (d < 0.004 + 0.0016 * tRay) return true;   // толщина растёт с дальностью
                }
                return false;
            }

            // M8.7 ЦВЕТНОЙ СВЕТ. Список светящихся блоков вокруг игрока (собран в RtLights.java).
            // Вершинный lightmap знает УРОВЕНЬ света, но не КТО его излучил, поэтому оттенок
            // (голубой у душ, зелёный у меди, фиолетовый у портала) берём отсюда.
            struct RtLight {
                vec4 posRange;   // xyz = центр блока (мировые коорд.), w = дальность в блоках
                vec4 col;        // rgb = цвет (таблица Eclipse setup.csh)
            };
            layout(std430, binding = 6) readonly buffer Lights { RtLight lights[]; };
            // M8.23 УДАРЫ КАПЕЛЬ (рябь): [0].x = число, далее (x, z, времяРождения_тики, _)
            layout(std430, binding = 11) readonly buffer Ripples { vec4 ripples[]; };

            // === M8.18 ЦИКЛ ДЕНЬ/НОЧЬ ===
            // Солнце больше НЕ константа: направление приходит из игрового времени (sky.xyz).
            // Ночью светит ЛУНА — она ровно напротив солнца, поэтому источник света = ±sky.xyz.
            vec3  sunVec()   { return skyP.xyz; }
            float sunH()     { return skyP.y; }                 // высота солнца: -1 полночь .. 1 полдень
            vec3  moonVec()  { return -skyP.xyz; }
            // ИСТОЧНИК прямого света: днём солнце, ночью луна (плавная передача у горизонта).
            vec3  SUN_DIR_f(){ return sunH() >= 0.0 ? sunVec() : moonVec(); }
            int   dimType()  { return int(cam.rtCfg5.y + 0.5); }  // 0 верхний, 1 Ад, 2 Энд (M8.130)
            vec3  endOrb()   { return normalize(vec3(0.28, 0.62, -0.73)); }  // затменное светило Энда
            // Радиус точки d в радиусах диска светила Энда (0 центр .. 1 край). Общий для
            // затемнения тела (main) и короны/ореола (skyBodies) — чтобы совпадали.
            float endOrbRad(vec3 d){
                vec3 od = endOrb();
                float dp = dot(d, od);
                if (dp <= 0.0) return 1e9;
                vec3 up0 = abs(od.y) < 0.99 ? vec3(0,1,0) : vec3(1,0,0);
                vec3 tx = normalize(cross(up0, od)), ty = cross(od, tx);
                vec3 pj = d / max(dp, 1e-4);
                return length(vec2(dot(pj, tx), dot(pj, ty))) / 0.10;   // 0.10 = ORB_R
            }

            // Цвет чернотельного излучения по температуре: солнце 6300K, луна 8000K, звёзды.
            // Аппроксимация Таннера Хелланда (общеизвестный фит). Вход в Кельвинах, выход линейный RGB.
            vec3 blackbody(float Temp){
                float t = Temp * 0.01;
                vec3 col;
                col.r = t <= 66.0 ? 1.0
                      : clamp(1.29293618606 * pow(t - 60.0, -0.1332047592), 0.0, 1.0);
                col.g = t <= 66.0
                      ? clamp((99.4708025861 * log(t) - 161.1195681661) / 255.0, 0.0, 1.0)
                      : clamp(1.12989086089 * pow(t - 60.0, -0.0755148492), 0.0, 1.0);
                col.b = t >= 66.0 ? 1.0
                      : (t <= 19.0 ? 0.0
                         : clamp((138.5177312231 * log(t - 10.0) - 305.0447927307) / 255.0, 0.0, 1.0));
                col = Temp < 1000.0 ? col * Temp * 0.001 : col;
                return pow(col, vec3(2.2));        // srgbToLinear
            }

            // «Закатность»: 1 у самого горизонта, 0 днём и глубокой ночью. Наш собственный эффект —
            // у Eclipse его нет (там физическая модель Рэлея), а пользователь хочет сочный закат.
            // ⚠️ АСИММЕТРИЧНО. Гауссиана с одной шириной одинаково живёт по обе стороны горизонта:
            // солнце уже на -0.3 (небо чёрное, звёзды и луна на месте), а она всё ещё даёт 0.32 —
            // яркий жёлто-розовый веер на ночном небе. Под горизонтом сумерки должны гаснуть быстро.
            // ⚠️ СКОЛЬКО СВЕТА В ВОДЕ. Рассеяние в толще воды светится не само по себе — его
            // освещает небо. А масштабировали мы его лайтмапом (wlm.y = «доступ к небу»), который
            // НОЧЬЮ ОСТАЁТСЯ ЕДИНИЦЕЙ под открытым небом. Отсюда «газировка»: синь горела в полночь
            // так же, как в полдень. Ночью оставляем лунный минимум.
            float dayAmt();
            float waterDayLit(){ return mix(0.045, 1.0, dayAmt()); }   // M8.116: ночью вдвое темнее (репорт)

            float sunsetAmt(){
                float h = sunH();
                // Над горизонтом ШИРЕ (0.40): закат занимается заранее, пока небо ещё синее —
                // солнце низкое, но не севшее. Под горизонтом РЕЗКИЙ спад (0.12): сумерки гаснут
                // быстро, и к ночи от зарева не остаётся ничего.
                float w = h >= 0.0 ? 0.40 : 0.12;
                return exp(-pow(h / w, 2.0));
            }
            // День (1) / ночь (0) с мягкой сумеречной передачей
            float dayAmt(){ return smoothstep(-0.12, 0.14, sunH()); }
            // Звёзды и луна проступают ЕЩЁ В СУМЕРКАХ, бледно — как в жизни (не «включаются» ночью).
            // Луна в MC ровно напротив солнца, значит восходит точно на закате.
            float starFade(){ return smoothstep(0.24, -0.05, sunH()); }
            float moonUp()  { return smoothstep(-0.07, 0.09, -sunH()); }

            // ЦВЕТ ПРЯМОГО СВЕТА: днём тёплый белый (6300K), на закате — розово-золотой (наш),
            // ночью — холодный лунный (8000K, слабый).
            vec3 SUN_COL_f(){
                float d = dayAmt(), ss = sunsetAmt();
                vec3 dayCol   = blackbody(6300.0) * 4.2;
                vec3 sunsetCol= vec3(1.00, 0.46, 0.34) * 3.4;      // розово-золотой (закат)
                vec3 nightCol = blackbody(8000.0) * 0.45;          // луна: холодная, НАПРАВЛЕННАЯ (ярче прямой)
                vec3 lit = mix(dayCol, sunsetCol, ss);
                return mix(nightCol, lit, d);
            }
            // M8.102: тёплый цвет факельного света — СВОЯ калибровка (не значения Eclipse).
            // ⚠️ Усиление нужно, потому что у нас экспозиция фиксированная под день (без
            // авто-адаптации глаза) — иначе подземелья тонули в черноте.
            const vec3  TORCH_COL    = vec3(1.00, 0.53, 0.27);
            const float TORCH_AMOUNT_BASE = 2.8;
            float TORCH_AMOUNT_f(){ return TORCH_AMOUNT_BASE * cam.rtCfg2.w; }
            #define TORCH_AMOUNT TORCH_AMOUNT_f()
            // Пол яркости: «чёрный» не должен быть угольным нулём — в реальном зрении
            // абсолютно чёрных теней не бывает (значения свои).
            // M8.145: подняли ~2.5x. В Deep Dark / глубоких пещерах и skyL, и blockL = 0, поэтому
            // эмбиент падал ровно до этого минимума -> кадр почти чёрный (репорт по Ancient City).
            // В освещённых сценах эта добавка пренебрежимо мала, так что подъём трогает ТОЛЬКО тьму:
            // формы читаются, настроение и контраст цветного света (скалк/свечи) сохраняются.
            // Пользователь может докрутить ползунком «минимальный свет» (cam.rtCfg3.y).
            const vec3  MIN_LIGHT_BASE = vec3(0.042, 0.045, 0.060);   // чуть холодный
            vec3 MIN_LIGHT_f(){ return MIN_LIGHT_BASE * cam.rtCfg3.y; }
            #define MIN_LIGHT MIN_LIGHT_f()

            // M8.133: сине-сиреневый амбиент Энда — купает террейн в фиолетовом. В Энде нет
            // ни солнца, ни цикла суток, поэтому весь непрямой свет — эта база (ориентир —
            // AmbientLightEnd Eclipse ~(0.3,0.35,1.0); поведение и калибровка свои).
            vec3 endAmb(){ return vec3(0.40, 0.34, 0.60); }
            // Свет затменного светила Энда — сине-фиолетовый направленный (даёт объём:
            // освещённая/теневая сторона обелисков, тени на земле). Ориентир — VORTEX_LIGHT
            // Eclipse ~(0.5,0.4,1.0); калибровка своя.
            vec3 endOrbLight(){ return vec3(0.62, 0.46, 1.05); }
            // Есть ли ПРЯМОЙ свет светила: 1 в верхнем мире, 0 в Аду/Энде (солнца нет — иначе
            // террейн мигал бы по циклу суток верхнего мира через skyP).
            float dimDay(){ return dimType() == 0 ? 1.0 : 0.0; }
            // Дешёвый хеш мировой ячейки -> [0..1] для пылинок в объёмных лучах (M8.134)
            float h13(vec3 p){ return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }

            // ТУМАН. Раньше начало (128 блоков) и длина (768) были зашиты — при дальности 8 чанков
            // туман съедал весь горизонт, при 32 не появлялся вовсе. Теперь его границы считает Java
            // ОТ ДАЛЬНОСТИ ПРОРИСОВКИ: горизонт всегда одинаково подёрнут дымкой (см. rtCfg2).
            float fogAmt(float t){
                float f = clamp((t - cam.rtCfg2.x) / max(cam.rtCfg2.y, 1.0), 0.0, cam.rtCfg2.z);
                // M8.144i: в ДОЖДЬ дымка ГУЩЕ и БЛИЖЕ — дальние горы тонут мягче (не «вырвиглазят»).
                // rainF() определён ниже -> берём силу дождя inline из skyP2.w (|w| = осадки+гроза).
                float rf = min(abs(skyP2.w), 1.0);
                if (rf > 0.001) {
                    // раньше стартует и быстрее набирает -> средние/дальние горы уходят в серую мглу
                    float near = clamp((t - cam.rtCfg2.x * 0.30) / max(cam.rtCfg2.y * 0.50, 1.0), 0.0, 1.0);
                    f = mix(f, max(f, near), rf * 0.93);
                }
                return f;
            }
            // Сила экранных эффектов (свои значения)
            const float MOTION_AMOUNT           = 0.24;
            const float ON_FIRE_DISTORT_STRENGTH = 0.72;

            // --- Тонмаппер AgX (MIT: Benjamin Wrensch), матрицы merlin — грейд Eclipse.
            // Кинематографическая S-кривая вместо жёсткого clamp: яркое мягко
            // скругляется (не выгорает), + «punchy» насыщенность. Порт из rt-engine M7.
            vec3 agxContrast(vec3 x) {
                vec3 x2 = x * x, x4 = x2 * x2;
                return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
                     - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
            }
            vec3 agxLook(vec3 v) {
                // AgX «punchy» (Wrensch): контраст pow 1.35 + насыщенность 1.4 —
                // «мультяшная/фэнтезийная» подача (просьба пользователя).
                v = pow(v, vec3(1.35));
                float l = dot(v, vec3(0.2126, 0.7152, 0.0722));
                return l + 1.5 * (v - l);   // сочнее — мультяшно-фэнтезийная подача
            }
            vec3 ToneMap_AgX(vec3 color) {
                const mat3 inMat = mat3(
                    0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                    0.0784335999999992, 0.878468636469772, 0.0784336,
                    0.0792237451477643, 0.0791661274605434, 0.879142973793104);
                const mat3 outMat = mat3(
                    1.19687900512017, -0.0528968517574562, -0.0529716355144438,
                    -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
                    -0.0990297440797205, -0.0989611768448433, 1.15107367264116);
                const float minEv = -11.47393, maxEv = 3.226069;
                color = inMat * color;
                color = clamp(log2(max(color, 1e-8)), minEv, maxEv);
                color = (color - minEv) / (maxEv - minEv);
                color = agxContrast(color);
                color = agxLook(color);
                color = outMat * color;
                color = pow(max(vec3(0.0), color), vec3(2.2));   // -> линейный, гамма позже
                return clamp(color, 0.0, 1.0);
            }
            // Небо — калибровка из M7 (HDR, подача в духе Eclipse; шум/облака позже)
            // === M8.18 НЕБО: градиент по ВЫСОТЕ СОЛНЦА + НАШИ ЗАКАТЫ + СВОЁ звёздное поле ===
            // M8.102: звёзды — СОБСТВЕННАЯ реализация (не порт). Направление проецируем на
            // ДОМИНАНТНУЮ грань куба (одна решётка вместо суммы трёх плоскостей), в клетке
            // зажигаем звезду по хешу со случайным сдвигом от центра; цвет — чёрное тело
            // 5300..18200K; мерцание — своя фаза по клетке. Дизеринг-хеш ниже (hash12_alt) —
            // фольклорный однострочник, оставлен для марша облаков.
            float hash12_alt(vec2 co){
                return fract(sin(6.2831853 * fract(dot(co.xy, vec2(12.9898, 78.233)))) * 43758.5453);
            }
            float starHash(vec2 p){                             // хеш Дейва Хоскинса (MIT)
                p = fract(p * vec2(0.1031, 0.1030));
                p += dot(p, p.yx + 33.33);
                return fract((p.x + p.y) * p.x);
            }
            float starTemp(float h){ return h*h*h * (18200.0 - 5300.0) + 5300.0; }
            vec3 starsCol(vec3 d){
                vec3 a = abs(d);
                vec2 fc; float w;
                if (a.x >= a.y && a.x >= a.z) { fc = d.yz / a.x; w = a.x; }
                else if (a.y >= a.z)          { fc = d.xz / a.y; w = a.y; }
                else                          { fc = d.xy / a.z; w = a.z; }
                vec2 grid = fc * 235.0;                          // плотность решётки
                vec2 cell = floor(grid);
                if (starHash(cell) < 0.9935) return vec3(0.0);   // звезда лишь в ~0.65% клеток (реже — просьба)
                vec2 off = vec2(starHash(cell + 17.0), starHash(cell + 43.0)) - 0.5;
                float dd = length(grid - cell - 0.5 - off * 0.55);
                float core = exp(-dd * dd * 15.0);               // мельче ядро (просьба)
                float tw = 0.68 + 0.32 * sin(starHash(cell + 71.0) * 6.2831853 + params.y * 0.05);
                // w² гасит звёзды у рёбер куба — маскирует сшивку граней (как ослабление
                // у горизонта плоскости в старом варианте)
                return blackbody(starTemp(starHash(cell + 5.0)))
                     * (core * tw * w * w * 1.1) * cam.rtCfg3.z; // тусклее (просьба)
            }

            // ============ M8.20/102 ОБЪЁМНЫЕ ОБЛАКА — СВОЯ сборка ============
            // Приёмы стандартные для объёмных облаков (поле «покрытие минус шум», марш с
            // Беером, «одна выборка» для тени); композиция, кривые и константы собственные
            // (clean-room M8.102, Eclipse оставался лишь ориентиром по виду).
            // 4 слоя с его дефолтами (lib/settings.glsl). Слои 0 и 1 — те, сквозь которые летают
            // и на уровне которых строят; 2 и 3 — высокие тонкие пелены.
            // ⚠️ Высоты Eclipse (5000/7000) — под РЕАЛИСТИЧНЫЙ масштаб планеты; в Minecraft (мир
            // ~320 блоков) типы 2 и 3 висели вне поля зрения и были тоньше бумаги -> «нет облаков»
            // на половину дней. Опустил на видимые высоты и сделал тонкие типы плотнее. Каша не
            // грозит — в день марширует только ОДИН тип, высоты могут перекрываться.
            const float CL0_h = 200.0, CL0_tall = 130.0, CL0_dens = 0.50, CL0_detail = 600.0; // кучевые
            const float CL1_h = 320.0, CL1_tall = 200.0, CL1_dens = 0.55, CL1_detail = 250.0; // кучево-слоистые
            const float CL2_h = 480.0, CL2_tall = 70.0,  CL2_dens = 0.20, CL2_cov = 0.6;      // высокослоистые (пласт)
            const float CL3_h = 640.0, CL3_tall = 45.0,  CL3_dens = 0.07, CL3_cov = 0.07;     // перистые
            const float CLOUD_SHADOW_AMOUNT = 100.0;
            const float PLANET_R  = 6371000.0;   // радиус планеты — даёт кривизну слоя облаков
            const float CLOUD_FAR = 24000.0;     // потолок марша (дальше их всё равно съедает дымка)

            float cloudMove(){ return skyP2.y; }
            // ПОГОДА упакована в skyP2.w: |w| = сила осадков+грозы (0..2), ЗНАК = снег(-)/дождь(+).
            //   дождь rainF = min(|w|,1);  гроза thunderF = |w|-1;  снег snowF = знак минуса.
            float rainF()    { return min(abs(skyP2.w), 1.0); }
            float thunderF() { return clamp(abs(skyP2.w) - 1.0, 0.0, 1.0); }
            // M8.144k: сила ПРЯМОГО солнца — гаснет в дождь (пасмурно). При dayDirect~0 солнечный
            // теневой луч не пускаем вовсе (экономия: 1 луч/пиксель сквозь стену дождя не нужен).
            float dayDirect() { return 1.0 - smoothstep(0.15, 0.5, rainF()); }
            float snowF()    { return skyP2.w < 0.0 ? 1.0 : 0.0; }
            // «Влажность» для луж (2-мин хвост из Java) упакована в ДРОБНУЮ часть фазы луны
            // (push-константы переполнены). Целая часть = фаза, дробная = влажность 0..0.98.
            float wetnessF() { return fract(skyP2.x); }

            // СЛЕДЫ ОТ КАПЕЛЬ: кольцо от КАЖДОГО реального удара (buffer ripples), в его точке и
            // в его момент. Возвращает касательный уклон (xz) нормали воды/лужи. nowTicks = params.y.
            vec2 rainRipple(vec2 pw, float nowTicks){
                int cnt = int(ripples[0].x);
                vec2 slope = vec2(0.0);
                for (int i = 0; i < cnt; i++){
                    vec4 imp = ripples[i + 1];
                    vec2 d = pw - imp.xy;
                    if (abs(d.x) > 1.6 || abs(d.y) > 1.6) continue;   // ПЕРФ: дальние мимо (кольцо ≤~1.4), без sqrt
                    float age = nowTicks - imp.z;
                    if (age < 0.0) age += 3600.0;              // wrap gameTime % 3600
                    if (age > 22.0) continue;                  // жизнь ~1.1с
                    float ageS = age / 20.0;
                    float r = length(d);
                    float wv = r - ageS * 1.2;                 // МЕНЬШЕ радиус: ~1.2 бл/с (макс ~1.3 блока)
                    if (abs(wv) > 0.45) continue;              // только на гребне кольца
                    float ring = sin(wv * 26.0) * exp(-abs(wv) * 9.0)
                               * (1.0 - ageS / 1.1) * exp(-r * 0.5);
                    slope += (r > 1e-3 ? d / r : vec2(0.0)) * ring;
                }
                return slope;
            }
            // В дождь облака гуще (становятся тучами) — прибавка к плотности слоя
            // ⚠️ В ДОЖДЬ/ГРОЗУ ТУЧИ ДОЛЖНЫ БЫТЬ НЕПРОЗРАЧНЫМИ. Прибавки 0.35 не хватало: в зените луч
            // проходит слой почти по кратчайшему пути, оптической толщины набирается мало — и сквозь
            // грозовые тучи отчётливо просвечивала луна, со всеми кратерами. Теперь плотность в грозу
            // растёт заметно сильнее (и солнце днём за тучами тоже прячется — оно перекрывается тем
            // же множителем (1 - cl.a), что и всё небо).
            float rainDens(float d){ return min(d + rainF() * 0.55 + thunderF() * 0.35, 1.0); }

            // ВСПЫШКА МОЛНИИ — процедурная (как CUMULONIMBUS_LIGHTNING у Eclipse: по таймеру, а не
            // по болту-сущности). В грозу раз в ~3 c небо и облака озаряет вспышка с резким
            // нарастанием, затуханием и вторым пиком-«миганием».
            const float RAIN_FALL_SPEED = 18.0;   // блоков/сек — скорость, с которой бежит текстура дождя
            const vec3 LIGHTNING_COL = vec3(0.75, 0.82, 1.0);
            // Позиция вспышки в текущем окне (XZ около игрока): озаряется ЛОКАЛЬНАЯ область облаков
            // у молнии, а не полнеба (в жизни вспышка — пятно, а не весь небосвод).
            vec2 lightningPos(){
                float win = floor(params.y / 20.0 / 3.0);
                float ang = fract(sin(win * 33.13) * 7541.7) * 6.2831853;
                float dst = 150.0 + fract(sin(win * 71.31) * 1231.9) * 450.0;
                return origin.xz + vec2(cos(ang), sin(ang)) * dst;
            }
            float lightningFlash(){
                float th = thunderF();
                if (th < 0.02) return 0.0;
                float sec = params.y / 20.0;                       // params.y — тики -> секунды
                float win = floor(sec / 3.0);                      // окно ~3 c
                if (fract(sin(win * 91.7) * 43758.5453) > 0.55) return 0.0;  // бьёт не в каждом окне
                float local  = fract(sec / 3.0) * 3.0;             // 0..3 c внутри окна
                float strike = fract(sin(win * 12.9) * 9124.3) * 1.5;
                float d = local - strike;
                if (d < 0.0) return 0.0;
                float env = exp(-d * 7.0) + 0.5 * exp(-abs(d - 0.12) * 30.0);
                return clamp(env, 0.0, 1.0) * th;
            }

            // ТИП и КОЛИЧЕСТВО облаков ДНЯ. Оба выбираются раз в сутки (утром) в Java и упакованы
            // в ОДНО поле skyP2.z: packed = amount + type*8 (push-константы переполнены, отдельного
            // поля нет). type 0..3 = кучевые/средние/пласт/перистые. amount = покрытие.
            int   cloudType()   { return int(skyP2.z / 8.0); }
            float cloudAmount() { return skyP2.z - float(cloudType()) * 8.0; }

            // Псевдо-3D поле из 2D-шума — наша формулировка приёма «этажерка»: каждый целый
            // уровень Y уводит 2D-выборку на свой ряд текстуры, а дробная часть Y
            // интерполирует ДВА КАНАЛА одной выборки (канал = соседний этаж).
            float cloudField(vec3 wp){
                vec3 q = wp * (1.0 / 17.5);
                q.x *= 0.5; q.z *= 0.5;
                float fl = floor(q.y);
                vec2 sheet = q.xz + vec2(0.0, 181.0) * fl;
                vec2 duo = texture(noisetex, sheet * (1.0 / 512.0)).yx;
                return mix(duo.x, duo.y, q.y - fl);
            }

            // --- перистые (CIRRUS): Eclipse использует curl-шум ---
            vec2 hash2c(vec2 p){
                vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.xx + p3.yz) * p3.zy);
            }
            vec2 perlin_gradient(vec2 coord){
                vec2 i = floor(coord), f = fract(coord);
                vec2 u  = f * f * (3.0 - 2.0 * f);
                vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
                vec2 g0 = normalize(hash2c(i) - 0.5);
                vec2 g1 = normalize(hash2c(i + vec2(1.0, 0.0)) - 0.5);
                vec2 g2 = normalize(hash2c(i + vec2(0.0, 1.0)) - 0.5);
                vec2 g3 = normalize(hash2c(i + vec2(1.0, 1.0)) - 0.5);
                float v0 = dot(g0, f);
                float v1 = dot(g1, f - vec2(1.0, 0.0));
                float v2 = dot(g2, f - vec2(0.0, 1.0));
                float v3 = dot(g3, f - vec2(1.0, 1.0));
                vec2 omu = 1.0 - u;
                return vec2(((v1 - v0) * omu.y + (v3 - v2) * u.y) * du.x,
                            ((v2 - v0) * omu.x + (v3 - v1) * u.x) * du.y);
            }
            vec2 curl2D(vec2 c){ vec2 g = perlin_gradient(c); return vec2(g.y, -g.x); }

            // === Форма облачного слоя — НАША сборка (clean-room M8.102) ===
            // Принципы обычные для объёмных облаков: «покрытие минус поле шума» с
            // нормировкой, конверт по высоте, двухчастотная эрозия кромки. Композиция,
            // имена, кривые и все константы — собственные. LOD 0 = без эрозии (для теней).
            float cloudCoverage(int layer){
                float amt = cloudAmount();
                float cov = (layer == 0) ? amt
                          : (layer == 1) ? amt * 0.90
                          : (layer == 2) ? amt * 0.60 + 0.2 * rainF()
                                         : amt * 0.45;
                return max(cov, 0.05);              // inversesqrt(0) ниже дал бы NaN
            }
            // Поле «занятости» слоя: крупная маска + мелкая деталь
            float layerField(int layer, vec3 samplePos, vec3 position){
                if (layer == 0) {
                    float big = texture(noisetex, (samplePos.xz + cloudMove()) / 5200.0).b;
                    float sml = 1.0 - texture(noisetex, (samplePos.xz - cloudMove()) / 520.0).r;
                    return abs(big - 0.58) + sml * sml;
                } else if (layer == 1) {
                    float big = texture(noisetex, (samplePos.zx + cloudMove()*3.0) / 10400.0).b;
                    float sml = texture(noisetex, (samplePos.zx - cloudMove()*3.0) / 2600.0).b;
                    return abs(big * -0.68) + sml;
                } else if (layer == 2) {
                    float big = texture(noisetex, (position.xz + cloudMove()*20.0) / 104000.0).b;
                    float sml = 1.0 - texture(noisetex, ((position.xz + vec2(-cloudMove(), cloudMove())*20.0) / 7800.0
                                   - vec2(1.0 - big, -big) / 5.2)).b;
                    return big + sml * 0.42 * clamp(0.88 - big, 0.0, 1.0);
                }
                vec2 coord = position.zx + 6.0 * cloudMove();
                vec2 curl = curl2D(0.000021 * coord) * 0.5
                          + curl2D(0.000052 * coord) * 0.25
                          + curl2D(0.00019 * coord) * 0.125;
                float big = texture(noisetex, (position.xz + cloudMove()*2.0) / 83000.0).b;
                float sml = texture(noisetex, 0.0000052 * coord).r;
                float amp = 0.32, freq = 0.000021, curlS = 1.35;
                for (int i = 0; i < 3; i++) {
                    sml -= texture(noisetex, coord * freq + curl * curlS).r * amp;
                    amp *= 0.5; freq *= 4.0; curlS *= 2.6;
                }
                return abs(big * -0.38) + sml;
            }
            float cloudShape(int layer, vec3 position, int LOD){
                float minHeight, maxHeight, detail;
                if (layer == 0)      { minHeight = CL0_h; maxHeight = CL0_h + CL0_tall; detail = CL0_detail; }
                else if (layer == 1) { minHeight = CL1_h; maxHeight = CL1_h + CL1_tall; detail = CL1_detail; }
                else if (layer == 2) { minHeight = CL2_h; maxHeight = CL2_h + CL2_tall; detail = 0.0; }
                else                 { minHeight = CL3_h; maxHeight = CL3_h + CL3_tall; detail = 0.0; }
                vec3 samplePos = position * vec3(0.26, 0.0052, 0.26);
                float tallness = maxHeight - minHeight;
                float posToMax = maxHeight - position.y;

                float coverage = cloudCoverage(layer);
                // «покрытие минус поле»: чем меньше покрытие дня, тем мягче нормировка
                float shape = clamp((coverage - layerField(layer, samplePos, position))
                                    * inversesqrt(coverage), 0.0, 1.0);
                if (layer == 2) shape *= shape;

                // конверт по высоте: жёсткие пределы, скруглённый низ, спад к макушке
                shape = min(min(shape, clamp(posToMax, 0.0, 1.0)),
                            1.0 - clamp(minHeight - position.y, 0.0, 1.0));
                float b = min(max(position.y - minHeight, 0.0) / 24.0, 1.0);
                float bottomShape = 1.0 - pow(1.0 - b, 5.0);
                float topShape = min(max(posToMax, 0.0) / max(tallness, 1.0), 1.0);
                topShape = min(exp(-0.55 * (1.0 - topShape)), 1.0 - pow(1.0 - topShape, 5.0));
                shape = max(shape - (1.0 - topShape * bottomShape), 0.0);

                if (shape <= 0.001) return 0.0;
                if (layer > 1) return shape;                    // тонкие пелены — без эрозии
                float erodeAmount = 0.52;
                if (LOD < 1) return max(shape - 0.26 * erodeAmount, 0.0);

                // двухчастотная эрозия: крупная волна выгрызает кромку (sqrt(om)), мелкая
                // рвёт её по градиенту высоты; ^4 оставляет работу только у краёв
                samplePos.xz -= cloudMove() / 4.0;
                samplePos.xz += pow(max(position.y - (minHeight + 19.0), 0.0)
                                    / (max(tallness, 1.0) * 0.21), 1.45);
                float om = 1.0 - shape;
                float div = (layer == 0) ? 3.1 : 3.45;
                float falloff = 1.0 - clamp(posToMax / tallness, 0.0, 1.0);
                float fmul = (layer == 0) ? 0.27 : 0.48;
                float erosion = (1.0 - cloudField(samplePos * detail / div)) * sqrt(om)
                              + abs(cloudField(samplePos * detail) - falloff)
                              * 0.72 * (om * om) * (1.0 - falloff * fmul);
                erosion *= erosion; erosion *= erosion;
                return max(shape - erosion * erodeAmount, 0.0);
            }

            float layerDens(int layer){
                if (layer == 0) return rainDens(CL0_dens);
                if (layer == 1) return rainDens(CL1_dens);
                if (layer == 2) return rainDens(CL2_dens);
                return CL3_dens;
            }""",
            """
            // ТЕНЬ ОТ ОБЛАКОВ без марша (наш пересказ известного приёма «одна выборка»):
            // поднимаемся вдоль луча к солнцу до основания слоя и смотрим форму ровно там —
            // тень почти бесплатна, а движется вместе с облаками.
            float cloudShadow(vec3 worldPos){
                vec3 L = sunVec();
                if (L.y < 0.03 || cloudAmount() < 0.02) return 1.0;
                int layer = cloudType();
                float baseH = (layer == 0 ? CL0_h : layer == 1 ? CL1_h : layer == 2 ? CL2_h : CL3_h) + 20.0;
                float rise = max(baseH - worldPos.y, 0.0) / abs(L.y);   // путь вдоль луча до слоя
                float cs = cloudShape(layer, worldPos + L * rise, 0) * layerDens(layer);
                cs *= CLOUD_SHADOW_AMOUNT / 100.0;
                return exp(-200.0 * cs * cs);
            }

            vec3 skyBaseNoClouds(vec3 d);   // предварительное объявление (марш берёт его как амбиент)

            // Марш ОДНОГО слоя. ⚠️ maxT — дистанция до ГЕОМЕТРИИ: облако за блоком СРЕЗАЕТСЯ,
            // а облако перед блоком видно. Слой берём как плиту: работает и когда камера ВНУТРИ
            // облаков (полёт сквозь) — тогда марш стартует прямо от камеры.
            // Пересечение луча со СФЕРОЙ (для кривизны планеты)
            vec2 raySphere(vec3 ro, vec3 rd, vec3 c, float r){
                vec3 oc = ro - c;
                float b = dot(oc, rd);
                float cc = dot(oc, oc) - r*r;
                float disc = b*b - cc;
                if (disc < 0.0) return vec2(-1.0, -1.0);
                float sq = sqrt(disc);
                return vec2(-b - sq, -b + sq);
            }

            vec4 marchLayer(vec3 ro, vec3 rd, float maxT, int layer, int steps, int sunSteps){
                float minH, maxH;
                if (layer == 0)      { minH = CL0_h; maxH = CL0_h + CL0_tall; }
                else if (layer == 1) { minH = CL1_h; maxH = CL1_h + CL1_tall; }
                else if (layer == 2) { minH = CL2_h; maxH = CL2_h + CL2_tall; }
                else                 { minH = CL3_h; maxH = CL3_h + CL3_tall; }

                // ⚠️ КРИВИЗНА ПЛАНЕТЫ. Раньше слой был БЕСКОНЕЧНОЙ ПЛОСКОЙ ПЛИТОЙ: облака тянулись
                // ровным потолком и обрывались жёсткой линией «в пустоту». Теперь слой — СФЕРИЧЕСКАЯ
                // ОБОЛОЧКА: у горизонта она заворачивает вниз, и облака уходят за него сами,
                // с естественной кривизной.
                vec3 pc = vec3(ro.x, -PLANET_R, ro.z);         // центр планеты под камерой
                vec2 si = raySphere(ro, rd, pc, PLANET_R + minH);
                vec2 so = raySphere(ro, rd, pc, PLANET_R + maxH);
                if (so.y <= 0.0) return vec4(0.0);             // оболочку не задели вовсе

                float camR = length(ro - pc);                  // радиус камеры
                float t0, t1;
                if (camR < PLANET_R + minH) {                  // камера ПОД слоем (обычный случай)
                    if (si.y <= 0.0) return vec4(0.0);
                    t0 = si.y; t1 = so.y;
                } else if (camR > PLANET_R + maxH) {           // камера НАД слоем
                    t0 = max(so.x, 0.0);
                    t1 = (si.x > 0.0) ? si.x : so.y;
                } else {                                       // камера ВНУТРИ слоя (полёт сквозь)
                    t0 = 0.0;
                    t1 = (si.x > 0.0) ? si.x : so.y;
                }
                t1 = min(min(t1, maxT), CLOUD_FAR);            // срез геометрией + потолок дальности
                if (t1 <= t0) return vec4(0.0);

                float dens0 = layerDens(layer);
                float dt = (t1 - t0) / float(steps);
                vec3 L = SUN_DIR_f();      // ночью — направление на ЛУНУ (иначе самотень «сквозь землю»)
                float dF = dayAmt();
                // ⚠️ ОСНОВАНИЕ ТУЧИ — СВИНЦОВОЕ, А НЕ ГОЛУБОЕ. Амбиент облака я брал как цвет ЗЕНИТА
                // НЕБА, и вот что выходило: солнце сквозь 130 м плотной тучи почти не пробивает, так
                // что весь цвет основания = голубое небо, лишь притушенное. Снизу туча получалась ТОГО
                // ЖЕ ОТТЕНКА, ЧТО НЕБО ЗА НЕЙ, — и казалась жидкой, хотя была непрозрачной (сверху те
                // же тучи выглядят плотной белой пеленой — их освещает солнце).
                // В дождь уводим амбиент в нейтральный свинцовый: туча снизу тёмная, как в жизни.
                // ⚠️ И свинцовый цвет тоже ГАСНЕТ НОЧЬЮ: это рассеянный ДНЕВНОЙ свет, а не собственное
                // свечение туч. Без множителя ночная гроза светилась ровным серым — «слишком ярко».
                vec3 ambC = mix(skyBaseNoClouds(vec3(0.0, 1.0, 0.0)) * 0.42,
                                vec3(0.055, 0.058, 0.065) * mix(0.10, 1.0, dayAmt()), rainF());
                vec3 sunC = SUN_COL_f() * mix(0.95, 0.30, rainF());
                // НОЧЬ: облако с альбедо ~1 иначе вспыхивает белым (было видно в полночь). Гасим
                // до лунного полумрака — днём (dF=1) множители = 1.0, ничего не меняют.
                sunC *= mix(0.16, 1.0, dF);
                ambC *= mix(0.30, 1.0, dF);
                float flash = lightningFlash();          // молния подсвечивает облако изнутри
                vec2  flashPos = lightningPos();

                // ДИЗЕР СТАРТА. Шаги марша стоят фиксированной сеткой, и их границы складываются в
                // видимые СЛОИ — те самые «полосатые» облака (классический бандинг объёмного марша).
                // Сдвигаем старт на случайную долю шага: полосы рассыпаются в шум. Раньше это было бы
                // лекарством хуже болезни, но теперь шум съедает DLSS — потому и стало можно.
                float dither = hash12_alt(vec2(gl_GlobalInvocationID.xy)
                                          + mod(cam.frameInfo.x, 64.0) * 7.31);

                float trans = 1.0;
                vec3 scat = vec3(0.0);
                for (int i = 0; i < steps; i++) {
                    float t = t0 + dt * (float(i) + dither);
                    vec3 p = ro + rd * t;
                    // ВЫСОТУ берём от СФЕРЫ, а не от плоскости — иначе форма не искривится
                    p.y = length(p - pc) - PLANET_R;
                    float dens = cloudShape(layer, p, 1) * dens0;
                    if (dens <= 0.002) continue;

                    float od = 0.0;
                    for (int j = 1; j <= sunSteps; j++) {
                        vec3 sp = p + L * (float(j) * 22.0);
                        od += cloudShape(layer, sp, 0) * dens0 * 22.0;
                    }
                    float sunT = exp(-od * 0.075);
                    // «пороховой» отклик: у кромки облако темнее, в толще светлее (запись своя)
                    float powder = 1.0 - exp2(-dens * 8.8);
                    vec3 lum = sunC * sunT * (0.34 + 0.66 * powder) + ambC;
                    float lspat = exp(-length(p.xz - flashPos) * 0.006);   // пятно у молнии, ~200 блоков
                    lum += LIGHTNING_COL * flash * lspat * (0.5 + 0.8 * powder) * 9.0;   // локальная вспышка в туче

                    // АТМОСФЕРНАЯ ПЕРСПЕКТИВА: дальние облака тают в дымке, а не рубятся краем
                    float atm = exp(-t * 0.00013);
                    lum = mix(skyBaseNoClouds(rd), lum, atm);

                    // ⚠️ ОПТИЧЕСКАЯ ТОЛЩИНА, А НЕ ПРОСТО «ПЛОТНОСТЬ». Свет за тучей гаснет как
                    // exp(-tau), и луна пробивала не потому, что туч мало, а потому, что tau мала:
                    // при tau≈2 сквозь тучу проходит ещё 12% — а луна в HDR настолько ярка, что эти
                    // 12% видны диском с кратерами. В дождь поднимаем коэффициент затухания в 2.5
                    // раза: tau уходит за 5, и пропускание падает до долей процента.
                    float tr = exp(-dens * dt * (0.055 + 0.080 * rainF()));
                    scat += trans * (1.0 - tr) * lum;
                    trans *= tr;
                    if (trans < 0.02) break;
                }
                return vec4(scat, 1.0 - trans);
            }

            // ОТРАЖЕНИЯ БЛОКОВ (M8.24): свойства материала по matId (карта материалов).
            // tint = цвет отражения (у металлов — их F0-цвет), f0 = база Френеля, strength = сила.
            void matProps(uint id, out vec3 tint, out float f0, out float strength){
                // ⚠️ СИЛА ОТРАЖЕНИЯ (strength) — это «сколько зеркала поверх своей текстуры». При 0.85-0.90
                // блок золота превращался в зеркальную плиту: текстура слитков НЕ ЧИТАЛАСЬ (замечено в игре).
                // Металл остаётся металлом за счёт высокого F0 и окраски отражения текселем (см. ниже).
                if (id == 1u)      { tint = vec3(1.00, 0.86, 0.57); f0 = 0.60; strength = 0.68; }  // золото
                else if (id == 2u) { tint = vec3(0.77, 0.78, 0.80); f0 = 0.55; strength = 0.62; }  // железо
                else if (id == 3u) { tint = vec3(0.95, 0.64, 0.54); f0 = 0.50; strength = 0.64; }  // медь
                else if (id == 4u) { tint = vec3(0.42, 0.40, 0.44); f0 = 0.35; strength = 0.50; }  // незерит
                else if (id == 5u) { tint = vec3(0.95, 0.98, 1.00); f0 = 0.17; strength = 0.65; }  // алмаз
                else if (id == 6u) { tint = vec3(0.55, 1.00, 0.70); f0 = 0.08; strength = 0.55; }  // изумруд
                else if (id == 7u) { tint = vec3(0.45, 0.40, 0.55); f0 = 0.05; strength = 0.50; }  // обсидиан
                else if (id == 8u) { tint = vec3(0.35, 0.50, 0.95); f0 = 0.06; strength = 0.45; }  // лазурит
                else if (id == 9u) { tint = vec3(1.00, 0.30, 0.25); f0 = 0.06; strength = 0.45; }  // редстоун
                // --- M8.26: вторая волна (id >= 16). Диэлектрики: F0 ~0.04-0.05 (реальные значения),
                // сила отражения = «насколько гладко отполирована» поверхность.
                else if (id == 16u) { tint = vec3(1.00, 1.00, 1.00); f0 = 0.05; strength = 0.22; }  // полированный камень
                else if (id == 17u) { tint = vec3(1.00, 0.98, 0.96); f0 = 0.046; strength = 0.32; } // кварц (IOR 1.55)
                else if (id == 18u) { tint = vec3(1.00, 1.00, 1.00); f0 = 0.05; strength = 0.50; }  // глазурь (эмаль)
                else if (id == 19u) { tint = vec3(1.00, 1.00, 1.00); f0 = 0.05; strength = 0.20; }  // руда (блеск вкраплений)
                else if (id == 20u) { tint = vec3(0.85, 0.62, 1.00); f0 = 0.08; strength = 0.55; }  // аметист (кристалл)
                else                { tint = vec3(0.72, 0.95, 0.92); f0 = 0.05; strength = 0.35; }  // призмарин (21)
            }

            // Все слои, композит ПО ХОДУ ЛУЧА (вверх: 0,1,2,3; вниз: наоборот)
            // ⚠️ РАНЬШЕ марш шёл по ВСЕМ 4 слоям сразу — они наслаивались и рябили («каша»).
            // Теперь ТИП облаков выбирается раз в день (cloudType), и марширует ТОЛЬКО он. Один
            // слой вместо четырёх — и красивее (нет каши), и намного быстрее.
            vec4 marchClouds(vec3 ro, vec3 rd, float maxT, int q){
            #ifndef RT_CLOUDS
                // ⚠️ ВЫРЕЗАНО ПРЕПРОЦЕССОРОМ, А НЕ ОБОЙДЕНО УСЛОВИЕМ. Условие `if(выкл) return` не
                // экономит НИЧЕГО: видеокарта выделяет регистры под САМЫЙ ТЯЖЁЛЫЙ путь исполнения,
                // и код облаков продолжает их занимать, даже не исполняясь. Безусловный return
                // делает весь код ниже недостижимым — драйвер выбрасывает его, регистры свободны,
                // потоков в полёте больше. Замер сказал: платим мы за пиксели, а не за лучи.
                return vec4(0.0);
            #endif
                if (cam.rtCfg.w < 0.5) return vec4(0.0);   // облака выключены -> чистое небо
                if (cloudAmount() < 0.02) return vec4(0.0);         // «ни облачка»
                int layer = cloudType();
                int steps, sunSteps;
                if (layer <= 1) { steps = (q == 0 ? 18 : 9); sunSteps = (q == 0 ? 5 : 2); }
                else            { steps = (q == 0 ? 10 : 5); sunSteps = (q == 0 ? 3 : 1); }  // пелены дешевле
                // M8.144o: в ДОЖДЬ тучи ПЛОТНЫЕ (rainDens) -> марш насыщается быстро, а деталь всё
                // равно застлана серым overcast и самим дождём. Режем число шагов по силе дождя —
                // крупная экономия (объёмные облака ~половина бюджета шейдера), вид почти не страдает.
                float rf = rainF();
                if (rf > 0.3) {
                    float k = mix(1.0, 0.33, smoothstep(0.3, 0.85, rf));
                    steps    = max(int(float(steps) * k), 3);
                    sunSteps = max(int(float(sunSteps) * k), 1);
                }
                return marchLayer(ro, rd, maxT, layer, steps, sunSteps);
            }

            vec3 skyBaseNoClouds(vec3 d){
                float up = clamp(d.y * 0.5 + 0.5, 0.0, 1.0);      // 0 надир .. 1 зенит

                // === M8.130 ЭНД: тёмное сине-фиолетовое небо (СВОЯ реализация; ориентир — Энд
                // Eclipse: почти чёрный низ, фиолетово-розовый подсвет у затменного светила).
                // Туман Энда красится ЭТИМ же небом (fog = mix(scene, skyBase, fogAmt)) — розово-
                // фиолетовый по просьбе. Небо статично (в Энде нет цикла суток).
                if (dimType() == 2) {
                    // Лавандовая дымка Энда: СВЕТЛАЯ и мягкая (в неё растворяется дальний террейн
                    // через fogAmt) — тёмная база давала «плоско». Ярче к зениту (up^2), у
                    // горизонта чуть темнее для объёма. Звёзды остаются видны — они добавляются
                    // поверх и ярче фона.
                    vec3 haze = vec3(0.44, 0.36, 0.62);           // мягкий светлый лавандовый
                    float lift = pow(up, 2.0);
                    vec3 col = haze * mix(0.32, 0.66, lift);
                    // розово-фиолетовое зарево вокруг светила — ТУГОЕ (широкое пятно убрано)
                    float toOrb = max(dot(d, endOrb()), 0.0);
                    col += vec3(0.14, 0.07, 0.18) * pow(toOrb, 60.0) * 0.7;
                    return col;
                }
                // === M8.133 АД: небо/дальняя дымка = цвет тумана биома (приходит в rtCfg6).
                // Неба в Аду нет (потолок бедрока), но fogAmt красит горизонт этим цветом.
                if (dimType() == 1) {
                    return cam.rtCfg6.rgb * mix(0.75, 1.1, up);
                }

                float dayF = dayAmt(), ss = sunsetAmt();

                // --- ДЕНЬ: голубой градиент ---
                vec3 dayHor = vec3(0.62, 0.76, 0.95);
                vec3 dayZen = vec3(0.18, 0.42, 0.86);
                vec3 dayCol = mix(dayHor, dayZen, smoothstep(0.0, 0.72, up)) * 1.7;

                // --- НОЧЬ: тёмно-синее + ЗВЁЗДЫ ---
                vec3 nightHor = vec3(0.007, 0.011, 0.024);   // темнее: ночь глубже (просьба)
                vec3 nightZen = vec3(0.0013, 0.0022, 0.007);
                vec3 nightCol = mix(nightHor, nightZen, smoothstep(0.0, 0.75, up));

                vec3 col = mix(nightCol, dayCol, dayF);
                // ⚠️ ЗВЁЗД ЗДЕСЬ БОЛЬШЕ НЕТ — они переехали в stars() и добавляются только к ВИДИМОМУ
                // небу (первичный луч и отражения). Причина: это небо сэмплит АМБИЕНТ случайными
                // лучами, и луч, попавший точно в звезду — яркую точку на почти чёрном фоне, — давал
                // выброс в сотни раз выше среднего. Такие «светлячки» временной денойзер погасить не
                // может: одиночный экстремум он размазывает в пятно — те самые «пастельные полосы»,
                // которые возвращались ночью. Звёзды и физически мир не освещают.

                // --- НАШ ЗАКАТ/РАССВЕТ (в Eclipse такого нет — добавляем своё) ---
                // Двухслойный градиент, РАЗВЁРНУТЫЙ В СТОРОНУ СОЛНЦА: жёлто-персиковый у самого
                // горизонта -> розово-сиреневый выше. Именно направленность даёт «настоящий»
                // закат, а не равномерную заливку.
                if (ss > 0.01) {
                    vec2 dh = normalize(vec2(d.x, d.z) + 1e-5);
                    vec2 sh = normalize(vec2(sunVec().x, sunVec().z) + 1e-5);
                    float toward = pow(max(dot(dh, sh), 0.0), 2.2);     // к солнцу
                    float lowBand = pow(max(1.0 - abs(d.y), 0.0), 6.0); // у самого горизонта
                    float midBand = pow(max(1.0 - abs(d.y - 0.22), 0.0), 4.0);

                    vec3 gold = vec3(1.35, 0.78, 0.34);                 // жёлто-персиковый
                    vec3 pink = vec3(1.05, 0.45, 0.62);                 // розово-сиреневый
                    // Закат чуть краснее рассвета (вечером в воздухе больше пыли) — sky.w > 0.5
                    float evening = step(0.5, skyP.w);
                    gold = mix(gold, gold * vec3(1.06, 0.92, 0.86), evening);
                    pink = mix(pink, pink * vec3(1.10, 0.90, 0.95), evening);

                    col += (gold * lowBand + pink * midBand * 0.85) * toward * ss * 1.25;
                    // лёгкая подсветка всего неба тёплым (рассеяние)
                    col += gold * 0.10 * ss * (1.0 - up);
                }

                // === M8.144f: ДОЖДЬ/ГРОЗА -> СЕРАЯ МГЛА. Ясное синее небо под дождём давало
                // «синее пятно» вдали (туман красится ЭТИМ небом: mix(scene, skyBase, fogAmt)).
                // Десатурируем к прохладному серому + притемняем по силе осадков; гроза ещё темнее.
                // Чинит разом видимое небо, дальний туман и амбиент (он сэмплит это же небо).
                float rf = rainF();
                if (rf > 0.001) {
                    // Пасмурная мгла = ПРОХЛАДНЫЙ СЛАНЦЕВО-СИНИЙ (аэроперспектива), не плоский белёсый
                    // серый. Уровень по времени суток; лёгкий градиент: у горизонта светлее/нейтральнее,
                    // к зениту темнее и синее -> дальние горы = мягкая синяя дымка, а не яркий блин.
                    float ov    = mix(0.035, 0.34, dayAmt());
                    float horiz = pow(clamp(1.0 - abs(d.y), 0.0, 1.0), 2.5);
                    vec3  tint  = mix(vec3(0.55, 0.64, 0.82),           // зенит — синее/холоднее
                                      vec3(0.82, 0.86, 0.92), horiz);   // горизонт — светлее/нейтральнее
                    vec3  overcast = ov * tint * (1.0 - 0.40 * thunderF());
                    col = mix(col, overcast, clamp(rf, 0.0, 1.0) * 0.90);
                }
                return col;
            }

            // Небо БЕЗ облаков — им светится амбиент мира (марш в амбиент не тянем: дорого)
            vec3 skyBase(vec3 d){ return skyBaseNoClouds(d); }

            // Звёзды — только для ВИДИМОГО неба: первичный луч и отражения. В освещение не идут.
            // === M8.103 ПАДАЮЩИЕ ЗВЁЗДЫ (своя аналитика): изредка ночью чиркает метеор.
            // Окна по 9 с: хеш окна решает, будет ли метеор (~45% окон), точку и направление.
            vec3 meteors(vec3 d){
                // M8.144p: метеоры ТОЛЬКО в ЯСНОМ небе — при облачности их застят тучи. Раньше
                // «летали по тучам» (яркий метеор просвечивал сквозь неплотную тучу поверх облаков).
                float clear = 1.0 - smoothstep(0.12, 0.40, cloudAmount());
                if (clear < 0.01) return vec3(0.0);
                float T = params.y / 20.0;
                float win = floor(T / 5.5);
                float ph  = T - win * 5.5;
                if (ph > 0.75 || starHash(vec2(win, 3.7)) < 0.40) return vec3(0.0);   // чаще (просьба)
                float life = ph / 0.75;                                  // 0..1 за ~0.75 с
                float az = starHash(vec2(win, 1.1)) * 6.2831853;
                float el = mix(0.35, 1.05, starHash(vec2(win, 2.2)));    // высоко над горизонтом
                vec3 P0 = vec3(cos(az)*cos(el), sin(el), sin(az)*cos(el));
                float az2 = az + mix(2.2, 4.1, starHash(vec2(win, 4.4)));
                vec3 M = normalize(vec3(cos(az2), -0.75, sin(az2)));     // вниз-вбок
                vec3 head = normalize(P0 + M * (0.06 + 0.34 * life));
                vec3 tang = normalize(M - head * dot(M, head));          // касательная движения
                vec3 rel = d - head;
                float along = dot(rel, tang);
                float perp = length(rel - tang * along);
                float trail = 0.055;                                     // длина хвоста (рад)
                if (along > 0.004 || along < -trail || perp > 0.01) return vec3(0.0);
                float tail = 1.0 + along / trail;                        // 1 у головы -> 0 в хвосте
                float line = exp(-perp * perp * 5.0e5);
                float fade = sin(min(life, 1.0) * 3.14159265);
                return vec3(0.85, 0.92, 1.0) * line * tail * tail * fade * 5.0 * clear;
            }

            // === M8.103 СЕВЕРНОЕ СИЯНИЕ (своя аналитика): холодные биомы, только ночью.
            // «Занавес»: лента по шуму (изгиб warp'ом), вертикальные лучи, зелёный низ ->
            // фиолетовый верх. Сила cam.rtCfg3.w приходит из Java (снежный биом, плавно ~6 с).
            vec3 aurora(vec3 d){
                return vec3(0.0);   // M8.144r: северное сияние ВРЕМЕННО отключено (просьба). Вернуть — убрать эту строку.
                float a = cam.rtCfg3.w;
                if (a < 0.01 || d.y < 0.04) return vec3(0.0);
                float T = params.y / 20.0;
                vec2 p = d.xz / (d.y + 0.35);
                float warp = texture(noisetex, p * 0.045 + vec2(T * 0.006, 0.0)).r * 1.5;
                float band = texture(noisetex, vec2(p.x * 0.055 + warp * 0.4, T * 0.011)).b;
                float curtain = smoothstep(0.55, 0.80, band);
                float rays = 0.55 + 0.45 * texture(noisetex, vec2(p.x * 0.6 + warp * 0.8, T * 0.05)).b;
                float alt = smoothstep(0.04, 0.25, d.y) * (1.0 - smoothstep(0.5, 0.95, d.y));
                vec3 colA = mix(vec3(0.10, 0.90, 0.42), vec3(0.30, 0.22, 0.80),
                                smoothstep(0.10, 0.55, d.y));
                return colA * (curtain * rays * alt * a * 0.9);
            }

            vec3 stars(vec3 d){
                vec3 n = normalize(d);
                // M8.132: ЭНД висит в пустоте — звёзды по ВСЕЙ сфере (и снизу), всегда горят
                // (нет цикла суток). Без метеоров/сияния — они атрибут верхнего неба.
                if (dimType() == 2) return starsCol(n) * 1.15;
                if (d.y <= -0.06) return vec3(0.0);
                float f = starFade();
                if (f <= 0.002) return vec3(0.0);
                return (starsCol(n) * 1.15 + meteors(n) + aurora(n)) * f;
            }

            // Небо с диском СОЛНЦА, ЛУНОЙ и ОБЛАКАМИ (для первичного луча)
            // ⚠️ СВЕТИЛА ОТДЕЛЬНО ОТ ФОНА. Диск луны/солнца в HDR ярче ночного неба в СОТНИ раз, и
            // даже 1% пропускания сквозь тучу даёт отчётливый диск с кратерами. Честное exp(-tau) тут
            // не спасает: при 18 шагах марша оптическая толщина недобирается. Поэтому светила гасим
            // облачностью ОТДЕЛЬНО и в кубе — фон при этом гаснет линейно, как и должен.
            vec3 skyBodies(vec3 d){
                vec3 col = vec3(0.0);

                // === M8.130 СВЕТИЛО ЭНДА: затменный диск — тёмная сердцевина + яркий ободок
                // (СВОЯ реализация; ориентир — «затменная копия солнца, крупнее» из Eclipse).
                if (dimType() == 2) {
                    // ⚠️ ТОЛЬКО АДДИТИВНЫЙ СВЕТ: корона + ореол. Тёмное ТЕЛО диска перекрывает
                    // небо не здесь (сложение не затемняет фон) — оно затемняется в композите
                    // main по маске endOrbRad. Раньше mix внутри skyBodies шёл по локальному
                    // col=0 и через «col += bodies» небо не чернил (репорт: диск цвета неба).
                    float r = endOrbRad(d);
                    float dp = max(dot(d, endOrb()), 0.0);
                    // корона — яркое тонкое кольцо на кромке диска
                    float ring = smoothstep(0.86, 0.97, r) * smoothstep(1.06, 0.97, r);
                    col += vec3(0.95, 0.55, 1.15) * ring * 1.8;
                    // ОРЕОЛ как у солнца: мягкое свечение наружу от диска + тугой дальний
                    col += vec3(0.42, 0.20, 0.56) * exp(-max(r - 1.0, 0.0) * 3.0) * 0.55;
                    col += vec3(0.26, 0.12, 0.36) * pow(dp, 300.0) * 0.6;
                    return col;
                }

                // СОЛНЦЕ. ⚠️ M8.144n: в ДОЖДЬ пасмурно -> диск/ореол/дымка СКРЫТЫ (иначе ореол
                // просвечивал ярким пятном сквозь тучи). Гасим по силе дождя (rainF), не только sunUp.
                float bodyVis = smoothstep(-0.10, 0.02, sunH()) * (1.0 - rainF());
                float s = max(dot(d, sunVec()), 0.0);
                float sunUp = bodyVis;
                col += blackbody(6300.0) * (pow(s, 700.0) * 6.0 + 0.05 * pow(s, 48.0)) * sunUp;
                // тёплая дымка у горизонта в сторону солнца (усиливается на закате)
                vec2 dh = normalize(vec2(d.x, d.z) + 1e-4);
                vec2 sh = normalize(vec2(sunVec().x, sunVec().z) + 1e-4);
                float glow = pow(max(1.0 - abs(d.y), 0.0), 5.0) * pow(max(dot(dh, sh), 0.0), 3.0);
                col += vec3(1.00, 0.52, 0.22) * glow * (0.5 + 1.2 * sunsetAmt()) * sunUp;
                // ЛУНА (напротив солнца), холодная — Moon_temp 8000K
                // === M8.19 ЛУНА С ТЕКСТУРОЙ (наш код; ориентир — идея «карты всей сферы») ===
                // Луна хранится equirect-картой (NASA SVS, public domain) и рисуется освещённым шаром.
                // Проецируем видимое полушарие: точка диска -> нормаль шара -> equirect-UV.
                // ФАЗУ берём из MC (skyP2.x): освещаем шар виртуальным солнцем под углом фазы —
                // терминатор получается сам, физически (0 = полнолуние, 4 = новолуние).
                float m = max(dot(d, moonVec()), 0.0);
                col += blackbody(8000.0) * pow(m, 90.0) * 0.05 * moonUp() * (1.0 - rainF());   // мягкий ореол (в дождь скрыт)
                {
                    vec3 md = moonVec();
                    float dp = dot(d, md);
                    if (dp > 0.90 && moonUp() > 0.001) {
                        const float MOON_R = 0.055;                 // угловой радиус (tan)
                        vec3 up0 = abs(md.y) < 0.99 ? vec3(0,1,0) : vec3(1,0,0);
                        vec3 tx = normalize(cross(up0, md));
                        vec3 ty = cross(md, tx);
                        vec3 pj = d / max(dp, 1e-4);                // на плоскость диска
                        vec2 duv = vec2(dot(pj, tx), dot(pj, ty)) / MOON_R;
                        float r2 = dot(duv, duv);
                        if (r2 < 1.0) {
                            // нормаль шара в локальных координатах (+z — к нам)
                            vec3 nrm3 = vec3(duv.x, duv.y, sqrt(max(1.0 - r2, 0.0)));
                            // equirect-UV поверхности Луны
                            vec2 muv = vec2(atan(nrm3.x, nrm3.z) / 6.2831853 + 0.5,
                                            acos(clamp(nrm3.y, -1.0, 1.0)) / 3.14159265);
                            vec3 surf = texture(moonTex, muv).rgb;
                            // ФАЗА: угол виртуального солнца. 0 -> свет со стороны наблюдателя
                            // (полнолуние), 4 -> из-за луны (новолуние).
                            float pa = floor(skyP2.x) / 8.0 * 6.2831853;   // дробную часть занимает wetness
                            vec3  L  = vec3(sin(pa), 0.0, cos(pa));
                            float lit = smoothstep(-0.06, 0.18, dot(nrm3, L));
                            float edge = smoothstep(1.0, 0.72, r2);   // ШИРОКОЕ затухание края:
                            // на внутреннем 1280x720 узкий край давал «лесенку», плюс у самого
                            // лимба проекция сферы вырождается и текстура рвётся.
                            col += surf * lit * edge * 2.4 * moonUp();
                        }
                    }
                }
                // ⚠️ Облака здесь НЕ рисуем: им нужна дистанция до ГЕОМЕТРИИ (иначе облако перед
                // постройкой не видно, а за ней — не срезается). Их кладёт main, маршем от камеры
                // до p1T. См. M8.20.
                return col;
            }
            vec3 sky(vec3 d){ return skyBase(d) + stars(d) + skyBodies(d); }

            // Небо для ОТРАЖЕНИЯ воды: тугое светило БЕЗ широкого ореола — иначе на гранях волн
            // ореол выгорает в белую кляксу.
            vec3 skyRefl(vec3 d){
                vec3 col = skyBase(d) + stars(d);
                float s = max(dot(d, sunVec()), 0.0);
                float sunUp = smoothstep(-0.10, 0.02, sunH());
                // M8.119: тугой диск (pow 600) в отражении по волнам даёт одиночные искры на
                // пиксель — DLSS-денойзер гасит их как шум, и солнечной ДОРОЖКИ на воде не было.
                // Широкие слабые лепестки (pow 40/9) дают стабильное зарево, которое переживает
                // денойз и по волновым нормалям растягивается в дорожку.
                col += SUN_COL_f() * (pow(s, 600.0) * 3.0 + pow(s, 220.0) * 0.35
                                    + pow(s, 40.0) * 0.16 + pow(s, 9.0) * 0.03) * sunUp;
                float m = max(dot(d, moonVec()), 0.0);
                col += blackbody(8000.0) * (pow(m, 500.0) * 1.2 + pow(m, 40.0) * 0.08) * moonUp();
                vec4 cl = marchClouds(origin.xyz, d, 1e9, 1);   // облака отражаются в воде (дешёвое качество)
                col = col * (1.0 - cl.a) + cl.rgb;
                return col;
            }

            // Пер-пиксельный ГПСЧ для лучей амбиента
            // ⚠️ ХЕШ, А НЕ ПРОСТО LCG. Зерно строилось как (y*width + x)*C, а дальше крутился обычный
            // LCG. У соседних пикселей зёрна отличаются на константу, и LCG эту связь НЕ РАЗРУШАЕТ:
            // «случайные» лучи ложатся правильной решёткой, и по всем поверхностям идёт диагональная
            // штриховка под 45°. Ночью, где сигнал слабый, она видна особенно ясно — те самые полосы.
            // PCG перемешивает биты как следует, поэтому соседние пиксели становятся независимыми.
            uint pcg(uint v){
                uint state = v * 747796405u + 2891336453u;
                uint word  = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
                return (word >> 22u) ^ word;
            }
            float rnd(inout uint s){ s = pcg(s); return float(s >> 8) * (1.0/16777216.0); }
            vec3 cosineDirUV(vec3 n, float u1, float u2){
                float r = sqrt(u1), phi = 6.2831853 * u2;
                vec3 up = abs(n.z) < 0.999 ? vec3(0,0,1) : vec3(1,0,0);
                vec3 tx = normalize(cross(up, n)), ty = cross(n, tx);
                return normalize(tx*(r*cos(phi)) + ty*(r*sin(phi)) + n*sqrt(max(0.0, 1.0-u1)));
            }
            vec3 cosineDir(vec3 n, inout uint seed){
                return cosineDirUV(n, rnd(seed), rnd(seed));
            }
            /**
             * СТРАТИФИЦИРОВАННОЕ направление: полусфера делится на клетки, и каждый луч берётся из
             * СВОЕЙ клетки (со случайным сдвигом внутри неё).
             * ⚠️ Зачем: чисто случайные лучи иногда кучкуются в одном углу полусферы и мажут мимо
             * остального неба — отсюда большой разброс между соседними пикселями. Обычно это лечит
             * временное накопление, но при РЕЗКОМ ПОВОРОТЕ камеры открываются пиксели, которых в
             * прошлом кадре не было (дисокклюзия): истории нет, и DLSS вынуждена показать ОДИН
             * сэмпл как есть. Тогда весь разброс и вылезает шумом. Стратификация режет его в разы
             * бесплатно — лучей столько же.
             */
            vec3 cosineDirStrat(vec3 n, inout uint seed, int i, int N){
                int nx = 2, ny = (N + 1) / 2;
                int sx = i % nx, sy = i / nx;
                float u1 = (float(sx) + rnd(seed)) / float(nx);
                float u2 = (float(sy) + rnd(seed)) / float(ny);
                return cosineDirUV(n, u1, u2);
            }
            """,
            // M8.153: блок ШЕЙДЕРА РАЗБИТ НАДВОЕ. Причина не в логике: строковая константа
            // в class-файле не может превышать 64 КБ в UTF-8, а кириллица занимает по два
            // байта на символ — комментарии перевесили. Склейка идёт по порядку, поэтому на
            // сам GLSL разрез не влияет; резать только по границе функций.
            // M8.162: сюда же встал блок PBR-материалов из ресурспаков — отдельной константой,
            // чтобы не перевалить 64 КБ в уже набитых блоках (см. тот же предел выше).
            """
            // ==================== M8.162 PBR-МАТЕРИАЛЫ ИЗ РЕСУРСПАКОВ (labPBR) ====================
            // Паки (AVPBR Retextured, SPBR, Vanilla PBR и прочие) кладут рядом с текстурой блока две
            // карты: <имя>_n (нормаль) и <имя>_s (спека). Java собирает из них ДВЕ текстуры в UV
            // атласа (см. RtPbrMaps), шейдер сэмплит их по уже известному uv попадания.
            // Разбор строго по labPBR 1.3: _s.R — перцептивная ГЛАДКОСТЬ (шероховатость = (1-s)^2),
            // _s.G — F0 (0..229 линейно) или МЕТАЛЛ (230..237 по таблице, 255 = F0 равно альбедо),
            // _s.A — ЭМИССИЯ (255 = не светится), _n.RG — нормаль (DirectX), _n.B — у пака AO
            // (у нас занято Z, потому что Z мы считаем сами, как велит стандарт).
            // ВАЖНО: «нет данных» = плоская нормаль и полностью погашенная спека, поэтому НЕ НУЖЕН
            // признак «здесь есть пак»: отсутствие данных само ничего не добавляет.
            #ifdef RT_PBR

            // ТАНГЕНТ-БАЗИС ПОПАВШЕГО ТРЕУГОЛЬНИКА — из ЕГО ЖЕ позиций и UV, то есть из той самой
            // развёртки, по которой рисовал художник. Так нормаль ложится правильно и на верхнюю
            // грань, и на боковую, и на наклонную (ступени, забор, листва) без таблиц осей.
            // labPBR хранит нормали в DirectX-виде (зелёный растёт ВНИЗ по картинке), а ∂P/∂v в MC
            // смотрит ровно в сторону роста V — значит базис берём КАК ЕСТЬ, без разворота зелёного.
            void triFrame(vec3 p0, vec3 p1, vec3 p2, vec2 t0, vec2 t1, vec2 t2, vec3 Ng, out vec3 T, out vec3 B){
                vec3 n = normalize(Ng);
                vec3 e1 = p1 - p0, e2 = p2 - p0;
                vec2 d1 = t1 - t0, d2 = t2 - t0;
                float det = d1.x * d2.y - d2.x * d1.y;
                if (abs(det) < 1e-12) {                       // вырожденная развёртка — плоскость по нормали
                    T = normalize(cross(abs(n.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0), n));
                    B = cross(n, T);
                    return;
                }
                float r = 1.0 / det;
                vec3 T0 = (e1 * d2.y - e2 * d1.y) * r;
                vec3 B0 = (e2 * d1.x - e1 * d2.x) * r;
                T = T0 - n * dot(T0, n);
                T = length(T) > 1e-6 ? normalize(T) : normalize(cross(abs(n.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0), n));
                B = B0 - n * dot(B0, n);
                B = length(B) > 1e-6 ? normalize(B) : cross(n, T);
            }

            // НОРМАЛЬ ИЗ ПАКА. В нашей карте лежит УЖЕ ЕДИНИЧНЫЙ вектор (Z посчитан при сборке),
            // поэтому тут только перевод байтов в [-1..1] и перенос в тангент-базис. Нет данных ->
            // (0,0,1) -> нормаль остаётся геометрической, и никаких ветвлений для этого не нужно.
            vec3 pbrNormal(vec2 uv, vec3 Ng, vec3 T, vec3 B){
                vec2 xy = texture(pbrNormalTex, uv).xy * 2.0 - 1.0;
                float z2 = 1.0 - dot(xy, xy);
                float z = z2 > 0.0 ? sqrt(z2) : 0.0;          // страховка от фильтра на кромке
                return normalize(T * xy.x + B * xy.y + Ng * z);
            }

            // НОРМАЛЬ ИЗ ПАКА КАК ДОБАВКА к уже возмущённой нормали — для воды и льда: там база это
            // наша аналитическая рябь, и «замена» стёрла бы её целиком (карта пака часто спокойнее).
            vec3 pbrNormalAdd(vec2 uv, vec3 N, vec3 T, vec3 B){
                vec2 xy = texture(pbrNormalTex, uv).xy * 2.0 - 1.0;
                return normalize(N + T * xy.x + B * xy.y);
            }

            // UV ДЛЯ ВЫБОРКИ PBR-КАРТ. triUV отступает полтекселя АТЛАСА, а PBR-карта бывает вдвое
            // мельче атласа (см. «ПАМЯТЬ» в RtPbrMaps) — там полтекселя БОЛЬШЕ, и у самой кромки
            // спрайта билинейный фильтр затянул бы нормаль и спеку СОСЕДНЕГО блока. Поэтому у границ
            // ТРЕУГОЛЬНИКА отступаем ещё раз, уже по размерам самой карты. В середине спрайта
            // результат не меняется ни на волос: зажим там не срабатывает.
            // ⚠️ Пад считается по СВОЕЙ карте: у заглушки 1x1 он равен половине UV, но ветка RT_PBR
            // требует собранных карт, а в кадр пересборки шейдер читает заглушку — «нет данных»,
            // и любой UV от неё не меняет картинку.
            vec2 pbrUV(vec2 uv, vec2 u0, vec2 u1, vec2 u2){
                vec2 pad = 0.5 / vec2(textureSize(pbrMaterialTex, 0));
                vec2 lo = min(min(u0, u1), u2) + pad;
                vec2 hi = max(max(u0, u1), u2) - pad;
                return clamp(uv, min(lo, hi), max(lo, hi));
            }

            // МЕТАЛЛЫ labPBR (зелёный _s, коды 230..237). F0 — измеренные значения металлов
            // (BD-RDF-таблицы Filament): у железа серое, у золота жёлтое, у меди медно-красное.
            // ⚠️ СРАВНЕНИЯ ПО КОДАМ 0..255, А НЕ ПО ДРОБЯМ: границы 230/231 — это РАЗНЫЕ металлы, и
            // промах на округлении покрасил бы золото медью.
            vec3 labMetalF0(float g255){
                if (g255 < 230.5) return vec3(0.560, 0.570, 0.580);   // железо
                if (g255 < 231.5) return vec3(1.000, 0.766, 0.336);   // золото
                if (g255 < 232.5) return vec3(0.913, 0.921, 0.925);   // алюминий
                if (g255 < 233.5) return vec3(0.549, 0.556, 0.554);   // хром
                if (g255 < 234.5) return vec3(0.955, 0.638, 0.538);   // медь
                if (g255 < 235.5) return vec3(0.632, 0.626, 0.641);   // свинец
                if (g255 < 236.5) return vec3(0.673, 0.637, 0.585);   // платина
                if (g255 < 237.5) return vec3(0.972, 0.960, 0.915);   // серебро
                return vec3(0.0);                                     // 238..254 стандартом не заданы
            }

            // ФИЗИКА ПОВЕРХНОСТИ ИЗ _s — ДОБАВКА к нашей таблице materials (matProps), не замена:
            //   tint  — цвет зеркала, f0 — его сила (скаляр, как в matProps), str — «сколько зеркала»
            //   (в matProps это 1 - шероховатость, здесь ровно то же), metal — металл ли тексель,
            //   emis — сила эмиссии пака.
            // ⚠️ КВАДРАТ ШЕРОХОВАТОСТИ — не косметика: labPBR хранит ГЛАДКОСТЬ, и без (1-s)^2
            // матовые тексели пака блестели бы как полированные (стандарт задаёт именно квадрат).
            void pbrSurface(vec2 uv, vec3 albedoLin, out vec3 tint, out float f0, out float str,
                            out float metal, out float emis){
                vec4 s = texture(pbrMaterialTex, uv);
                float smoothness = s.r;
                str   = 1.0 - (1.0 - smoothness) * (1.0 - smoothness);
                emis  = s.a < 0.999 ? s.a : 0.0;               // 255 = «не светится» (так в labPBR)
                float g255 = s.g * 255.0;
                vec3 f0c;
                if (g255 < 229.5)      { f0c = vec3(s.g); metal = 0.0; }         // 0..229 — F0 как число
                else if (g255 < 237.5) { f0c = labMetalF0(g255); metal = 1.0; }   // 230..237 — металлы
                // 238..254 стандартом НЕ назначены (таблица кончается серебром на 237), а 255 — «F0
                // равно альбедо». Оба случая ведём по последнему: так советует и сам стандарт
                // («шейдерпаки вправе вообще не знать таблицы металлов и читать 230..255 как 255»).
                // ⚠️ Раньше сюда попадал весь диапазон 230..254, и любой неизвестный код давал F0=0 —
                // пак молча гасил отражение там, где просил металл.
                else                   { f0c = albedoLin; metal = 1.0; }
                f0 = max(f0c.r, max(f0c.g, f0c.b));
                tint = f0c / max(f0, 1e-3);
                if (dot(f0c, f0c) < 1e-8) { f0 = 0.0; str = 0.0; metal = 0.0; }   // «нет данных» — не мешаем
            }
            #endif
            """,
            """
            vec2 vUV(Verts vb, uint vi){
                uint p = vb.w[vi*4u + 2u];
                // Расшифровка как в terrain.vsh VulkanMod: UV0 * (1/32768)
                return vec2(float(p & 0xFFFFu), float(p >> 16)) / 32768.0;
            }
            vec3 vCol(Verts vb, uint vi){
                uint c = vb.w[vi*4u + 3u];
                return vec3(float(c & 0xFFu), float((c>>8)&0xFFu), float((c>>16)&0xFFu)) / 255.0;
            }
            uvec3 triVerts(uint prim){
                uint b = (prim >> 1u) * 4u;            // квад = prim/2, 4 вершины на квад
                return (prim & 1u)==0u ? uvec3(b,b+1u,b+2u) : uvec3(b,b+2u,b+3u);
            }

            // M8.7 СВЕТ БЛОКОВ: MC уже посчитал освещённость и упаковал её в Position.a —
            // это СТАРШИЕ 16 бит uint[1] вершины (см. terrain.vsh: sample_lightmap2(Position.a)).
            //   биты 4-7   = block-light (факелы/лава/светокамень), 0..15
            //   биты 12-15 = sky-light (небо сквозь проёмы), 0..15
            // Возвращаем (blockLight, skyLight) в 0..1.
            vec2 vLight(Verts vb, uint vi){
                uint L = vb.w[vi*4u + 1u] >> 16u;                  // Position.a
                return vec2(float((L >> 4u)  & 0xFu),              // block
                            float((L >> 12u) & 0xFu)) / 15.0;      // sky
            }

            // Локальная позиция вершины: 3x int16 (декод s/2048+4 — как decode-матрица BLAS)
            vec3 vPos(Verts vb, uint vi){
                uint a = vb.w[vi*4u + 0u];
                uint b = vb.w[vi*4u + 1u];
                ivec3 raw = ivec3(int(a << 16) >> 16, int(a) >> 16, int(b << 16) >> 16);
                return vec3(raw) / 2048.0 + 4.0;
            }

            // M8.4: вершина СУЩНОСТИ (NEW_ENTITY, 9 uint): pos float @0..2, color RGBA8 @3, uv float @4..5
            // M8.11 OVERLAY вершины СУЩНОСТИ (uint[6]: младший short = u, старший = v, оба 0..15).
            // Именно им ваниль красит мобов: v < 8 => ПОЛУЧИЛ УРОН (красный), иначе — белая вспышка
            // (криппер перед взрывом), сила по u. NO_OVERLAY = (0, 10) => ничего не меняет.
            vec2 entOverlay(Verts vb, uint vi){
                uint ov = vb.w[vi*9u + 6u];
                return vec2(float(ov & 0xFFFFu), float((ov >> 16u) & 0xFFFFu));
            }
            vec3 applyEntityOverlay(vec3 c, Verts vb, uvec3 tv){
                vec2 o = entOverlay(vb, tv.x);          // на батч одинаковый
                if (o.y < 8.0) return mix(vec3(0.75, 0.05, 0.05), c, 0.80);   // УРОН — ЛЁГКОЕ покраснение
                float a = 1.0 - (o.x / 15.0) * 0.75;                        // белая вспышка
                return (a < 0.99) ? mix(vec3(1.0), c, a) : c;
            }

            // Блок-свет вершины СУЩНОСТИ (uv2, uint[7], младший short = block*16, 0..240 -> 0..1).
            // Светящийся спрут форсит себе 15 (GlowSquidRenderer.getBlockLightLevel) — так и «светится».
            float entBlockLight(Verts vb, uint vi){
                return float(vb.w[vi*9u + 7u] & 0xFFFFu) / 240.0;
            }
            // ⚠️ НЕБЕСНЫЙ СВЕТ СУЩНОСТИ — ИЗ ВЕРШИНЫ (M8.71). Раньше я брал его как ДОЛЮ ЛУЧЕЙ
            // АМБИЕНТА, дошедших до неба. Для складок и щелей внутри моба это приговор: у овцы
            // FreshAnimations шерсть — раздутая оболочка, и кожа в щели на шее видна снаружи, но
            // ВСЕ её лучи упираются изнутри в ту же шерсть -> небо не видно -> почти чёрное пятно,
            // и оно читается как дыра в шерсти. Ваниль освещает моба ровным светом ЕГО ПОЗИЦИИ —
            // он и лежит в вершине (верхние 16 бит слова 7). Берём его, как у блоков. Локальное
            // затенение при этом не теряется: лучи амбиента по-прежнему приносят тёплый отскок
            // вместо неба там, где упёрлись.
            float entSkyLight(Verts vb, uint vi){
                return float((vb.w[vi*9u + 7u] >> 16u) & 0xFFFFu) / 240.0;
            }
            vec3 entPos(Verts vb, uint vi){
                return vec3(uintBitsToFloat(vb.w[vi*9u+0u]),
                            uintBitsToFloat(vb.w[vi*9u+1u]),
                            uintBitsToFloat(vb.w[vi*9u+2u]));
            }
            vec2 entUV(Verts vb, uvec3 tv, vec2 bary){
                float w0 = 1.0 - bary.x - bary.y;
                vec2 u0 = vec2(uintBitsToFloat(vb.w[tv.x*9u+4u]), uintBitsToFloat(vb.w[tv.x*9u+5u]));
                vec2 u1 = vec2(uintBitsToFloat(vb.w[tv.y*9u+4u]), uintBitsToFloat(vb.w[tv.y*9u+5u]));
                vec2 u2 = vec2(uintBitsToFloat(vb.w[tv.z*9u+4u]), uintBitsToFloat(vb.w[tv.z*9u+5u]));
                return w0*u0 + bary.x*u1 + bary.y*u2;
            }

            // === M8.126: «Actually 3D Stuff» — АЛЬФА КАК МЕТКА МАТЕРИАЛА ===
            // Core-шейдер пака читает альфу текселя как ЦЕЛУЮ МЕТКУ: {50,100,130,155,175,200,
            // 206,207,252,253} = рисовать фулбрайтом (свечение), 254 = свет без направленного;
            // прозрачность у него — только a<0.1 (discard). Наш RT считал метки прозрачностью —
            // 3D-предметы шли полупрозрачной кашей или резались альфа-тестом. Возвращаем
            // семантику пака: метка -> непрозрачно; фулбрайт кодируем a=2.0 (легальная
            // альфа <= 1, все потребители либо клампят, либо сравнивают с порогом).
            float a3dNorm(float a){
                if (cam.rtCfg5.x < 0.5) return a;    // пак с метками не активен
                int t = int(round(a * 255.0));
                if (t == 254) return 1.0;
                if (t==50||t==100||t==130||t==155||t==175||t==200||t==206||t==207||t==252||t==253)
                    return 2.0;
                return a;
            }

            #ifdef ENT_TEX
            // Точный texel текстуры сущности (nearest, мип-0 — как рисует MC)
            vec4 entFetch(uint slot, vec2 uv){
                ivec2 sz = textureSize(entityTex[nonuniformEXT(slot)], 0);
                ivec2 tc = clamp(ivec2(fract(uv) * vec2(sz)), ivec2(0), sz - 1);
                vec4 c = texelFetch(entityTex[nonuniformEXT(slot)], tc, 0);
                c.a = a3dNorm(c.a);   // M8.126: альфа-метки пака -> непрозрачность/свечение
                return c;
            }
            #endif

            // UV треугольника с зажимом ВНУТРЬ спрайта (полтексела от края):
            // на краю грани интерполяция попадала в texel соседнего спрайта атласа
            // -> тёмные швы по границам блоков («земля кусками»).
            vec2 triUV(Verts vb, uvec3 tv, vec2 bary){
                float w0 = 1.0 - bary.x - bary.y;
                vec2 uv0 = vUV(vb, tv.x), uv1 = vUV(vb, tv.y), uv2 = vUV(vb, tv.z);
                vec2 uv = w0*uv0 + bary.x*uv1 + bary.y*uv2;
                vec2 half_texel = 0.5 / vec2(textureSize(atlas, 0));
                vec2 lo = min(min(uv0, uv1), uv2) + half_texel;
                vec2 hi = max(max(uv0, uv1), uv2) - half_texel;
                return clamp(uv, min(lo, hi), max(lo, hi));
            }

            // Точный texel мип-0 БЕЗ фильтрации сэмплера — как рисует сам Minecraft
            // (жёсткие пиксели). textureLod размывал: фильтр/LOD сэмплера усреднял
            // атлас -> «мыло» и каша вместо резкой альфы травы.
            vec4 atlasFetch(vec2 uv){
                ivec2 sz = textureSize(atlas, 0);
                ivec2 tc = clamp(ivec2(uv * vec2(sz)), ivec2(0), sz - 1);
                return texelFetch(atlas, tc, 0);
            }

            // Альфа-тест КАНДИДАТА (CUTOUT-геометрия без opaque-флага): прозрачный
            // пиксель атласа -> false, луч летит дальше = ажурная трава/листва.
            // purpose: 0=первичный мир (глаз, БЕЗ руки), 1=тень из мира, 2=амбиент из мира,
            //          3=ОТРАЖЕНИЕ (зеркало глади), 4=тень ОТ руки, 5=амбиент ОТ руки,
            //          6=оверлей руки (только рука), 7=ПРЕЛОМЛЕНИЕ воды (своё тело скрыто).
            // Материал КОММИТНУТОГО попадания (по карте материалов). Нужен, чтобы луч пропускания
            // сквозь плотный лёд пропускал ТОЛЬКО грани самого льда, а не всё подряд.
            uint hitMaterial(rayQueryEXT rq){
                uint raw = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
                if ((raw & 0x800000u) != 0u) return 0u;              // сущность — не блок
                uint iIdm = raw & 0x3FFFFFu;
                uint prm  = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
                vec2 bry  = rayQueryGetIntersectionBarycentricsEXT(rq, true);
                Verts vbm = refs[iIdm];
                uvec3 tvm = triVerts(prm);
                return uint(texture(materialMap, triUV(vbm, tvm, bry)).r * 255.0 + 0.5);
            }

            #ifdef ENT_TEX
            // === M8.126f: БЭКФЕЙС-КУЛЛИНГ для квадов «cull»-рендертайпов (бит 25 quadTex) ===
            // Блокбенч-трюк контура (Actually 3D Stuff): ВЫВЕРНУТАЯ раздутая оболочка предмета
            // в растре отбрасывается куллингом (item_entity_translucent_CULL) и невидима, а луч
            // куллинга не делает — бил в неё СНАРУЖИ, и предмет накрывало сплошным коробом цвета
            // её заливки (кремовый слиток, репорт + проба текселя). Обратные грани пропускаем,
            // как растр. Мобы с no_cull-тайпами бит не получают — их поведение не меняется.
            bool entBackface(Verts vb, uvec3 tv, vec3 rd){
                vec3 p0 = entPos(vb, tv.x);
                vec3 n = cross(entPos(vb, tv.y) - p0, entPos(vb, tv.z) - p0);
                return dot(n, rd) > 0.0;
            }
            #endif

            // M8.147 ФЛАГИ КВАДА С ПОПРАВКОЙ НА ОСАДКИ.
            // ⚠️ quadTex[] индексируется номером квада, а prim>>1 — это номер ВНУТРИ своего BLAS.
            // Для сущностей одно и то же, но с M8.146 у осадков СВОЙ BLAS: там prim>>1 даёт 0..N,
            // тогда как записи капель лежат в массиве ПОСЛЕ сущностей (со смещения weatherStart).
            // Читая напрямую, капля получала запись чужого моба — и, что хуже, его НЕПРОЗРАЧНУЮ
            // текстуру в альфа-тесте обхода: тест пропускал все тексели и рисовал квад целиком
            // вместо тонкой струи (репорт: капли «кашей»). Поэтому осадкам отдаём флаг «частица»
            // и слот текстуры из конфига — оба известны без массива.
            uint quadFlags(uint iId, uint prim){
                if (cam.rtCfg7.x >= 0.0 && abs(float(iId) - cam.rtCfg7.x) < 0.5)
                    return 0x20000000u | uint(cam.rtCfg7.y + 0.5);
                return quadTex[prim >> 1u];
            }

            bool candAlphaPass(rayQueryEXT rq, uint purpose){
                uint raw  = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false));
                // ⚠️ M8.146: прежний программный скип осадков (M8.144m) УБРАН — он не работал надёжно
                // и стоил чтения quadTex на каждом кандидате. Теперь осадки живут в ОТДЕЛЬНОМ BLAS
                // с маской 0x04, а вторичные лучи ходят маской 0x03 -> капли отсекаются АППАРАТНО,
                // до обхода их BVH вообще (это чинит и «капли в отражениях», и стоимость дождя).
                // Проход-6 (оверлей руки, рисуется ПОВЕРХ мира как ванильный вьюмодел):
                // подтверждаем ТОЛЬКО кванты руки (бит 30 quadTex), всё остальное — мимо.
                if (purpose == 6u) {
            #ifdef ENT_TEX
                    if ((raw & 0x800000u) == 0u) return false;                       // не сущность
                    uint prim6 = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false));
                    uint qt6 = quadFlags(raw & 0x3FFFFFu, prim6);   // осадкам вернёт «частицу» -> не рука
                    if ((qt6 & 0x40000000u) == 0u) return false;                     // не рука
                    Verts vb6 = refs[raw & 0x3FFFFFu];
                    uvec3 tv6 = triVerts(prim6);
                    // M8.126f: cull-рендертайп -> обратные грани мимо (вывернутая оболочка)
                    if ((qt6 & 0x02000000u) != 0u
                            && entBackface(vb6, tv6, rayQueryGetWorldRayDirectionEXT(rq))) return false;
                    return entFetch(qt6 & 0xFFFFu,
                            entUV(vb6, tv6, rayQueryGetIntersectionBarycentricsEXT(rq, false))).a > 0.1;
            #else
                    return false;
            #endif
                }
                // вода (бит 22): С ВОЗДУХА — гладь как геометрия (пруд сверху: волны/отражение/
                // преломление). ИЗ-ПОД ВОДЫ первичный луч ПРОХОДИТ сквозь воду: подводная гладь
                // рисуется НЕ геометрией, а АНАЛИТИЧЕСКОЙ ПЛОСКОСТЬЮ (см. main) — так дистанция до
                // глади честно сравнивается с дистанцией до блока, и продырявить блок невозможно
                // в принципе (все прежние артефакты — слои/кромки/дыры — были от геометрии воды).
                // ⚠️ ЗАДНЮЮ ГРАНЬ ОТСЕКАТЬ НЕ НАДО (проверено моделями, M8.57). Я решил, что у панели
                // «перепутаны половинки», потому что подтверждается задняя грань с зеркальным UV, и
                // добавил сюда отсев по винтингу. Это лечило несуществующую болезнь: ваниль зеркалит
                // UV задней грани РОВНО затем, чтобы скомпенсировать зеркальный взгляд, — обе грани
                // дают ОДНО И ТО ЖЕ мировое отображение (u = 16 − z). К тому же ближайшее попадание
                // ray query коммитит и так, а доверие к знаку винтинга могло срезать грани воды.
                if ((raw & 0x400000u) != 0u) {
                    // Проход-8 (сквозь СТЕКЛО, M8.97/99): коммитим прозрачное, НО только ПЕРЕДНИЕ
                    // грани. Задняя грань стекла КОМПЛАНАРНА передней грани соседа (песок под
                    // блоком) — коммит задней + шаг 0.02 тоннелировал луч под землю (класс M8.25f).
                    // Отсекая задние, отдаём попадание компланарной передней грани соседа.
                    if (purpose == 8u) {
                        if (params.x > 0.5) return false;
                        Verts vbt = refs[raw & 0x3FFFFFu];
                        uint pt = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false));
                        uvec3 tvt = triVerts(pt);
                        vec3 tq0 = vPos(vbt, tvt.x);
                        vec3 tn = cross(vPos(vbt, tvt.y) - tq0, vPos(vbt, tvt.z) - tq0);
                        return dot(tn, rayQueryGetWorldRayDirectionEXT(rq)) < 0.0;
                    }
                    return purpose == 0u && params.x < 0.5;
                }
                uint iId  = raw & 0x3FFFFFu;
                uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false));
                vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, false);
                Verts vb = refs[iId];
                uvec3 tv = triVerts(prim);
            #ifdef ENT_TEX
                if ((raw & 0x800000u) != 0u) {               // сущность
                    uint qt = quadFlags(iId, prim);
                    if ((qt & 0x80000000u) != 0u) {          // тело игрока
                        // первичный луч из глаза его ПРОПУСКАЕТ (не видишь своё лицо);
                        // тень/амбиент из МИРА — ловят (весь игрок даёт тень на землю).
                        if (purpose == 0u) return false;
                        // ОТРАЖЕНИЕ (3): своё тело в зеркале скрываем ТОЛЬКО когда камера сама
                        // под водой (иначе тело у камеры заполняет зеркало). Над водой отражаем
                        // всегда — видишь себя в пруду (присед, по пояс). Работало нормально.
                        if (purpose == 3u && params.x > 0.5) return false;
                        // ПРЕЛОМЛЕНИЕ (7): своё погружённое тело СКВОЗЬ гладь не показываем —
                        // иначе под водой торчит твоя же текстура. Мобов/дно сквозь воду видно.
                        if (purpose == 7u || purpose == 8u) return false;
                        // лучи ОТ руки (4 тень / 5 амбиент): тело НЕ затеняет свою же руку —
                        // это абсурд (рука в пещере темнеет от мира, а не от своего тела).
                        if (purpose == 4u || purpose == 5u) return false;
                    }
                    // рука 1-го лица (бит 30): в проходах 0-5 ПРОЗРАЧНА — она рисуется
                    // отдельным оверлеем (проход 6, поверх мира). Так рука не клипается о
                    // землю, не тонет в воде, не бросает «висящую» тень/АО и не в отражениях.
                    if ((qt & 0x40000000u) != 0u) return false;
                    // M8.14 ПАРТИКЛ (бит 29): виден первичным лучом (0), в отражении (3) и сквозь
                    // воду (7). НО НЕ отбрасывает тень и не затеняет амбиент (1,2,4,5) — дым и искры
                    // не должны затемнять мир. Альфа-тест учитывает и прозрачность ВЕРШИНЫ
                    // (партиклы гаснут через vertex color, а не только текстурой).
                    if ((qt & 0x20000000u) != 0u) {
                        if (purpose == 1u || purpose == 2u || purpose == 4u || purpose == 5u)
                            return false;
                        float pa = entFetch(qt & 0xFFFFu, entUV(vb, tv, bary)).a
                                 * unpackUnorm4x8(vb.w[tv.x*9u + 3u]).a;
                        return pa > 0.15;
                    }
                    // ⚠️ МОЛНИЯ (бит 27) — БЕЗ ТЕКСТУРЫ. Это чистая эмиссивная геометрия: у неё нет ни
                    // атласа, ни UV. Общий альфа-тест ниже брал бы ЧУЖУЮ текстуру по нулевым UV,
                    // получал альфу ~0 и отбрасывал КАЖДЫЙ треугольник болта — геометрия в TLAS была,
                    // но ни один луч её не подтверждал. Молнию пропускаем всегда; тень она не бросает
                    // (сама светится), поэтому теневые и амбиентные проходы её игнорируют.
                    if ((qt & 0x08000000u) != 0u) {
                        if (purpose == 1u || purpose == 2u || purpose == 4u || purpose == 5u)
                            return false;
                        return true;
                    }
                    // M8.126f: cull-рендертайп -> обратные грани мимо (вывернутая оболочка)
                    if ((qt & 0x02000000u) != 0u
                            && entBackface(vb, tv, rayQueryGetWorldRayDirectionEXT(rq))) return false;
                    return entFetch(qt & 0xFFFFu, entUV(vb, tv, bary)).a > 0.1;   // альфа текстуры
                }
            #endif
                vec2 uv = triUV(vb, tv, bary);
                return atlasFetch(uv).a > 0.5;               // порог CUTOUT VulkanMod
            }

            void walkRay(rayQueryEXT rq, uint purpose){
                while (rayQueryProceedEXT(rq)) {
                    if (rayQueryGetIntersectionTypeEXT(rq, false)
                            == gl_RayQueryCandidateIntersectionTriangleEXT
                            && candAlphaPass(rq, purpose))
                        rayQueryConfirmIntersectionEXT(rq);
                }
            }

            // M8.7 ПОРТ Eclipse (lib/diffuse_lighting.glsl, doBlockLightLighting): кривая блочного
            // света — мягкий подъём + «ГОРЯЧЕЕ ПЯТНО» (HDR-всплеск до 2.5) у самого источника.
            // Именно оно даёт факелу «жар», а не плоскую заливку.
            // Во сколько раз светящийся слой ярче своей текстуры. 2.0 — глаза читаются ночью как
            // источник, но не выжигаются тонмаппером в белое.
            const float EMISSIVE_GAIN_BASE = 2.0;
            float EMISSIVE_GAIN_f(){ return EMISSIVE_GAIN_BASE * cam.rtCfg3.x; }
            #define EMISSIVE_GAIN EMISSIVE_GAIN_f()

            float blockLightCurve(float lm){
                lm = clamp(lm, 0.0, 1.0);
                float hot = smoothstep(0.72, 1.0, lm);                // только вплотную к источнику
                float bright = hot * hot * hot;                       // узкое «горячее пятно»
                // мягкий подъём: lm / (1+sqrt(1-lm)) — та же вогнутая кривая затухания
                // факела, записанная через сопряжённую форму (без вычитания корня)
                float soft = lm / (1.0 + sqrt(1.0 - lm));
                soft *= soft;
                // ⚠️ «Горячее пятно» 2.5 (как у Eclipse) рассчитано на его АВТОЭКСПОЗИЦИЮ. У нас
                // сверху ещё TORCH_AMOUNT=2.8 -> на самом источнике выходило 2.8*2.5 = 7x ярче
                // ванили, и тёмный металл фонаря выбеливался в белое пятно. Снижаем пик до 1.2:
                // работает только выше lm 0.7 (вплотную к источнику), освещение пещеры не трогает.
                return mix(soft, 1.2, bright);
            }
            vec3 blockLightEclipse(float lm){                          // фолбэк: тёплый факел
                return blockLightCurve(lm) * TORCH_COL * TORCH_AMOUNT;
            }
            vec3 blockLightEclipse(float lm, vec3 col){                // с ЦВЕТОМ источника
                return blockLightCurve(lm) * col * TORCH_AMOUNT;
            }

            // M8.153 ОТТЕНОК ИЗ ОБЪЁМА — основной путь. Решётка запечена на процессоре по ПОЛНОМУ
            // списку источников и привязана к МИРУ, поэтому не зависит ни от того, что попало в
            // буфер ближних 192, ни от поворота камеры. Читаем трилинейно (8 выборок) — переход
            // между ячейками плавный, «кубиков» оттенка не видно.
            const int   LV_NX = 64, LV_NY = 64, LV_NZ = 64;
            const float LV_CELL = 2.0;

            vec4 lvFetch(ivec3 c){
                if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(LV_NX, LV_NY, LV_NZ))))
                    return vec4(0.0);
                uint v = lvox[(c.y * LV_NZ + c.z) * LV_NX + c.x];
                return vec4(float( v        & 0xFFu), float((v >>  8) & 0xFFu),
                            float((v >> 16) & 0xFFu), float((v >> 24) & 0xFFu)) / 255.0;
            }

            bool volTint(vec3 p, out vec3 tint){
                if (cam.rtCfg8.w < 0.5) return false;              // решётка ещё не запечена
                vec3 g = (p - cam.rtCfg8.xyz) / LV_CELL - 0.5;     // координата в ЦЕНТРАХ ячеек
                ivec3 c0 = ivec3(floor(g));
                vec3  f  = g - vec3(c0);
                vec4 acc = vec4(0.0);
                for (int i = 0; i < 8; i++){
                    ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
                    vec3 wv = mix(1.0 - f, f, vec3(o));
                    acc += lvFetch(c0 + o) * (wv.x * wv.y * wv.z);
                }
                if (acc.a < 0.02) return false;                    // тут света нет -> старый путь
                tint = acc.rgb / max(acc.a, 1e-4);   // делим на АЛЬФУ, иначе пустые соседи разбавляют
                float m = max(tint.r, max(tint.g, tint.b));
                if (m > 1e-4) tint /= m;                           // чистый оттенок, без яркости
                return true;
            }

            // M8.7 ОТТЕНОК СВЕТА В ТОЧКЕ. ЯРКОСТЬ берём из вершинного lightmap (там ванильная
            // заливка — свет честно затекает за углы), а ЦВЕТ смешиваем по ближним источникам.
            // Вес = ванильный уровень (дальность - расстояние), в квадрате: ближний фонарь
            // перебивает дальний, и на стыке двух ламп цвета плавно переходят.
            vec3 lightTint(vec3 p){
                // M8.153: сперва спрашиваем ОБЪЁМ. Он покрывает 128^3 блоков вокруг камеры и знает
                // ВСЕ запечённые источники. Ниже — прежний путь по буферу: он остаётся для того,
                // что запечь нельзя (динамика: факел в руке, горящие мобы, брошенные предметы) и
                // для точек за границей решётки.
                vec3 vtint;
                if (volTint(p, vtint)) return vtint;

                int n = int(wparams.w);
                vec3 acc = vec3(0.0); float wsum = 0.0;
                // M8.147 ФОЛБЭК. Раньше точка, которую не покрыл НИ ОДИН источник из буфера,
                // красилась тёплым TORCH_COL — глобальной догадкой «вокруг факелы». Но буфер
                // капнут топ-192 ПО ДИСТАНЦИИ ДО КАМЕРЫ: стоит отлететь, и soul-фонарь из него
                // вылетает, а его законно освещённые блоки внезапно желтеют (репорт: «синева не
                // держится»). Берём оттенок БЛИЖАЙШЕГО живого источника — он почти всегда той же
                // природы, что и выпавший. Стоимость нулевая: расстояние уже считаем.
                vec3 nearCol = TORCH_COL; float nearD = 1e9;
                for (int i = 0; i < n; i++){
                    vec4 pr = lights[i].posRange;
                    float d = distance(p, pr.xyz);
                    float w = max(pr.w - d, 0.0) / 15.0;
                    // ⚠️ M8.149: КВАДРАТ, и повышать степень НЕЛЬЗЯ — проверено на живой сцене.
                    // История: в M8.148 я поднял до ^8, чтобы один сильный источник побеждал толпу
                    // слабых (скалк выдавливал оттенок ламп). Лечило симптом, а породило хуже:
                    // ^8 — это почти «победитель забирает всё», пол разбивался на участки-Вороного
                    // с резкими швами, и при движении камеры состав буфера (192 слота на 280 тысяч
                    // источников) менялся -> победитель в области скакал -> ПЯТНА РВАНО МИГАЛИ.
                    // Причину убрали в корне: у фосфора отобрано право голоса (ниже), и квадрата
                    // хватает с запасом — свеча в 2 блоках даёт 0.44 против 0.071 у двух десятков
                    // блоков скалка, то есть 86% голоса. Квадрат же держит переходы плавными.
                    w *= w;
                    // M8.149 «ФОСФОР» (модель пользователя). Точечные источники (MODE_DOTS = скалк,
                    // светящийся лишайник, светящиеся лозы) СВЕТЯТСЯ САМИ, но на округу почти
                    // ничего не бросают — как фосфор: в темноте виден, а рядом со свечой его
                    // вклад в цвет ничтожен. Раньше они голосовали наравне с настоящими лампами,
                    // и пол между свечами Ancient City оставался бирюзовым вместо тёплого.
                    // ⚠️ Право голоса режем, но НЕ обнуляем: оттенок нормируется (acc/wsum),
                    // поэтому там, где других источников нет, скалк по-прежнему забирает весь
                    // голос и красит свою нишу бирюзой. Само свечение точек (emissiveAt) не
                    // трогаем вовсе — оно живёт отдельно от этого веса.
                    if (lights[i].col.w > 5.5) w *= 0.05;
                    acc += lights[i].col.rgb * w; wsum += w;
                    if (d < nearD) { nearD = d; nearCol = lights[i].col.rgb; }
                }
                // Если поблизости честно НИЧЕГО нет — плавно возвращаемся к старому тёплому
                // допущению: далёкий источник не вправе диктовать оттенок целой пещере.
                return wsum > 1e-5 ? acc / wsum
                                   : mix(nearCol, TORCH_COL, smoothstep(32.0, 96.0, nearD));
            }

            // M8.7 ЭМИССИЯ: сам источник должен СВЕТИТЬСЯ (пламя факела, лава, светокамень).
            // Точку сдвигаем ВНУТРЬ поверхности (p - n*eps) и смотрим, попала ли она в куб
            // блока-источника. Так соседняя стена (её точка лежит в СВОЁМ кубе) не засветится.
            // Кривая яркости — Emissive_Curve=2.0 из Eclipse: светятся только светлые тексели
            // (пламя), а тёмная палка факела остаётся тёмной.
            const float EMIS_CURVE  = 2.0;
            const float EMIS_BRIGHT = 3.0;

            // M8.141: САМО-ЭМИССИЯ ИЗ КАРТЫ МАТЕРИАЛОВ (GBA = цвет эмиссии блока). Distance-
            // independent: лава/портал/светокамень светятся своим цветом на ЛЮБОЙ дистанции,
            // минуя буфер источников (он лишь 20 блоков — оттого «портал горел огнём» вдали).
            // fromMat=true -> блок запечён как эмиссивный, буферную emissiveAt пропускаем.
            vec3 selfEmission(vec2 uv, vec3 albedoLin, out bool fromMat){
                vec3 ec = texture(materialMap, uv).gba;      // 0 = не эмиссивный
                fromMat = dot(ec, ec) > 1e-5;
                if (!fromMat) return vec3(0.0);
                float luma = dot(albedoLin, vec3(0.2126, 0.7152, 0.0722));
                return albedoLin * ec * pow(luma, EMIS_CURVE) * EMIS_BRIGHT;   // как emissiveAt, но по UV
            }
            """,
            """
            // M8.7 БЛОЧНЫЙ СВЕТ ДЛЯ СУЩНОСТЕЙ. У мобов нет вершинного lightmap (их меш приходит
            // из другого формата), поэтому уровень света оцениваем по списку источников: ванильная
            // формула «уровень = дальность − расстояние». Иначе им приходилось давать константу,
            // и в тёмной пещере зомби светился, как призрак.
            float pointBlockLevel(vec3 p){
                int n = int(wparams.w);
                float best = 0.0;
                for (int i = 0; i < n; i++){
                    // MODE_POINT (огонь горящих мобов) НЕ флэтим сюда: иначе моб бледно
                    // заливается своим же огнём («призрак»), а соседние мобы — тоже. Горящее
                    // тело получает выделенную яркую эмиссию отдельно (см. main).
                    if (abs(lights[i].col.w - 4.0) < 0.5) continue;
                    vec4 pr = lights[i].posRange;
                    best = max(best, max(pr.w - distance(p, pr.xyz), 0.0) / 15.0);
                }
                // + факел в руке игрока (он мобов тоже освещает)
                float he = wparams.z * 15.0;
                float hd = distance(p, origin.xyz - vec3(0.0, 0.35, 0.0));
                return max(best, max(he - hd, 0.0) / 15.0);
            }

            // pHit — САМА точка попадания (не сдвигать внутрь!). Для тонких накладок (лишайник
            // на грани блока) сдвиг внутрь попадал в СОСЕДНИЙ блок, и тест не срабатывал. Допуск
            // 0.02 ловит точку ровно на грани клетки источника.
            vec3 emissiveAt(vec3 pHit, vec3 albedoLin, vec2 uv){
                int n = int(wparams.w);
                for (int i = 0; i < n; i++){
                    vec3 cell = lights[i].posRange.xyz;
                    vec3 d = abs(pHit - cell);
                    if (d.x < 0.52 && d.y < 0.52 && d.z < 0.52) {
                        vec3  col  = lights[i].col.rgb;
                        // 0=обычный,1=светлячки,2=квадратики,3=пламя,4=горящий моб,5=огонь-факел(норм.эмиссия+мерцание)
                        float mode = lights[i].col.w;
                        float luma = dot(albedoLin, vec3(0.2126, 0.7152, 0.0722));
                        // Насыщенность текселя: у ПЛАМЕНИ (оранж/синее/зелёное) она высокая, у СЕРОГО
                        // МЕТАЛЛА фонаря — низкая. Гейтим ей огонь -> светится ТОЛЬКО зона пламени,
                        // а не блики металлического корпуса (были «светлячковые» пиксели).
                        float sat = max(max(albedoLin.r, albedoLin.g), albedoLin.b)
                                  - min(min(albedoLin.r, albedoLin.g), albedoLin.b);
                        // MODE_POINT (горящий моб) — ТОЛЬКО светит, сам не эмиссивит (ловим ДО пламени,
                        // иначе 4 > 2.5 и он ошибочно попадал в ветку пламени).
                        if (abs(mode - 4.0) < 0.5) return vec3(0.0);
                        if (mode > 2.5 && mode < 3.5) {
                            // ПЛАМЯ (огонь, костёр): яркие тексели пламени горят сильнее + мерцают.
                            // Порог по sRGB-яркости; тёмные брёвна костра (низкая яркость) не светятся.
                            float lumaS = dot(pow(clamp(albedoLin, 0.0, 1.0), vec3(1.0/2.2)),
                                              vec3(0.2126, 0.7152, 0.0722));
                            float m  = smoothstep(0.33, 0.60, lumaS) * smoothstep(0.42, 0.66, sat);
                            float fl = 0.72 + 0.28 * sin(params.y * 0.6
                                       + dot(floor(pHit * 3.0), vec3(1.7, 2.3, 3.1)));   // живое мерцание
                            return albedoLin * col * m * EMIS_BRIGHT * 1.5 * fl;
                        } else if ((mode > 1.5 && mode < 2.5) || mode > 5.5) {   // M8.149: +фосфор
                            // КВАДРАТИКИ (лишайник): светятся ТОЛЬКО самые яркие тексели-огонёчки.
                            // ⚠️ Порог по sRGB-яркости (не линейной!): линейная занижена гаммой, и
                            // тусклое тело лишайника проскакивало -> светился весь блок. Высокий порог
                            // по sRGB отсекает тело, оставляет яркие огоньки.
                            float lumaS = dot(pow(clamp(albedoLin, 0.0, 1.0), vec3(1.0/2.2)),
                                              vec3(0.2126, 0.7152, 0.0722));
                            float m = smoothstep(0.55, 0.78, lumaS);
                            // M8.139: точки читаются ЦВЕТОМ ИСТОЧНИКА, а не приглушённым оттенком
                            // текселя. Ягоды светились «зелёно», т.к. albedoLin(тёмный оранж)×col
                            // давало тускло, а рядом зелёные листья. Десатурируем тексель к его
                            // ЯРКОСТИ и красим цветом источника (ягоды — оранж, лишайник — бирюза),
                            // + ярче -> ягоды/огоньки выделяются точками.
                            // M8.149: ФОСФОР (скалк) горит ЗАМЕТНО ТУСКЛЕЕ настоящих источников —
                            // просьба пользователя «сделать скалк тусклее». Лишайник и ягоды
                            // (MODE_DOTS) яркость сохраняют: они светят и в ванилле.
                            float dotGain = (mode > 5.5) ? 0.6 : 1.8;
                            return mix(albedoLin, vec3(luma), 0.6) * col * m * EMIS_BRIGHT * dotGain;
                        } else if (mode > 0.5) {
                            // СВЕТЛЯЧКИ (куст). ⚠️ ИЗМЕРЕНО: светлячки firefly_bush нарисованы НЕ в
                            // базовой текстуре, а в ОТДЕЛЬНОЙ анимированной (16x160, 10 кадров). В базе
                            // листва и светлячки по цвету неразличимы -> цветом не выделить. Поэтому
                            // синтезируем: привязываемся к СЕТКЕ ТЕКСЕЛЕЙ атласа (1 тексель = 1 пиксель
                            // MC = «квадратик», не круг), ~5% текселей — светлячки, каждый плавно
                            // моргает в своём ритме. Так эффект совпадает по масштабу с ванильным.
                            // ⚠️ ТОЛЬКО САМ КУСТ, не соседние блоки. Грани земли/стены, к которым
                            // прижат куст, лежат на ГРАНИЦЕ клетки (d≈0.5) и проходили расширенный
                            // тест 0.52 -> светлячки «переползали» на землю. Нутро куста — глубже,
                            // требуем строгий интерьер.
                            if (d.x > 0.46 || d.y > 0.46 || d.z > 0.46) return vec3(0.0);
                            ivec2 asz = textureSize(atlas, 0);
                            ivec2 tc  = ivec2(floor(uv * vec2(asz)));
                            float r0  = fract(sin(dot(vec2(tc), vec2(127.1, 311.7))) * 43758.5453);
                            if (r0 < 0.05) {
                                float ph    = r0 * 300.0;                       // своя фаза у каждого
                                float pulse = 0.5 + 0.5 * sin(params.y * 0.09 + ph);   // медленнее
                                float tw    = pulse * pulse * pulse;            // плавно: редкие вспышки
                                return col * tw * 3.5;                          // свой яркий цвет, не листвы
                            }
                            return vec3(0.0);
                        }
                        // огонь-факел/фонарь (5): та же эмиссия, что у обычного, ПЛЮС мерцание
                        // самой текстуры пламени (пользователь хочет, чтобы факел «дрожал»).
                        if (abs(mode - 5.0) < 0.5) {
                            float fl = 0.72 + 0.28 * sin(params.y * 0.6
                                       + dot(floor(pHit * 3.0), vec3(1.7, 2.3, 3.1)));
                            // gate по насыщенности: светится огонь фонаря, а не серый металл
                            return albedoLin * col * pow(luma, EMIS_CURVE) * EMIS_BRIGHT * fl
                                   * smoothstep(0.42, 0.66, sat);
                        }
                        // обычный источник (0): статичная эмиссия (кривая Eclipse)
                        return albedoLin * col * pow(luma, EMIS_CURVE) * EMIS_BRIGHT;
                    }
                }
                return vec3(0.0);
            }

            // Теневой луч с ОГРАНИЧЕННОЙ дальностью (для точечных источников: свет предмета).
            // ⚠️ purpose 4, а НЕ 1: источник (факел в руке) находится ВНУТРИ тела игрока, которое
            // лежит в TLAS. С обычным теневым лучом (1) он упирался в СВОЁ ЖЕ тело -> весь мир
            // «в тени» -> свет факела не доходил никуда, кроме самой руки. Purpose 4 пропускает
            // тело и руку, но мобы/блоки по-прежнему дают тень.
            bool inShadowDist(vec3 o, vec3 dir, float maxd){
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x03, o, 0.001, dir, maxd);
                walkRay(rq, 4u);
                return rayQueryGetIntersectionTypeEXT(rq, true)
                        == gl_RayQueryCommittedIntersectionTriangleEXT;
            }

            // Теневой луч ТОЛЬКО ПО МИРУ (cullMask 0x01 — секции мира; сущности 0x02 не ловятся).
            // Для света ГОРЯЩИХ МОБОВ: источник внутри тела моба, и обычный теневой луч упирался бы
            // в само тело -> свет не доходил бы до земли. По миру — тело моба не загораживает.
            bool inShadowWorld(vec3 o, vec3 dir, float maxd){
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x01u, o, 0.001, dir, maxd);
                walkRay(rq, 1u);
                return rayQueryGetIntersectionTypeEXT(rq, true)
                        == gl_RayQueryCommittedIntersectionTriangleEXT;
            }

            // M8.7 Динамический свет ПРЕДМЕТА В РУКЕ (стиль LambDynamicLights): точечный источник
            // у игрока + НАСТОЯЩИЙ теневой луч к нему -> факел отбрасывает реальные тени.
            vec3 handheldLight(vec3 hp, vec3 nrm, float level, vec3 camPos, float ambLm){
                if (level < 0.01) return vec3(0.0);
                vec3 lp = camPos - vec3(0.0, 0.35, 0.0);     // источник чуть ниже глаз (у руки)
                vec3 ld = lp - hp;
                float dist = length(ld);
                float emit = level * 15.0;                   // 14 у факела, 15 у светокамня
                if (dist > emit) return vec3(0.0);           // ванильная дальность: emit блоков
                vec3 ldn = ld / max(dist, 1e-3);
                // ⚠️ ЯРКОСТЬ КАК У ПОСТАВЛЕННОГО ФАКЕЛА. Ванильный свет распространяется заливкой:
                // на расстоянии d уровень = (emit - d). Ровно эту величину и кормим в кривую Eclipse —
                // тогда точка в 2 блоках от факела в руке светится ТАК ЖЕ, как в 2 блоках от факела
                // на стене. (Своя «квадратичная» формула давала ~в 3 раза темнее.)
                // +1 уровень к ванильному: факел В РУКЕ должен читаемо освещать пещеру
                float lm = clamp((emit + 1.0 - dist) / 15.0, 0.0, 1.0);
                // Свет заливкой почти ненаправленный -> мягкая (wrap) диффузка, а не чистый N·L,
                // иначе боковые грани уходят в чёрное, чего у поставленного факела не бывает.
                float ndl = max(dot(nrm, ldn) * 0.7 + 0.3, 0.0);
                if (ndl <= 0.0) return vec3(0.0);
                if (inShadowDist(hp + nrm*0.02, ldn, dist - 0.05)) return vec3(0.0);   // ТЕНЬ от факела
                const float HELD_BOOST = 1.7;   // ярче поставленного: это твой единственный свет в пещере
                // M8.154 РУЧНОЙ СВЕТ МЕШАЕТСЯ С ОКРУЖАЮЩИМ (просьба пользователя). Раньше он клался
                // ЧИСТЫМ цветом предмета отдельным слоем — и читался как прожектор поверх сцены, а
                // не как часть освещения: встань с фонарём душ среди свечей, и пятно под ногами
                // оставалось ледяным, хотя вокруг тёплый свет. Теперь оттенок сдвигается к смеси
                // окружающих ламп ПРОПОРЦИОНАЛЬНО вкладам: где ламп нет (тёмная пещера) — остаётся
                // чистый цвет предмета, где вокруг светло — сливается с их цветом.
                // Оттенок окружения берём из ОБЪЁМА (lightTint), поэтому смесь честная и стабильная.
                float hw = blockLightCurve(lm) * HELD_BOOST;
                float aw = blockLightCurve(clamp(ambLm, 0.0, 1.0));
                vec3 col = mix(handCol.rgb, lightTint(hp), aw / (aw + hw + 1e-4));
                return blockLightEclipse(lm, col) * ndl * HELD_BOOST;
            }

            // M8.8 Динамический свет ГОРЯЩИХ МОБОВ (MODE_POINT). Как факел в руке, но источники —
            // из списка lights[]. Такой свет НЕ в ванильном lightmap блоков, поэтому кладём его тут
            // отдельным проходом (иначе горящий моб освещал бы себя, но не землю под собой).
            // M8.138: ДОМИНИРУЮЩЕЕ НАПРАВЛЕНИЕ статических источников (лава/факелы/светокамень)
            // для НАПРАВЛЕННОСТИ блок-света. Дёшево (без теневых лучей): сумма направлений к
            // ближним источникам с весом «ярче+ближе». Длина результата = «уверенность» в
            // направлении (0 — источников рядом нет -> свет остаётся ненаправленным). Возвращаем
            // НЕнормированный вектор: его длину читает вызывающий как силу эффекта.
            vec3 blockLightDir(vec3 hp){
                int n = int(wparams.w);
                vec3 acc = vec3(0.0);
                for (int i = 0; i < n; i++){
                    if (abs(lights[i].col.w - 4.0) < 0.5) continue;   // горящие мобы — отдельно (свои тени)
                    vec4 pr = lights[i].posRange;
                    vec3 d = pr.xyz - hp; float dist = length(d);
                    if (dist > pr.w) continue;
                    float w = max(pr.w - dist, 0.0) / max(pr.w, 1.0);   // ближе/дальнобойнее — весомее
                    acc += (d / max(dist, 1e-3)) * (w * w);
                }
                return acc;
            }

            vec3 dynamicPointLight(vec3 hp, vec3 nrm){
                int n = int(wparams.w);
                vec3 acc = vec3(0.0);
                for (int i = 0; i < n; i++){
                    if (abs(lights[i].col.w - 4.0) > 0.5) continue;   // только MODE_POINT (горящие мобы)
                    vec4 pr = lights[i].posRange;
                    vec3 ld = pr.xyz - hp; float dist = length(ld);
                    if (dist > pr.w) continue;
                    vec3 ldn = ld / max(dist, 1e-3);
                    float ndl = max(dot(nrm, ldn) * 0.7 + 0.3, 0.0);  // мягкая wrap-диффузка (огонь окутывает)
                    if (ndl <= 0.0) continue;
                    // ⚠️ Тень ТОЛЬКО по миру (cullMask 0x01): источник огня внутри тела моба, а
                    // inShadowDist (purpose 4) пропускает лишь тело/руку ИГРОКА, не обычных мобов ->
                    // теневой луч упирался в само тело моба -> свет в мир не доходил.
                    if (inShadowWorld(hp + nrm*0.02, ldn, dist - 0.1)) continue;
                    // ⚠️ Нормируем на САМУ дальность (не /15): у дальности 8 деление /15 давало макс.
                    // уровень 0.53 — вечно тусклая часть кривой, земля почти не светилась. Теперь у
                    // источника уровень ~1 (яркое «горячее пятно»), к краю — спад. Огонь реально светит.
                    float lm = clamp((pr.w - dist) / pr.w, 0.0, 1.0);
                    // Мерцание огня моба: фаза от позиции источника -> разные мобы вразнобой
                    float ph = dot(floor(pr.xyz), vec3(1.7, 2.3, 3.1));
                    float fl = 0.80 + 0.20 * (sin(params.y*0.8 + ph)*0.6 + sin(params.y*2.1 + ph*1.7)*0.4);
                    acc += blockLightEclipse(lm, lights[i].col.rgb) * ndl * fl * 1.4;
                }
                return acc;
            }

            // M8.8 Мерцание СВЕТА, отброшенного пламенем/огнём/горящими мобами на ОКРУЖЕНИЕ.
            // Свет фикс. блоков (огонь/костёр) идёт из ванильного lightmap (статичный), поэтому
            // сам по себе не мерцает — домножаем его на этот «дышащий» множитель. Вблизи мерцающего
            // источника (пламя=3, горящий моб=4) свет колышется, вдали = 1.0 (обычные факелы ровные).
            float flameFlicker(vec3 hp){
                int n = int(wparams.w);
                float infl = 0.0, ph = 0.0;
                for (int i = 0; i < n; i++){
                    if (lights[i].col.w < 2.5) continue;          // только пламя и горящие мобы
                    vec4 pr = lights[i].posRange;
                    float w = clamp(1.0 - distance(hp, pr.xyz) / max(pr.w, 1e-3), 0.0, 1.0);
                    if (w > infl) { infl = w; ph = dot(floor(pr.xyz), vec3(1.7, 2.3, 3.1)); }
                }
                if (infl < 0.01) return 1.0;
                float flick = 0.82 + 0.18 * (sin(params.y*0.8 + ph)*0.6 + sin(params.y*2.1 + ph*1.7)*0.4);
                return mix(1.0, flick, infl);                     // вдали от огня — стабильно
            }

            bool inShadow(vec3 o, vec3 dir, bool fromHand){
            #ifndef RT_SHADOWS
                return false;   // тени выключены — код теневого луча вырезан из шейдера
            #endif
                if (cam.rtCfg.x < 0.5) return false;   // тени выключены в настройках -> луч не пускаем
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas,
                        gl_RayFlagsTerminateOnFirstHitEXT, 0x03, o, 0.001, dir, cam.rtCfg4.x);
                // Тело игрока всегда даёт тень; рука занавешивает ТОЛЬКО лучи от самой руки (4).
                walkRay(rq, fromHand ? 4u : 1u);
                return rayQueryGetIntersectionTypeEXT(rq, true)
                        == gl_RayQueryCommittedIntersectionTriangleEXT;
            }

            // --- Волны воды: ПО МОТИВАМ waterBump Eclipse; шум, размеры и константы СВОИ ---
            // noisetex заменён своим gnoise; 3 повёрнутые октавы + «большие волны»
            // (патчи спокойной/бурной воды) + след-волны от игрока; нормаль — конечные разности.
            // Симплекс-шум (Ashima/Gustavson). У градиентного (Перлин) шума ПРОИЗВОДНЫЕ —
            // а именно они дают нормаль воды — выровнены по клеткам квадратной решётки =>
            // диагональные полосы на преломлении вблизи. Симплекс на ТРЕУГОЛЬНОЙ решётке:
            // изотропные производные, нормаль без осевых стрипов. Возвращаем [0,1] как прежде.
            vec3 permute3(vec3 x){ return mod(((x*34.0)+1.0)*x, 289.0); }
            float snoise(vec2 v){
                const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                                   -0.577350269189626, 0.024390243902439);
                vec2 i  = floor(v + dot(v, C.yy));
                vec2 x0 = v -   i + dot(i, C.xx);
                vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
                vec4 x12 = x0.xyxy + C.xxzz;
                x12.xy -= i1;
                i = mod(i, 289.0);
                vec3 p = permute3( permute3( i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
                vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
                m = m*m; m = m*m;
                vec3 x = 2.0 * fract(p * C.www) - 1.0;
                vec3 h = abs(x) - 0.5;
                vec3 ox = floor(x + 0.5);
                vec3 a0 = x - ox;
                m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);
                vec3 g;
                g.x  = a0.x  * x0.x  + h.x  * x0.y;
                g.yz = a0.yz * x12.xz + h.yz * x12.yw;
                return 130.0 * dot(m, g);
            }
            float gnoise(vec2 p){ return snoise(p) * 0.5 + 0.5; }   // [0,1] без сеточных полос
            // ИЗОТРОПНЫЕ размеры (x==y) на 3 масштабах + поворот между октавами => круглые
            // объёмные волны, а не направленные полоски-штрихи (анизотропия давала стрипы).
            const vec2  WAVE_SIZE[3] = vec2[](vec2(3.4,3.4), vec2(2.1,2.1), vec2(5.7,5.7));
            const float WAVE_ROT = 2.39996;
            float waterHeight(vec2 pos, float lwCurved, float t, vec2 playerXZ){
                float mv = t*0.22;   // M8.113: волны медленнее (просьба; было 0.42)
                mat2 R = mat2(cos(WAVE_ROT), -sin(WAVE_ROT), sin(WAVE_ROT), cos(WAVE_ROT));
                float h = 0.0;
                vec2 p = pos;   // pos нужен ниже для кильватера — октавы крутим по копии
                for (int i=0;i<3;i++){ p = R*p; h += gnoise(p/WAVE_SIZE[i] + lwCurved*0.5 + mv); }
                return (h/4.6)*max(lwCurved, 0.32);
                // (кильватер игрока убран: в исходниках Eclipse нет портируемой функции —
                //  их «след» = Physics Mod physics_ripples / water-sim проход, не шейдер)
            }
            vec3 waterNormal(vec2 xz, float t, vec2 playerXZ, float dist){
                // M8.102: «патчи волнения» удалены ЧЕСТНО — замер показал, что старый блэнд
                // mix(1-x, x, 0.5) ТОЖДЕСТВЕННО равен 0.5: поле энергии волн давно было
                // константой, шум большой волны считался вхолостую. Оставляем константу.
                const float lwCurved = 0.5;
                // ШАГ конечной разности растёт с дистанцией: сглаживает мелкие волны
                // вдали (их период < пикселя = алиасинг/линии). Мип-фильтр нормали.
                float d = 0.06 + dist * 0.04;
                float h0 = waterHeight(xz, lwCurved, t, playerXZ);
                float h1 = waterHeight(xz+vec2(d,0.0), lwCurved, t, playerXZ);
                float h2 = waterHeight(xz+vec2(0.0,d), lwCurved, t, playerXZ);
                float amp = 0.7;                                       // сила наклона ряби (объёмнее, но круглая)
                float sx = (h1-h0)/d*amp, sz = (h2-h0)/d*amp;
                return normalize(vec3(-sx, 1.0, -sz));                 // круглый нормаль (без плоских стрипов)
            }

            // Лёгкое затенение попавшего треугольника для ВТОРИЧНЫХ лучей (отражение/
            // преломление воды): текстура + солнце с тенью + простой небесный амбиент
            // (без 4-лучевого GI — дёшево). Мир ИЛИ сущность (в т.ч. ТЕЛО ИГРОКА).
            vec3 shadeSimple(rayQueryEXT rq, vec3 ro, vec3 dir){
                float t = rayQueryGetIntersectionTEXT(rq, true);
                vec3 hp = ro + t*dir;
                uint rawId = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
                bool isEnt = (rawId & 0x800000u) != 0u;
                uint iId  = rawId & 0x3FFFFFu;
                uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
                vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
                float w0 = 1.0-bary.x-bary.y;
                Verts vb = refs[iId];
                uvec3 tv = triVerts(prim);
                vec3 p0, e1, e2, albedoS;
                vec2 hitUV = vec2(0.0);
                bool a3dGlowR = false;   // M8.126: фулбрайт-метка Actually 3D Stuff
                if (isEnt) {
                    p0 = entPos(vb,tv.x); e1 = entPos(vb,tv.y)-p0; e2 = entPos(vb,tv.z)-p0;
                    vec4 c = unpackUnorm4x8(vb.w[tv.x*9u + 3u]);
            #ifdef ENT_TEX
                    vec4 etxR = entFetch(quadFlags(iId, prim) & 0xFFFFu, entUV(vb, tv, bary));
                    albedoS = etxR.rgb * c.rgb;
                    a3dGlowR = etxR.a > 1.5;   // M8.126: фулбрайт-метка пака — светится и в отражении
            #else
                    albedoS = vec3(0.78) * c.rgb;
            #endif
                } else {
                    p0 = vPos(vb,tv.x); e1 = vPos(vb,tv.y)-p0; e2 = vPos(vb,tv.z)-p0;
                    vec2 uv = triUV(vb, tv, bary);
                    hitUV = uv;
                    vec3 tint = w0*vCol(vb,tv.x) + bary.x*vCol(vb,tv.y) + bary.y*vCol(vb,tv.z);
                    albedoS = atlasFetch(uv).rgb * tint;
                }
                vec3 nrm = cross(e1, e2); float nl = length(nrm);
                nrm = nl > 1e-6 ? nrm/nl : vec3(0,1,0);
                if (dot(nrm, dir) > 0.0) nrm = -nrm;
                vec3 albedo = pow(albedoS, vec3(2.2));
                if (!isEnt) albedo = applyCracks(albedo, hp, nrm);   // трещины видны и сквозь воду/в отражениях
                float ndl = max(dot(nrm, SUN_DIR_f()), 0.0);
                float sun = (ndl>0.0 && !inShadow(hp + nrm*0.02, SUN_DIR_f(), false)) ? ndl : 0.0;
                sun *= cloudShadow(hp);   // тень от облаков и в отражениях
                // M8.7: свет блоков и здесь — иначе факелы не светят в отражениях/сквозь воду.
                // Сущностям (в т.ч. телу игрока в отражении) — тот же честный расчёт, что в main:
                // блочный свет по источникам, небо — одним лучом вверх (в пещере = 0, не призрак).
                vec2 lm2;
                if (isEnt) {
                    // небесный свет — из вершины (см. entSkyLight): луч вверх давал ноль во всех
                    // складках моба и красил их чернотой
                    lm2 = vec2(pointBlockLevel(hp),
                               w0*entSkyLight(vb,tv.x) + bary.x*entSkyLight(vb,tv.y) + bary.y*entSkyLight(vb,tv.z));
                } else {
                    lm2 = w0*vLight(vb,tv.x) + bary.x*vLight(vb,tv.y) + bary.y*vLight(vb,tv.z);
                }
                vec3 ambient;   // M8.133: свой амбиент по измерению (Энд сиреневый, Ад цветной)
                if (dimType() == 2)
                    ambient = endAmb() * (0.55 + 0.45*lm2.y) + MIN_LIGHT;
                else if (dimType() == 1)
                    ambient = cam.rtCfg6.rgb * (0.9 + 0.5*lm2.x) + MIN_LIGHT;
                else
                    ambient = skyBase(nrm) * 0.28 * mix(0.40, 1.0, dayAmt()) * (0.06 + 0.94*lm2.y) + MIN_LIGHT;
                vec3 torch2  = blockLightEclipse(lm2.x, lightTint(hp)) * flameFlicker(hp);   // кривая + ЦВЕТ + мерцание
                // M8.138b: направленность блок-света и для сущностей/отражений (моб у лавы даёт
                // объём). Тот же приём, что на террейне (blockLightDir + half-Lambert).
                {
                    vec3 bld = blockLightDir(hp);
                    float conf = length(bld);
                    if (conf > 0.15) {
                        float nd = clamp(dot(nrm, bld / conf) * 0.5 + 0.5, 0.0, 1.0);
                        torch2 *= mix(1.0, nd, min(conf, 1.0) * 0.7);
                    }
                }
                // + факел В РУКЕ: без него дно под водой оставалось чёрным, хотя берег был освещён
                torch2 += handheldLight(hp, nrm, wparams.z, origin.xyz, lm2.x);
                if (!isEnt) torch2 += dynamicPointLight(hp, nrm);   // свет горящих мобов (в отражениях)
                vec3 lit2 = albedo * torch2;                       // тот же поджим пересвета, что в main
                float lmax2 = max(lit2.r, max(lit2.g, lit2.b));
                lit2 /= (1.0 + max(lmax2 - 1.0, 0.0) * 0.8);
                vec3 outc = albedo * (ambient + sun*SUN_COL_f()*dimDay()) + lit2;   // M8.133: солнце только верхний мир
                if (!isEnt) {   // M8.141: и в отражениях — эмиссия из карты (distance-independent)
                    bool fromMatR; vec3 seR = selfEmission(hitUV, albedo, fromMatR);
                    outc += fromMatR ? seR : emissiveAt(hp, albedo, hitUV);   // лава/факел светятся и в отражениях,
                }                                                    // но НЕ мобы, задевшие клетку факела
            #ifdef ENT_TEX
                // светящийся слой сущности светит и в отражении/сквозь воду
                if (isEnt && ((quadFlags(iId, prim) & 0x04000000u) != 0u || a3dGlowR)) outc = albedo * EMISSIVE_GAIN;
            #endif
                // светящийся спрут светится и в отражениях воды (см. main)
                if (isEnt) {
                    float ebl = w0*entBlockLight(vb,tv.x) + bary.x*entBlockLight(vb,tv.y) + bary.y*entBlockLight(vb,tv.z);
                    outc += albedo * smoothstep(0.85, 1.0, ebl) * max(ebl - max(lm2.x, lm2.y), 0.0) * 1.6;
                    outc = applyEntityOverlay(outc, vb, tv);   // покраснение от урона и в отражениях
                }
                return outc;
            }

            // Трасса вторичного луча (purpose=1: сквозь воду, ловит мир/сущности/ИГРОКА).
            // skyAccess: доступ ОТКРЫТОГО НЕБА у отражающей поверхности (0 в пещере, 1 на поверхности).
            // ⚠️ Промахнувшийся зеркальный луч возвращает skyRefl = ЯРКОЕ небо золотого часа. У края
            // воды в пещере луч уходит почти горизонтально, промахивает всю геометрию и приносит это
            // небо => БЛЕДНЫЕ ПОЛОСКИ по кромке водоёма. Под землёй воде отражать небо нечем — гасим.
            // === ЗАЩИТА ОТ «ПРОСВЕТОВ» (M8.25k) ===
            // Мир ПОД ПОВЕРХНОСТЬЮ ПОЛЫЙ: мешер не строит грани, которых игроку не видно. Любой
            // вторичный луч, случайно оказавшийся под поверхностью (эпсилон у РЕБРА блока — отсюда
            // «супертонкая каёмка»), летит сквозь всю землю и не встречает НИЧЕГО. Раньше промах =
            // НЕБО -> яркая щель по контуру блока. Но луч, ушедший ВНИЗ и не встретивший ничего, —
            // это всегда баг геометрии, а не небо: внизу неба нет. Отдаём ему темноту.
            vec3 missSky(vec3 rd, float skyAccess){
                if (rd.y < -0.05) return skyRefl(rd) * skyAccess * 0.03;   // «под миром» — тень, не небо
                return skyRefl(rd) * skyAccess;
            }

            vec3 traceShade(vec3 ro, vec3 rd, float skyAccess){
            #ifndef RT_REFLECTIONS
                // Вырезано целиком: за этой функцией тянется весь шейдинг отражённого мира.
                return sky(rd) * mix(0.35, 1.0, skyAccess);
            #endif
                // Отражения выключены: отдаём цвет НЕБА в ту сторону — дёшево и не чёрная дыра.
                if (cam.rtCfg.z < 0.5) return sky(rd) * mix(0.35, 1.0, skyAccess);
                rayQueryEXT rq;
                // M8.144l: в ДОЖДЬ укорачиваем луч отражения — дальние отражения тонут в тумане,
                // а луч меньше пробивает стену дождя (вода = главная просадка при взгляде на неё).
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsNoneEXT, 0x03, ro, 0.02, rd,
                        cam.rtCfg4.y * mix(1.0, 0.42, rainF()));
                walkRay(rq, 3u);   // отражение: сквозь воду; тело игрока — только с расстояния
                if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT)
                    return shadeSimple(rq, ro, rd);
                return missSky(rd, skyAccess);   // тугое солнце для отражения (без белой кляксы)
            }

            // Рука 1-го лица — отдельный ОВЕРЛЕЙ (проход 6, рисуется ПОВЕРХ мира). Шейдинг:
            // альбедо скина, солнце с тенью ТОЛЬКО от мира (пещера темнит; тело/сама рука — нет),
            // амбиент неба с видимостью. Под водой (behindWater): рябь UV — текстура «плывёт»
            // + тинт поглощением по глубине погружения. Рука не пропадает, а красиво искажается.
            vec4 shadeHand(rayQueryEXT rq, vec3 ro, vec3 dir, bool behindWater, float depth){
                float t = rayQueryGetIntersectionTEXT(rq, true);
                vec3 hp = ro + t*dir;
                uint rawId = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
                uint iId  = rawId & 0x3FFFFFu;
                uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
                vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
                Verts vb = refs[iId];
                uvec3 tv = triVerts(prim);
                vec2 uv = entUV(vb, tv, bary);   // геометрию искажает НАСТОЯЩЕЕ преломление луча (см. вызов)
                vec4 vc = unpackUnorm4x8(vb.w[tv.x*9u + 3u]);
            #ifdef ENT_TEX
                vec4 tx = entFetch(quadFlags(iId, prim) & 0xFFFFu, uv);
            #else
                vec4 tx = vec4(0.8);
            #endif
                // M8.99: ПОКРЫТИЕ руки/предмета = альфа текстуры x альфа вершины. Раньше всё
                // шейдилось непрозрачным — стекло в руке было сплошным кубом без текстуры.
                float hcover = clamp(tx.a * vc.a, 0.0, 1.0);
                vec3 albedo = pow(tx.rgb * vc.rgb, vec3(2.2));
                vec3 p0 = entPos(vb,tv.x), e1 = entPos(vb,tv.y)-p0, e2 = entPos(vb,tv.z)-p0;
                vec3 nrm = cross(e1,e2); float nl = length(nrm);
                nrm = nl > 1e-6 ? nrm/nl : vec3(0,1,0);
                if (dot(nrm, dir) > 0.0) nrm = -nrm;
                // GUIDE: рука перекрывает мир — DLSS должна видеть тут ПОВЕРХНОСТЬ, а не фон,
                // иначе она смешает руку с тем, что было за ней, и та поплывёт при движении.
                g_normal = nrm;
                g_viewZ  = max(dot(hp - origin.xyz, forward.xyz), 0.02);
                g_diff   = albedo;
                g_spec   = vec3(0.04);
                g_rough  = 0.9;
                g_isHand = true;   // -> вектор движения 0: рука прибита к камере и по экрану не едет
                float ndl = max(dot(nrm, SUN_DIR_f()), 0.0);
                // M8.104: тень на руке — ПО-ПИКСЕЛЬНО с её поверхности. Пробовали и «одной
                // пробой от камеры» (M8.101, рука темнеет сплошняком): пользователь сравнил
                // оба варианта вживую — по-пиксельное затенение выглядит лучше, возвращаем.
                float sun = (ndl > 0.0 && !inShadow(hp + nrm*0.02, SUN_DIR_f(), true)) ? ndl : 0.0;  // world-only (4)
                // Амбиент с видимостью (3 луча) — рука темнеет в пещере/под навесом.
                uint seed = (gl_GlobalInvocationID.y * uint(origin.w) + gl_GlobalInvocationID.x) * 9781u + 7u + frameSeed();
                vec3 skyLight = vec3(0.0);
                float skyVis = 0.0;
                for (int i = 0; i < 3; i++) {
                    vec3 ad = cosineDirStrat(nrm, seed, i, 3);
                    rayQueryEXT arq;
                    rayQueryInitializeEXT(arq, tlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x03, hp + nrm*0.02, 0.001, ad, 32.0);
                    walkRay(arq, 5u);   // амбиент от руки: мир затеняет, тело/рука прозрачны
                    if (rayQueryGetIntersectionTypeEXT(arq, true) == gl_RayQueryCommittedIntersectionNoneEXT) {
                        skyLight += skyBase(ad);
                        skyVis += 1.0/3.0;
                    } else {
                        skyLight += vec3(0.10) * mix(0.15, 1.0, dayAmt());   // отскок на руке тоже солнечный
                    }
                }
                // ⚠️ Небо гасим ВИДИМОСТЬЮ неба (как у блоков): без этого рука в пещере получала
                // плоский амбиент и светилась «призраком» на фоне тёмных стен.
                // ⚠️ НО не в ноль: при 0.06 рука, зашедшая в тень блока ПОД ОТКРЫТЫМ НЕБОМ, падала
                // в черноту — а в жизни она там освещена всей полусферой неба, просто без солнца.
                // Три луча амбиента с близкой руки легко упираются в собственный кулак и ближний
                // блок, поэтому доверять их видимости на 94% нельзя. Держим пол повыше.
                vec3 ambient = skyLight * (0.55 / 3.0) * mix(0.40, 1.0, dayAmt()) * (0.35 + 0.65 * skyVis) + MIN_LIGHT;
                // M8.7: РУКА тоже освещается блочным светом. Вершинного light у неё нет (это меш
                // сущности), поэтому берём уровень света В ПОЗИЦИИ ИГРОКА (wparams.y из MC) и
                // свет предмета в руке (wparams.z) — иначе рука оставалась чёрной у факела.
                // ×0.6: рука вплотную к пламени, на полной кривой она бы выгорала в белое пятно.
                // Цвет: свет своего предмета, а если вокруг светлее — оттенок ближних источников.
                vec3 handLightCol = (wparams.z > wparams.y) ? handCol.rgb : lightTint(origin.xyz);
                vec3 handTorch = blockLightEclipse(max(wparams.y, wparams.z), handLightCol) * 0.6;
                // ⚠️ ТОТ ЖЕ ПОДЖИМ ПЕРЕСВЕТА, ЧТО В МИРЕ (M8.63). Рука — третий, отдельный путь
                // шейдинга, и защита из main её не касалась: с факелом В РУКЕ уровень блок-света
                // сразу максимальный, а кожа светлая (~0.8) — рука выгорала в ровное белое пятно.
                // Ниже единицы не трогаем, выше — плавно заваливаем.
                vec3 hlit = albedo * handTorch;
                float hmax = max(hlit.r, max(hlit.g, hlit.b));
                hlit /= (1.0 + max(hmax - 1.0, 0.0) * 0.8);
                vec3 outc = albedo * (ambient + sun * SUN_COL_f()) + hlit;
                // M8.126: фулбрайт-метка Actually 3D Stuff — светящиеся пиксели 3D-предмета
                // в руке (пламя факела и т.п.) рисуются полным цветом, не гаснут в темноте
                if (tx.a > 1.5) outc = albedo * EMISSIVE_GAIN;
                if (params.x > 0.5) {
                    // камера ПОД водой -> ТОЧНО ТА ЖЕ формула, что у мира (M8.97). Раньше у руки
                    // был СВОЙ цвет рассеяния (ярче и зеленее) и НЕ БЫЛО потемнения с глубиной:
                    // мир на глубине гаснет до x0.13, а рука оставалась ярким пятном.
                    vec3 wA = vec3(0.335, 0.106, 0.069) * 0.65;
                    vec3 wS = vec3(0.030, 0.058, 0.105) * 1.5 * waterDayLit();   // как у мира
                    vec3 tr = exp(-wA * t);
                    outc = outc * tr + wS * (1.0 - tr);
                    float camDepth = 20.0;
                    if (wparams.x > -1e8) camDepth = clamp(wparams.x - origin.y, 0.0, 40.0);
                    outc *= mix(0.13, 1.0, exp(-camDepth * 0.085));   // темнеет с глубиной, как мир
                } else if (behindWater) {
                    // рука зашла под гладь (камера НАД водой) -> лёгкий «погружённый» оттенок
                    // ⚠️ Голубая добавка — это РАССЕЯНИЕ в воде, и его освещает небо. Без привязки к
                    // свету она горела и ночью: рука уходила под воду и вспыхивала «газировкой»
                    // ярче самой воды (та же ошибка, что была в самой воде).
                    float sub = clamp(depth * 0.6, 0.0, 0.65);
                    outc = mix(outc, outc * vec3(0.35, 0.6, 0.7)
                                     + vec3(0.02, 0.08, 0.11) * waterDayLit(), sub);
                }
                return vec4(outc, hcover);
            }

            // M8.144: ЗВЁЗДНАЯ ГЛАДЬ ПОРТАЛА В ЭНД — процедурная (наша, clean-room; НЕ ассет
            // шейдерпака). Фиолетово-бирюзовая туманность + многослойный параллакс звёзд, ЯРКО:
            // поверхность светит на внутренние грани рамок -> те горят бирюзой («столбы»).
            float epHash(vec2 p){
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }
            // СГЛАЖЕННЫЙ value-noise (билинейная интерполяция) — без квадратных блоков.
            float epNoise(vec2 p){
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = epHash(i),               b = epHash(i + vec2(1.0, 0.0));
                float c = epHash(i + vec2(0.0,1.0)), d = epHash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }
            // Одна звезда: в СЛУЧАЙНОЙ точке ячейки (не по сетке!), КВАДРАТНАЯ (пиксельная, как
            // ванильная текстура портала), резкая. Chebyshev-метрика max(|x|,|y|) даёт квадрат.
            float epStar(vec2 c, float thr){
                vec2 cell = floor(c);
                float h = epHash(cell);
                if (h <= thr) return 0.0;
                vec2 sp = vec2(epHash(cell + 11.3), epHash(cell + 27.7));   // позиция в ячейке -> уходит с сетки
                vec2 f = abs(fract(c) - sp);
                float d = max(f.x, f.y);                                    // КВАДРАТ (пиксельная звезда)
                float sz = mix(0.03, 0.12, epHash(cell + 5.1));            // РАЗНЫЙ РАЗМЕР звезды
                return (1.0 - smoothstep(sz * 0.35, sz, d)) * pow((h - thr) / (1.0 - thr), 2.0);
            }
            vec3 endPortalStars(vec3 wp, vec3 rd){
                // Техника изучена по Complementary endPortalEffect (© EminGT) — РЕАЛИЗАЦИЯ СВОЯ:
                // многослойная выборка звёздного поля с ПАРАЛЛАКСОМ (планарная проекция луча на
                // плоскость); звёзды РАЗНОЙ глубины; палитра/значения свои.
                vec2 proj = rd.xz / (abs(rd.y) + 0.03);        // параллакс глубины по ВЗГЛЯДУ (не по времени -> статично)
                vec3 col = vec3(0.001, 0.002, 0.004);          // ПОЧТИ ЧЁРНЫЙ фон (ваниль 98% чёрного) -> квадратов нет
                // 8 слоёв «глубины» (СТАТИЧНО, без дрейфа): разный масштаб/яркость/оттенок звёзд
                for (int i = 1; i <= 8; i++){
                    float fi = float(i);
                    vec2 p     = wp.xz * (0.5 + fi * 0.12) + proj * (fi * 0.13) + fi * 13.7;  // СТАТИЧНО (без времени)
                    float rot  = fi * 0.9; float cs = cos(rot), sn = sin(rot);
                    vec2 coord = mat2(cs, sn, -sn, cs) * p;
                    vec2 sc8 = coord * (6.0 + fi * 0.7);       // разная частота слоёв -> разный масштаб звёзд
                    float star = epStar(sc8, 0.86);
                    // ОТТЕНОК варьируем по хэшу: голубой / бирюза / бело-голубой
                    float ph = epHash(floor(sc8) + 3.7);
                    vec3 pal = ph < 0.40 ? vec3(0.28, 0.72, 1.00)     // голубой
                             : ph < 0.75 ? vec3(0.45, 0.96, 0.94)     // бирюза/циан
                             :             vec3(0.92, 1.00, 1.00);    // бело-голубой
                    float bri = 0.45 + 0.95 * epHash(floor(sc8) + 8.1);   // РАЗНАЯ яркость звёзд
                    col += pal * star * bri * (1.7 - fi * 0.12);  // 8 слоёв параллакса = объём
                }
                return col * 2.1;                              // эмиссивно-ярко
            }

            void main(){
                uint width = uint(origin.w), height = uint(forward.w);
                uvec2 pix = gl_GlobalInvocationID.xy;
                if (pix.x>=width || pix.y>=height) return;
                """,
                """
                // JITTER: каждый кадр луч смещается на долю пикселя (Halton). Без этого DLSS нечего
                // накапливать — она собирает субпиксельные детали именно из дрожания сетки лучей.
                float u = ((float(pix.x)+0.5+cam.jitterNF.x)/float(width))*2.0-1.0;
                float v = 1.0 - ((float(pix.y)+0.5+cam.jitterNF.y)/float(height))*2.0;
                vec3 right = -left.xyz;
                vec3 dir = normalize(forward.xyz + (u*left.w)*right + (v*up.w)*up.xyz);
                // === M8.12/95/102: экранные искажения — НАША сборка (clean-room) ===
                // Идея классическая для шейдер-паков: собрать ОДНУ маску искажения (пульс
                // урона, капли на камере, всплеск, жар) и «звумить» изображение к центру
                // пропорционально маске. У нас нет готового кадра для пересэмпла — гнём САМ
                // ЛУЧ той же величиной: результат чище (без блюра). Кривые/константы свои,
                // шум — наша текстура. Время в секундах (params.y — тики, 20/с).
                float fxTime = params.y / 20.0;
                vec2  fxUV   = vec2(u, v) * 0.5 + 0.5;                        // экран 0..1
                float fxAsp  = origin.w / forward.w;                          // соотношение сторон
                float fxNoise = texture(noisetex, fxUV * vec2(fxAsp, 1.0)).r;
                float vignette = sqrt(clamp(dot(vec2(u,v), vec2(u,v)) * 0.5, 0.0, 1.0));
                float distortmask = 0.0;
                {
                    float oneHeart = fx2.z, threeHeart = fx2.w;
                    float MinorDamageTaken = fx3.x, CriticalDamageTaken = fx3.y;
                    float enterWater = outlineInfo.z, exitWater = outlineInfo.w;
                    float exitLava = fx3.z;
                    bool  eyeInWater = params.x > 0.5;

                    // ---- DAMAGE DISTORTION (по мотивам) ----
                    float heartBeat = (pow(sin(fxTime * 14.0)*0.5+0.5, 2.0)*0.21 + 0.1);
                    float damageDistortion = vignette * fxNoise * heartBeat * threeHeart;
                    damageDistortion = mix(damageDistortion, vignette * (0.5 + fxNoise),
                                           CriticalDamageTaken) * MOTION_AMOUNT;
                    distortmask = damageDistortion;

                    // ---- WATER DISTORTION: КАПЛИ на камере после выныривания ----
                    if (exitWater > 0.0) {
                        vec3 scale = vec3(1.0, 1.0, 0.0);
                        scale.xy = (eyeInWater ? vec2(0.32)
                                   : vec2(0.52, 0.26 + (exitWater*exitWater)*0.24)) * vec2(fxAsp, 1.0);
                        scale.z  = eyeInWater ? 0.0 : exitWater;
                        float waterDrops = texture(noisetex, (fxUV - vec2(0.0, scale.z)) * scale.xy).r;
                        if (eyeInWater) waterDrops = 0.0;
                        if (!eyeInWater && exitWater > 0.0)
                            waterDrops = sqrt(min(max(waterDrops - (1.0-sqrt(exitWater))*0.68, 0.0)
                                                 * (1.0 + exitWater), 1.0)) * 0.31;
                        distortmask = max(distortmask, waterDrops);
                    }
                    // ---- ВСПЛЕСК при погружении ----
                    if (enterWater > 0.0) {
                        vec2 zoomTC = 0.5 + (fxUV - 0.5) * (1.0 - (1.0 - sqrt(1.0 - enterWater)));
                        float waterSplash = texture(noisetex, zoomTC * vec2(fxAsp, 1.0)).r
                                          * (1.0 - enterWater);
                        distortmask = max(distortmask, waterSplash);
                    }
                    // ---- HEAT DISTORTION (жар над огнём/после лавы) ----
                    if (exitLava > 0.0) {
                        vec2 zoomin = 0.5 + (fxUV - 0.5)
                                    * (1.0 - pow(1.0 - clamp(-fxUV.y*0.5 + 0.74, 0.0, 1.0), 1.0))
                                    * (1.0 - pow(1.0 - exitLava, 2.0));
                        float flameDistort = texture(noisetex, zoomin * vec2(fxAsp, 1.0)
                                                     - vec2(0.0, fxTime * 0.32)).b
                                           * clamp(-fxUV.y*0.32 + 0.32, 0.0, 1.0)
                                           * ON_FIRE_DISTORT_STRENGTH * exitLava;
                        distortmask = max(distortmask, flameDistort);
                    }
                }
                // ---- APPLY DISTORTION: zoomUV = 0.5 + (tc-0.5)*(1-distortmask) -> на ЛУЧ ----
                if (distortmask > 0.0005) {
                    vec2 zoomUV = vec2(u, v) * (1.0 - distortmask);   // эквивалент формулы Eclipse
                    dir = normalize(forward.xyz + (zoomUV.x*left.w)*right + (zoomUV.y*up.w)*up.xyz);
                }
                // ТОШНОТА — ванильный wobble (в Eclipse её нет, это эффект зелья MC)
                if (fx2.x > 0.001) {
                    float tn  = params.y * 0.06;
                    float amp = fx2.x * 0.16;
                    float ang = sin(tn) * amp;
                    float ca = cos(ang), sa = sin(ang);
                    vec2  uw = vec2(u*ca - v*sa, u*sa + v*ca) * (1.0 + amp*0.55*sin(tn*1.7));
                    dir = normalize(forward.xyz + (uw.x*left.w)*right + (uw.y*up.w)*up.xyz);
                }
                // Под водой: лёгкое анимированное искажение пространства (рябь луча).
                // M8.114: фаза — от МИРОВОГО направления луча, а не от экрана (u,v): раньше
                // узор был пришит к экрану, и при повороте камеры мир «плыл» под неподвижной
                // рябью. Теперь рябь пришита к миру — повороты её не таскают (просьба).
                if (params.x > 0.5) {
                    // M8.117: якорь фазы — МИРОВАЯ ТОЧКА в ~6 блоках по лучу (не направление!).
                    // Направленческий якорь (M8.114) не таскался поворотами, но при ПОГРУЖЕНИИ
                    // мир ехал сквозь неподвижный узор — эффект «ускорялся» (репорт). Теперь
                    // узор приклеен к самому миру: повороты и спуск его не трогают, при
                    // плавании он естественно проплывает мимо.
                    float tm = params.y;
                    vec3 pw = origin.xyz + dir * 6.0;
                    float ph1 = pw.x * 2.1 + pw.y * 1.3 + pw.z * 1.7;
                    float ph2 = pw.x * 1.2 + pw.y * 2.4 + pw.z * 0.8;
                    dir = normalize(dir + right * (0.006*sin(tm*0.17 + ph1))
                                        + up.xyz * (0.006*cos(tm*0.14 + ph2)));
                }

                rayQueryEXT rq;
                // Без форс-opaque: CUTOUT-кандидаты идут через альфа-тест (walkRay)
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsNoneEXT, 0xFF, origin.xyz, 0.05, dir, 4096.0);
                walkRay(rq, 0u);   // первичный луч — пропускает тело игрока (маска)

                vec3 col;
                bool p1Water = false;   // первичный луч мира попал в воду? (для эффекта руки под водой)
                vec3 bodies = vec3(0.0);   // диски солнца/луны — гасим их за облаками ОТДЕЛЬНО
                float p1T = 1e30;       // дистанция попадания первичного луча мира
                if (rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT) {
                    float t = rayQueryGetIntersectionTEXT(rq, true);
                    p1T = t;
                    vec3 hp = origin.xyz + t*dir;

                    // --- данные попавшего треугольника ---
                    uint rawId = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
                    bool isEnt = (rawId & 0x800000u) != 0u;      // бит 23 = «сущность»
                    bool isWater = (rawId & 0x400000u) != 0u;    // бит 22 = «вода»
                    p1Water = isWater;
                    uint iId  = rawId & 0x3FFFFFu;
                    uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
                    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
                    float w0 = 1.0-bary.x-bary.y, w1 = bary.x, w2 = bary.y;
                    Verts vb = refs[iId];
                    uvec3 tv = triVerts(prim);
                    // M8.141b: ТЁПЛЫЙ эмиссивный translucent-блок (ЛАВА) визуально непрозрачен и
                    // светится. Весь translucent-слой несёт бит «вода», и лава уходила в водную ветку
                    // МИМО selfEmission -> на дистанции её красил буфер тёплым фолбэком. Если эмиссия
                    // ТЁПЛАЯ (em.r > em.b, лава) — шейдим как ОПАК: selfEmission даёт свой цвет на любой
                    // дистанции. ⚠️ M8.144e: НЕЗЕР-ПОРТАЛ (эмиссия ХОЛОДНАЯ, фиолет em.b > em.r) НЕ
                    // трогаем — он должен остаться ПРОЗРАЧНЫМ (как ваниль). Вода/стекло не тронуты.
                    vec3 emGBA = texture(materialMap, triUV(vb, tv, bary)).gba;
                    if (isWater && !isEnt
                            && dot(emGBA, vec3(1.0)) > 0.004 && emGBA.r > emGBA.b) {
                        isWater = false; p1Water = false;
                    }
            #ifdef ENT_TEX
                    // M8.146: ОСАДКИ живут в ОТДЕЛЬНОМ BLAS, а у него prim'ы идут С НУЛЯ — значит
                    // quadTex для капель читать НЕЛЬЗЯ (попадём в чужой квад). Опознаём каплю по
                    // ИНДЕКСУ ИНСТАНСА (rtCfg7.x), а слот её текстуры берём из rtCfg7.y: у осадков
                    // текстура одна на кадр, поэтому per-quad таблица им и не нужна.
                    bool isWeather = isEnt && cam.rtCfg7.x >= 0.0
                                            && abs(float(iId) - cam.rtCfg7.x) < 0.5;
                    uint qtf = quadFlags(iId, prim);   // у осадков — «частица» + слот из конфига
                    bool isPartic  = isEnt && (qtf & 0x20000000u) != 0u;  // частица
                    bool isBolt    = isEnt && (qtf & 0x08000000u) != 0u;  // МОЛНИЯ
                    bool isEmis    = isEnt && (qtf & 0x04000000u) != 0u;  // светящийся слой
            #else
                    bool isWeather = false;
                    bool isPartic  = false;
                    bool isBolt    = false;
                    bool isEmis    = false;
            #endif
                    // ⚠️ ОСАДКИ И ЧАСТИЦЫ — ЧАСТИЧНО реактивные, и это компромисс, а не полумера.
                    // Капля летит вниз с огромной экранной скоростью, а вектор движения мы ей даём
                    // только от КАМЕРЫ: сеть копила яркие штрихи поверх старых, и при повороте они
                    // слипались в светлые кляксы. Но полный запрет накопления (1.0) сломал сами капли:
                    // тонкий штрих в 1280x720 без накопления не собирается в целую линию при апскейле —
                    // капли пошли рваными обрывками. 0.45 гасит шлейфы, но оставляет сети material
                    // для реконструкции самой капли.
                    // ⚠️ КАПЛЯ — ЭТО НЕ ЛЕТЯЩАЯ ГЕОМЕТРИЯ. Ваниль рисует НЕПОДВИЖНЫЕ квады, по которым
                    // каждый кадр СДВИГАЕТ UV — падает текстура, а не меш. Поэтому вектор движения у
                    // них выходил нулевым, и стоило игроку ОСТАНОВИТЬСЯ, как DLSS видела «пиксель не
                    // двигался» и держала старую историю: половина капель оставалась от прошлых кадров.
                    // (В движении камера сама давала им вектор — потому на ходу капли и выглядели
                    // нормально.) Даём им скорость их ВИДИМОГО падения — тогда сеть повезёт историю
                    // вниз вместе со струями и сможет их накапливать честно.
                    if (isWeather) g_vel = vec3(0.0, -RAIN_FALL_SPEED, 0.0);
                    if (isPartic)  g_react = 0.45;   // частицы движутся сами — им хватает частичной реактивности
                    uint matId = 0u;   // материал блока (карта материалов) — заполним для не-сущности
                    vec3 vtint = vec3(1.0);   // ЦВЕТ ВЕРШИНЫ: у редстоун-пыли в нём закодирована СИЛА СИГНАЛА

                    if (isWater) {
                        uint transMat = uint(texture(materialMap, triUV(vb, tv, bary)).r * 255.0 + 0.5);
                        // ⚠️ ВОДА — ЭТО ТОЛЬКО ВОДА (30). Раньше водой считалось всё прозрачное без
                        // материала, и стеклянная панель, чей спрайт в карту не попал, вела себя как
                        // вода: преломляла руку, синила мир. Теперь воду опознаём по её собственной
                        // текстуре, а ВСЁ ОСТАЛЬНОЕ прозрачное (стекло, панели, витражи) — стекло.
                        // Так новый прозрачный блок больше никогда не «станет водой» по умолчанию.
                        if (transMat != 30u) {
                            // ⚠️ ЭТО НЕ ВОДА. Прозрачные блоки несут тот же бит 22, что и вода, поэтому
                            // вся «водная» логика (преломление РУКИ, эффекты погружения) топила руку за
                            // стеклом. Снимаем флаг: рука рисуется поверх, как и должна.
                            p1Water = false;
                            // === СТЕКЛО/ЛЁД/СЛИЗЬ/МЁД (M8.25a) ===
                            // Блок = СВОЯ ТЕКСТУРА (её альфа: лёд 0.75, слизь 0.70, мёд 0.74,
                            // стекло 0..1) ПОВЕРХ того, что за ним, + френелевский блик.
                            // ⚠️ Раньше свою текстуру не рисовали вовсе -> лёд был зеркалом без текстуры.
                            vec3 gp0 = vPos(vb, tv.x);
                            vec3 gn = normalize(cross(vPos(vb, tv.y) - gp0, vPos(vb, tv.z) - gp0));
                            if (dot(gn, dir) > 0.0) gn = -gn;
                        #ifdef RT_PBR
                            // РЕЛЬЕФ СТЕКЛА/ЛЬДА ИЗ ПАКА: у льда в паках своя _n (трещины, наледь) —
                            // добавляем к геометрической нормали грани, как и на воде (см. ветку ВОДЫ).
                            {
                                vec3 gT, gB;
                                vec2 gu0 = vUV(vb,tv.x), gu1 = vUV(vb,tv.y), gu2 = vUV(vb,tv.z);
                                triFrame(gp0, vPos(vb,tv.y), vPos(vb,tv.z), gu0, gu1, gu2, gn, gT, gB);
                                gn = pbrNormalAdd(pbrUV(triUV(vb, tv, bary), gu0, gu1, gu2), gn, gT, gB);
                            }
                        #endif
                            vec2 guv  = triUV(vb, tv, bary);
                            vec4 gtex = atlasFetch(guv);
                            vec3 gvc  = w0*vCol(vb,tv.x) + w1*vCol(vb,tv.y) + w2*vCol(vb,tv.z);
                            vec3 galb = pow(gtex.rgb * gvc, vec3(2.2));
                            float gaa = clamp(gtex.a, 0.0, 1.0);        // плотность СВОЕЙ поверхности

                            float gior, gF0, gTint, gBend;
                            if (transMat == 11u)      { gior = 1.31; gF0 = 0.05;  gTint = 0.25; gBend = 0.10; }  // лёд
                            else if (transMat == 12u) { gior = 1.35; gF0 = 0.03;  gTint = 0.55; gBend = 0.14; }  // слизь
                            else if (transMat == 13u) { gior = 1.49; gF0 = 0.04;  gTint = 0.55; gBend = 0.16; }  // мёд
                            else                      { gior = 1.52; gF0 = 0.043; gTint = 0.30; gBend = 0.10; }  // стекло

                            vec2 glm = w0*vLight(vb,tv.x) + w1*vLight(vb,tv.y) + w2*vLight(vb,tv.z);

                            // GUIDE: стекло/лёд/слизь/мёд — гладкий диэлектрик со своей текстурой
                            g_normal = gn;
                            g_viewZ  = dot(hp - origin.xyz, forward.xyz);
                            g_diff   = galb * gaa;
                            g_spec   = vec3(gF0);
                            g_rough  = 0.08;

                            // --- СВОЯ ПОВЕРХНОСТЬ, освещённая (как обычная грань) ---
                            // ⚠️ РАМКА СТЕКЛА — ЭТО НАСТОЯЩАЯ ПОВЕРХНОСТЬ. В текстуре block/glass
                            // непрозрачны (альфа=255) только колонки/строки по краю — это рамка; всё
                            // внутри почти прозрачно (альфа~31). У панели рукав берёт текстуру один-к-
                            // одному, поэтому рамочная колонка садится ровно НА ГРАНИЦУ БЛОКА, и на
                            // стыке двух панелей их рамки встречаются — законная перемычка (в ванили
                            // она тоже есть). Но я освещал её дешёвым небом (0.28), пока обычные блоки
                            // получают трассируемый амбиент (4 луча, 0.45) — перемычка выходила ВЧЕТВЕРО
                            // темнее неба за стеклом и читалась как чёрный крест «не на месте».
                            // Замер по кадру: полоса (36,84,127) против стекла (150,198,239).
                            // Даём стеклу тот же амбиент, что и блокам. Лучи амбиента (проход 2)
                            // прозрачные грани не ловят — панель не затеняет сама себя.
                            uint gseed = (pix.y * width + pix.x) * 9781u + 7u + frameSeed();
                            vec3 gskyL = vec3(0.0);
                            for (int gi = 0; gi < 4; gi++) {
                                vec3 gad = cosineDirStrat(gn, gseed, gi, 4);
                                rayQueryEXT garq;
                                rayQueryInitializeEXT(garq, tlas, gl_RayFlagsTerminateOnFirstHitEXT,
                                        0x03, hp + gn*0.02, 0.001, gad, 64.0);
                                walkRay(garq, 2u);
                                if (rayQueryGetIntersectionTypeEXT(garq, true)
                                        == gl_RayQueryCommittedIntersectionNoneEXT) gskyL += skyBase(gad);
                                else                                                gskyL += vec3(0.30, 0.27, 0.22) * mix(0.08, 1.0, dayAmt());
                            }
                            vec3 gamb = gskyL * (0.45 / 4.0) * mix(0.40, 1.0, dayAmt())
                                      * (0.06 + 0.94*glm.y) + MIN_LIGHT;
                            float gndl = max(dot(gn, SUN_DIR_f()), 0.0);
                            float gsun = (gndl > 0.0 && !inShadow(hp + gn*0.02, SUN_DIR_f(), false)) ? gndl : 0.0;
                            gsun *= cloudShadow(hp);
                            // M8.138b: направленность блок-света и в этом (вторичном) проходе
                            vec3 gbl = blockLightEclipse(glm.x, lightTint(hp));
                            {
                                vec3 bld = blockLightDir(hp);
                                float conf = length(bld);
                                if (conf > 0.15) {
                                    float nd = clamp(dot(gn, bld / conf) * 0.5 + 0.5, 0.0, 1.0);
                                    gbl *= mix(1.0, nd, min(conf, 1.0) * 0.7);
                                }
                            }
                            vec3 gsurf = galb * (gamb + gsun * SUN_COL_f() + gbl);

                            // --- ПРОПУСКАНИЕ. ⚠️ ПЛИТА: свет преломляется на ВХОДЕ и обратно на ВЫХОДЕ,
                            // суммарно луч идёт почти ПРЯМО (лишь смещается вбок). Одиночное преломление
                            // уводило весь мир за стеклом — отсюда был «кривой» вид. Гнём лишь чуть (gBend).
                            vec3 gb = refract(dir, gn, 1.0/gior);
                            vec3 gtdir = (dot(gb, gb) < 1e-6) ? dir : normalize(mix(dir, gb, gBend));
                            vec3 gthru;
                            {
                                // === M8.97: МАРШ сквозь прозрачные (просьбы пользователя) ===
                                // Раньше проход 7 пропускал ВСЁ прозрачное разом: за окном виднелось
                                // голое дно без водной глади, а витраж за стеклом исчезал бесследно.
                                // Теперь шагаем: ОПАК = конец (shadeSimple + дымка по полной дистанции);
                                // ВОДА (материал 30) = лайт-гладь, конец; ЦВЕТНОЕ СТЕКЛО/панель —
                                // ПЕРЕДНЯЯ грань красит луч своим тинтом и идём дальше (задняя грань
                                // только «выпускает»: вход уже покрасил, иначе двойной тинт от блока).
                                // Одинаковые стёкла тонируют повторно чуть-чуть — это физично.
                                // ⚠️ ОБЛАКА И ДЫМКА ЗА СТЕКЛОМ (M8.61) сохранены ниже.
                                vec3 pro = hp + gtdir*0.02;      // старт: изнутри первой панели
                                float travel = 0.0;              // путь ЗА первым стеклом
                                vec3 accum = vec3(0.0);          // поверхности пройденных стёкол (рамки)
                                vec3 trans = vec3(1.0);          // остаточное пропускание (тинт стёкол)
                                vec3 endC  = vec3(0.0);          // чем закончился путь (опак/вода/небо)
                                bool ghit = false;
                                float gt = 1e9;
                                bool decided = false;
                                for (int hop = 0; hop < 4 && !decided; hop++) {
                                    rayQueryEXT gr;
                                    rayQueryInitializeEXT(gr, tlas, gl_RayFlagsNoneEXT, 0xFF, pro, 0.02, gtdir, 4096.0);
                                    walkRay(gr, 8u);   // проход 8: коммитит и прозрачное, и опак
                                    if (rayQueryGetIntersectionTypeEXT(gr, true)
                                            != gl_RayQueryCommittedIntersectionTriangleEXT) break;   // небо
                                    float ht = rayQueryGetIntersectionTEXT(gr, true);
                                    uint hraw = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(gr, true));
                                    if ((hraw & 0x400000u) == 0u) {          // ОПАК — конец пути
                                        ghit = true; gt = travel + ht; decided = true;
                                        endC = shadeSimple(gr, pro, gtdir);
                                        endC = mix(endC, skyBase(gtdir), fogAmt(t + gt));
                                        break;
                                    }
                                    uint hprim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(gr, true));
                                    vec2 hbary = rayQueryGetIntersectionBarycentricsEXT(gr, true);
                                    Verts hvb = refs[hraw & 0x3FFFFFu];
                                    uvec3 htv = triVerts(hprim);
                                    uint hmat = uint(texture(materialMap, triUV(hvb, htv, hbary)).r * 255.0 + 0.5);
                                    if (hmat == 30u) {
                                        // === ВОДА ЗА СТЕКЛОМ — лайт-гладь: волны + Френель +
                                        // небо/солнечный блик + дно с поглощением по глубине ===
                                        ghit = true; gt = travel + ht; decided = true;
                                        vec3 wp = pro + gtdir * ht;
                                        vec3 wnw = waterNormal(wp.xz, params.y * 0.05, origin.xz, t + gt);
                                        vec3 wn2 = normalize(mix(vec3(0.0, 1.0, 0.0), wnw, exp(-(t + gt) * 0.010)));
                                        if (dot(wn2, gtdir) > 0.0) wn2 = -wn2;
                                        float F2 = 0.02 + 0.98 * pow(1.0 - max(dot(-gtdir, wn2), 0.0), 5.0);
                                        // ⚠️ КАК У НАСТОЯЩЕЙ ВОДЫ: отражаем МИР (traceShade), не только
                                        // небо — иначе у берега за окном вода была ярко-голубой, а рядом
                                        // (без стекла) тёмной от отражения деревьев: резало глаз.
                                        float w0w = 1.0 - hbary.x - hbary.y;
                                        vec2 wlm2 = w0w*vLight(hvb,htv.x) + hbary.x*vLight(hvb,htv.y) + hbary.y*vLight(hvb,htv.z);
                                        float waterLit2 = clamp(0.06 + 0.94 * max(wlm2.y, blockLightCurve(wlm2.x) * 0.7), 0.0, 1.0);
                                        vec3 refl2 = traceShade(wp + wn2*0.02, reflect(gtdir, wn2),
                                                               smoothstep(0.0, 0.5, wlm2.y));
                                        vec3 td2 = refract(gtdir, wn2, 1.0/1.33);
                                        vec3 refr2;
                                        if (dot(td2, td2) < 1e-6) refr2 = refl2;
                                        else {
                                            rayQueryEXT wr2;
                                            rayQueryInitializeEXT(wr2, tlas, gl_RayFlagsNoneEXT, 0xFF,
                                                    wp - wn2*0.02, 0.02, td2, 4096.0);
                                            walkRay(wr2, 7u);   // до дна: прозрачные пропускаются
                                            bool bhit = rayQueryGetIntersectionTypeEXT(wr2, true)
                                                      == gl_RayQueryCommittedIntersectionTriangleEXT;
                                            float twd2 = bhit ? rayQueryGetIntersectionTEXT(wr2, true) : 24.0;
                                            vec3 bottom2 = bhit ? shadeSimple(wr2, wp - wn2*0.02, td2)
                                                                : vec3(0.020, 0.038, 0.062) * waterLit2 * waterDayLit();
                                            bottom2 *= exp(-vec3(0.335, 0.106, 0.069) * 0.85 * twd2);
                                            float scat2 = clamp(1.0 - exp(-twd2 * 0.18), 0.0, 0.38);
                                            refr2 = mix(bottom2, vec3(0.016, 0.034, 0.058) * waterLit2 * waterDayLit(), scat2);
                                        }
                                        endC = mix(refr2, refl2, F2);
                                        endC = mix(endC, skyBase(gtdir), fogAmt(t + gt));
                                        break;
                                    }
                                    // СТЕКЛО/ПАНЕЛЬ/ВИТРАЖ (задние грани отсёк проход-8, M8.99):
                                    // рисуем ЕГО ПОВЕРХНОСТЬ (рамка/разводы, по альфе текстуры) поверх
                                    // дальнейшего пропускания — «текстура второго стекла видна», —
                                    // и красим остаток его тинтом, как делает первая панель.
                                    float hw0 = 1.0 - hbary.x - hbary.y;
                                    vec4 htex = atlasFetch(triUV(hvb, htv, hbary));
                                    vec3 hvc = hw0*vCol(hvb,htv.x) + hbary.x*vCol(hvb,htv.y) + hbary.y*vCol(hvb,htv.z);
                                    vec3 halb = pow(htex.rgb * hvc, vec3(2.2));
                                    float hT = (hmat == 11u) ? 0.25 : (hmat == 12u || hmat == 13u) ? 0.55 : 0.30;
                                    float ha = clamp(htex.a, 0.0, 1.0);
                                    vec3 hsurf = shadeSimple(gr, pro, gtdir);   // рамка с базовым светом
                                    hsurf = mix(hsurf, skyBase(gtdir), fogAmt(t + travel + ht));
                                    accum += trans * hsurf * ha;
                                    trans *= (1.0 - ha);
                                    trans *= mix(vec3(1.0), halb, hT);
                                    pro += gtdir * (ht + 0.02);
                                    travel += ht + 0.02;
                                }
                                if (!decided) {
                                    // ⚠️ ПРОМАХ = НЕБО, ДАЖЕ ЕСЛИ ЛУЧ ИДЁТ ВНИЗ (M8.62): за окном может
                                    // законно кончиться прогруженная даль. Сюда же — экзотика из >4
                                    // прозрачных подряд: дальше не маршируем, отдаём небо.
                                    endC = skyBase(gtdir) + stars(gtdir);
                                }
                                // Облака на отрезке ЗА стеклом: до геометрии (тогда туча видна перед
                                // холмом) или до бесконечности (если там небо).
                                {
                                    vec4 gcl = marchClouds(hp + gtdir*0.02, gtdir, gt, 1);
                                    float gtr = max(1.0 - gcl.a, 0.0);
                                    vec3 gbd = ghit ? vec3(0.0) : skyBodies(gtdir);   // солнце/луна — только в небе
                                    endC = endC * gtr + gbd * gtr * gtr * gtr + gcl.rgb;
                                }
                                gthru = accum + trans * endC;           // рамки стёкол + окрашенное пропускание
                                gthru *= mix(vec3(1.0), galb, gTint);   // тинт ПЕРВОЙ панели (как раньше)
                            }

                            // --- ОТРАЖЕНИЕ (Френель) ---
                            float gndv = max(dot(-dir, gn), 0.0);
                            float gF = gF0 + (1.0 - gF0) * pow(1.0 - gndv, 5.0);
                            vec3 grefl = traceShade(hp + gn*0.02, reflect(dir, gn), smoothstep(0.0, 0.5, glm.y));

                            vec3 gbody = mix(gthru, gsurf, gaa);       // своя текстура ПОВЕРХ пропускания
                            col = mix(gbody, grefl, gF);
                            float gfog = fogAmt(t);
                            col = mix(col, skyBase(dir), gfog);
                        } else {
                        // --- ВОДА: нормаль по типу грани ---
                        // Верхняя грань -> аналитические ВОЛНЫ (из мировых XZ, гладко).
                        // Боковая грань (вертикальная, у ступеней дна) -> её геометрическая
                        // нормаль: иначе up-нормаль заставляет её отражать ТЁМНУЮ глубину =
                        // синие клинья на мелководье.
                        vec3 wp0 = vPos(vb,tv.x);
                        vec3 wg = cross(vPos(vb,tv.y)-wp0, vPos(vb,tv.z)-wp0);
                        float wl = length(wg); wg = wl>1e-6 ? wg/wl : vec3(0.0,1.0,0.0);
                        if (dot(wg, dir) > 0.0) wg = -wg;
                        vec3 wn;
                        if (wg.y > 0.5) {                                  // верхняя грань -> рябь
                            // Мировые XZ напрямую (без mod-256 woff): у симплекса точность
                            // держится до ~10к блоков, а woff прыгал на границах 256 при
                            // движении = «скачки волн влево-вправо». Теперь волны world-locked.
                            vec3 wnw = waterNormal(hp.xz, params.y * 0.05, origin.xz, t);
                            // ⚠️ ВРЕМЕННО ОТКЛЮЧЕНО (перф, дождь): кольца от ударов капель на глади.
                            // rainRipple циклит по буферу ударов на КАЖДОМ пикселе воды — дорого,
                            // лагало с самого начала. Вернём отдельной вехой (напр. запекать рябь в
                            // текстуру нормалей вместо per-pixel цикла). Эффект никому не обещан.
                            // if (rainF() > 0.01) {                          // кольца от реальных ударов капель
                            //     vec2 rp = rainRipple(hp.xz, params.y);
                            //     wnw = normalize(wnw + vec3(rp.x, 0.0, rp.y) * rainF() * 1.4);
                            // }
                            float waveFade = exp(-t * 0.010);            // дальняя вода спокойнее (антиалиасинг)
                            // ⚠️ ТЕЧЕНИЕ (вода вне водоёма: ручей по ступеням) — грань наклонная.
                            // Рябь считается от ровной глади, поэтому на склоне её глушим, а саму
                            // рябь кладём поверх ГЕОМЕТРИЧЕСКОЙ нормали грани, а не поверх вертикали.
                            float flatness = clamp((wg.y - 0.5) * 2.0, 0.0, 1.0);   // 'flat' — ключевое слово GLSL
                            wn = normalize(mix(wg, wnw, waveFade * flatness));
                        } else {
                            wn = wg;                                       // боковая грань — плоская
                        }
                        if (dot(wn, dir) > 0.0) wn = -wn;
                    #ifdef RT_PBR
                        // РЕЛЬЕФ ГЛАДИ ИЗ ПАКА. У AVPBR/SPBR у воды есть своя _n — мелкая рябь, которой
                        // наша аналитическая волна не даёт. Функция именно ДОБАВЛЯЕТ наклон к уже
                        // возмущённой нормали глади: там, где пак оставил карту плоской, волна остаётся
                        // как была (замена стёрла бы её). Кадр берём первый — рябь будет статичной, но
                        // вода не «замирает»: крупные волны у нас анимированные.
                        {
                            vec3 wT, wB;
                            vec2 wu0 = vUV(vb,tv.x), wu1 = vUV(vb,tv.y), wu2 = vUV(vb,tv.z);
                            triFrame(wp0, vPos(vb,tv.y), vPos(vb,tv.z), wu0, wu1, wu2, wg, wT, wB);
                            wn = pbrNormalAdd(pbrUV(triUV(vb, tv, bary), wu0, wu1, wu2), wn, wT, wB);
                        }
                    #endif
                        // GUIDE: вода — гладкий диэлектрик, диффуза нет (цвет даёт преломление)
                        g_react  = 1.0;   // рябь бежит по неподвижной поверхности — накапливать нельзя
                        g_normal = wn;
                        g_viewZ  = dot(hp - origin.xyz, forward.xyz);
                        g_diff   = vec3(0.0);
                        g_spec   = vec3(0.02);
                        g_rough  = 0.06;

                        // ⚠️ СВЕТ ВОДЫ. Собственный цвет воды (рассеяние) — это НЕ излучение:
                        // он виден только если воду есть чем осветить. Раньше он был константой,
                        // и в тёмной пещере ручей СВЕТИЛСЯ синим сам по себе. Берём уровень света
                        // из вершин самой воды (она — обычная геометрия чанка, lightmap у неё есть).
                        vec2 wlm = w0*vLight(vb,tv.x) + w1*vLight(vb,tv.y) + w2*vLight(vb,tv.z);
                        // ⚠️ Вершинный lightmap знает только СТАТИЧЕСКИЙ свет. Факел В РУКЕ туда не
                        // пишется (он существует только в RT), поэтому вода его не видела и оставалась
                        // чёрной, пока камень вокруг был освещён. Добавляем динамический уровень.
                        vec3 hlPos = origin.xyz - vec3(0.0, 0.35, 0.0);
                        float hlLvl = max(wparams.z*15.0 + 1.0 - distance(hp, hlPos), 0.0) / 15.0;
                        float wBlock = max(wlm.x, hlLvl);
                        float waterLit = clamp(0.06 + 0.94 * max(wlm.y, blockLightCurve(wBlock) * 0.7), 0.0, 1.0);
                        // Эта ветка — только С ВОЗДУХА (из-под воды первичный проходит сквозь воду;
                        // подводная гладь = аналитическая плоскость в main). Отражение + преломление.
                        float ndv = max(dot(-dir, wn), 0.0);
                        float F = 0.02 + 0.98*pow(1.0-ndv, 5.0);           // Френель (F0 воды ~0.02)
                        // ОТРАЖЕНИЕ: зеркальный луч ловит мир/сущности/ИГРОКА/небо.
                        // Небо гасим доступом неба у воды (wlm.y): в пещере кромка не светит.
                        vec3 refl = traceShade(hp + wn*0.02, reflect(dir, wn), smoothstep(0.0, 0.5, wlm.y));
                    #ifdef RT_PBR
                        // ШЕРOХОВАТОСТЬ ПАКА ГЛУШИТ ЗЕРКАЛО ГЛАДИ. У воды в паках она почти всегда
                        // нулевая (гладкость 255 = ровная вода), но мутной/илистой воде пак вправе её
                        // поднять — тогда острый луч заменяем аналитическим небом, как и на блоках.
                        // F0 воды (0.02) НЕ берём из пака: это физическая константа, а не настройка.
                        {
                            vec3 wTint; float wF0, wStr, wMetal, wEmis;
                            // Альбедо для случая «G = 255 (F0 равно альбедо)» передаём ЧЁРНЫМ: вода
                            // прозрачная, её цвет даёт преломление, а не тексель. Такой тексель даёт
                            // нулевой F0 и трактуется как «данных нет» — вода остаётся на своей
                            // физической константе 0.02, и пак не может сделать её зеркалом.
                            pbrSurface(pbrUV(triUV(vb, tv, bary), vUV(vb,tv.x), vUV(vb,tv.y), vUV(vb,tv.z)),
                                       vec3(0.0), wTint, wF0, wStr, wMetal, wEmis);
                            float wSharp = clamp(1.0 - (1.0 - wStr) * 1.6, 0.0, 1.0);
                            refl = mix(skyRefl(reflect(dir, wn)), refl, wSharp);
                        }
                    #endif
                        // ПРЕЛОМЛЕНИЕ: вниз в воду до дна, тинт поглощением Eclipse по глубине
                        vec3 tdir = refract(dir, wn, 1.0/1.33);
                        vec3 refr;
                        if (dot(tdir,tdir) < 1e-6) { refr = refl; }        // полное внутр. отражение
                        else {
                            rayQueryEXT wr;
                            rayQueryInitializeEXT(wr, tlas, gl_RayFlagsNoneEXT, 0xFF, hp - wn*0.02, 0.02, tdir, 4096.0);
                            walkRay(wr, 7u);   // преломление сквозь гладь: своё тело НЕ показываем (мобы/дно — да)
                            // Поглощение по глубине + ЛЁГКОЕ рассеяние (цвет воды): мелководье
                            // остаётся прозрачным (дно видно) с намёком на синь, глубина уходит в
                            // синий. Без «газировки»: цвет приглушённый, база 0, cap 0.55.
                            // Цвет рассеяния и «дальняя муть» гасятся светом воды (в пещере -> ~0)
                            // ⚠️ ПРОЗРАЧНЕЕ (просьба): синь была слишком плотной — вода читалась как
                            // «голубая газировка». Рассеяние приглушено, поглощение мягче, а доля
                            // рассеяния на глубине ограничена сильнее — дно видно дальше.
                            vec3 wScatter = vec3(0.016, 0.034, 0.058) * waterLit * waterDayLit();
                            float twd = (rayQueryGetIntersectionTypeEXT(wr, true) == gl_RayQueryCommittedIntersectionTriangleEXT)
                                      ? rayQueryGetIntersectionTEXT(wr, true) : 24.0;
                            vec3 bottom = (rayQueryGetIntersectionTypeEXT(wr, true) == gl_RayQueryCommittedIntersectionTriangleEXT)
                                        ? shadeSimple(wr, hp - wn*0.02, tdir) : vec3(0.020, 0.038, 0.062) * waterLit * waterDayLit();
                            bottom *= exp(-vec3(0.335,0.106,0.069) * 0.85 * twd);  // поглощение мягче
                            // M8.127: сквозь гладь виден МОБ -> гайды пикселя описывают МОБА, а не
                            // гладь (рекомендация NVIDIA для прозрачных поверхностей: гайды — от
                            // первого НЕпрозрачного попадания). Гайды глади неподвижны, и RR копил
                            // историю — за плывущим аксолотлем тянулся шлейф. Одной глубины мало
                            // (M8.127b): моб плывёт ГОРИЗОНТАЛЬНО, глубина почти константа — самый
                            // сильный сигнал сброса истории у сети это АЛЬБЕДО (розовый аксолотль
                            // против сине-серого дна) + нормаль.
                            if (rayQueryGetIntersectionTypeEXT(wr, true) == gl_RayQueryCommittedIntersectionTriangleEXT
                                    && (uint(rayQueryGetIntersectionInstanceCustomIndexEXT(wr, true)) & 0x800000u) != 0u) {
                                g_viewZ = dot(hp + tdir * twd - origin.xyz, forward.xyz);
                            #ifdef ENT_TEX
                                uint wIid  = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(wr, true)) & 0x3FFFFFu;
                                uint wPrim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(wr, true));
                                vec2 wBary = rayQueryGetIntersectionBarycentricsEXT(wr, true);
                                Verts wVb = refs[wIid];
                                uvec3 wTv = triVerts(wPrim);
                                vec4 wVc = unpackUnorm4x8(wVb.w[wTv.x*9u + 3u]);
                                vec4 wEtx = entFetch(quadTex[wPrim >> 1u] & 0xFFFFu, entUV(wVb, wTv, wBary));
                                g_diff = pow(wEtx.rgb * wVc.rgb, vec3(2.2));
                                vec3 wP0 = entPos(wVb, wTv.x);
                                vec3 wNrm = cross(entPos(wVb, wTv.y) - wP0, entPos(wVb, wTv.z) - wP0);
                                float wNl = length(wNrm);
                                wNrm = wNl > 1e-6 ? wNrm / wNl : vec3(0.0, 1.0, 0.0);
                                if (dot(wNrm, tdir) > 0.0) wNrm = -wNrm;
                                g_normal = wNrm;
                                g_spec = vec3(0.04); g_rough = 0.85;   // моб — шершавый диэлектрик, не вода
                            #endif
                            }
                            float scat = clamp(1.0 - exp(-twd * 0.18), 0.0, 0.38);            // мелко ~0, растёт с глубиной
                            refr = mix(bottom, wScatter, scat);
                            // M8.15 ОБВОДКА СКВОЗЬ ВОДУ: рисуем её на ПРЕЛОМЛЁННОМ луче. Это и
                            // ПОЯВЛЯЕТ рамку блока под водой (на первичном луче она отсекалась
                            // гладью), и ИСКАЖАЕТ её ровно так же, как искажён сам блок.
                            if (outlineHit(hp - wn*0.02, tdir, twd))
                                refr = mix(refr, vec3(0.0), 0.8);
                        }
                        col = mix(refr, refl, F);   // блик даёт skyRefl в отражённом луче
                        // БЛИК ФАКЕЛА на воде: точечный источник в TLAS отсутствует, зеркальный
                        // луч его «промахивает» — считаем блик аналитически. ⚠️ ТУГОЙ (pow 400):
                        // широкий (pow 64) размазывался в «пластиковую линзу». И ТОЛЬКО на верхней
                        // грани (wg.y>0.5) — на боковых он бликовал вертикальной полосой на кромке
                        // водоём/земля («засвет на границе»). На ряби тугой глинт бьётся на искры.
                        if (hlLvl > 0.01 && wg.y > 0.5) {
                            float sp = pow(max(dot(reflect(dir, wn), normalize(hlPos - hp)), 0.0), 400.0);
                            col += handCol.rgb * sp * hlLvl * 1.4;
                        }
                        // M8.147: подземная вода тоже тонет в темноте, а не в ярком небе
                        float fogw = fogAmt(t);
                        col = mix(col, mix(MIN_LIGHT * 2.0, skyBase(dir), smoothstep(0.0, 0.35, wlm.y)), fogw);
                        }   // конец ветки ВОДЫ (else от transMat)
                    } else {
                    // Геометрическая нормаль из настоящих вершин + альбедо по типу
                    vec3 p0, e1, e2;
                    vec3 albedoS;                                 // sRGB до линеаризации
                    vec2 hitUV = vec2(0.0);                       // атлас-UV попадания (для эмиссии светлячков)
                    if (isEnt) {
                        p0 = entPos(vb,tv.x);
                        e1 = entPos(vb,tv.y) - p0; e2 = entPos(vb,tv.z) - p0;
                        vec4 c = unpackUnorm4x8(vb.w[tv.x*9u + 3u]);
            #ifdef ENT_TEX
                        // ⚠️ У молнии (бит 27) ТЕКСТУРЫ НЕТ — выборка из чужой по нулевым UV дала бы мусор.
                        // Её цвет задан прямо в вершинах (ваниль так и рисует болт).
                        if ((qtf & 0x08000000u) != 0u) {
                            albedoS = c.rgb;
                        } else {
                            // M8.147: слот уже разрешён в quadFlags (у осадков — из униформы rtCfg7.y,
                            // т.к. их prim'ы указывали бы на чужой квад в общем массиве).
                            vec4 etx = entFetch(qtf & 0xFFFFu, entUV(vb, tv, bary));
                            albedoS = etx.rgb * c.rgb;
                            if (etx.a > 1.5) isEmis = true;   // M8.126: фулбрайт-метка пака
                        }
            #else
                        albedoS = vec3(0.78) * c.rgb;             // без ENT_TEX — плоский цвет
            #endif
                    } else {
                        p0 = vPos(vb,tv.x);
                        e1 = vPos(vb,tv.y) - p0; e2 = vPos(vb,tv.z) - p0;
                        vec2 uv   = triUV(vb, tv, bary);
                        hitUV = uv;
                        matId = uint(texture(materialMap, uv).r * 255.0 + 0.5);   // 0 = обычный блок
                        vec3 tint = w0*vCol(vb,tv.x) + w1*vCol(vb,tv.y) + w2*vCol(vb,tv.z);
                        vtint = tint;
                        albedoS = atlasFetch(uv).rgb * tint;
                    }
                    vec3 nrm = cross(e1, e2);
                    float nl = length(nrm);
                    nrm = nl > 1e-6 ? nrm / nl : vec3(0.0, 1.0, 0.0);
                    if (dot(nrm, dir) > 0.0) nrm = -nrm;

                    // Атлас/цвета уже sRGB — раскодируем в ЛИНЕЙНОЕ перед освещением
                    // (иначе финальная гамма даёт двойное осветление).
                    vec3 albedo = pow(albedoS, vec3(2.2));
                    if (!isEnt) albedo = applyCracks(albedo, hp, nrm);   // M8.16 трещины ломания

                    // === M8.162 PBR ИЗ РЕСУРСПАКА (labPBR) ===
                    // Всё, что пак говорит о поверхности: рельеф из _n и шероховатость/F0/металл/эмиссия
                    // из _s. Значения по умолчанию НЕЙТРАЛЬНЫ (рельефа нет, зеркала нет, металла нет,
                    // свечения нет), поэтому без пака блок шейдится ровно как раньше, а с паком
                    // получает ДОБАВКУ. Складываем через max/min, а не подменяем: иначе пак отбирал бы
                    // блеск у блоков, которым наша таблица материалов его уже дала (см. RtPbrMaps).
                    float pbrStr = 0.0, pbrF0 = 0.0, pbrMetal = 0.0, pbrEmis = 0.0, pbrRough = 1.0;
                    vec3  pbrTint = vec3(1.0);
                #ifdef RT_PBR
                    if (!isEnt) {   // у сущностей своя развёртка и свои текстуры — карты блоков не для них
                        vec3 T, B;
                        vec2 u0 = vUV(vb,tv.x), u1 = vUV(vb,tv.y), u2 = vUV(vb,tv.z);
                        triFrame(p0, p0 + e1, p0 + e2, u0, u1, u2, nrm, T, B);
                        vec2 puv = pbrUV(hitUV, u0, u1, u2);         // UV с отступом по карте пака
                        nrm = pbrNormal(puv, nrm, T, B);                   // рельеф встаёт в освещение и в гайды
                        pbrSurface(puv, albedo, pbrTint, pbrF0, pbrStr, pbrMetal, pbrEmis);
                        pbrRough = clamp(1.0 - pbrStr, 0.02, 1.0);
                    }
                #endif
                    """,
                    """
                    // --- GUIDE для DLSS: что за поверхность в этом пикселе ---
                    g_normal = nrm;
                    g_viewZ  = dot(hp - origin.xyz, forward.xyz);
                    if (!isEnt && matId > 0u && matId < 10u) {
                        vec3 mt; float mf0, mstr;
                        matProps(matId, mt, mf0, mstr);
                        // Металл (1..4): диффуза нет, отражение красит сам металл. Остальные — диэлектрики.
                        bool metal = matId <= 4u;
                        g_diff  = metal ? vec3(0.0) : albedo;
                        g_spec  = metal ? albedo * mt : vec3(mf0);
                        g_rough = clamp(1.0 - mstr, 0.05, 1.0);          // сильнее зеркало -> глаже
                    } else {
                        g_diff  = albedo;
                        g_spec  = vec3(0.04);                            // обычный диэлектрик
                        g_rough = 0.95;
                    }
                #ifdef RT_PBR
                    // ПАК ГОВОРИТ ТОЧНЕЕ ТАБЛИЦЫ. Гайды DLSS RR — это ФИЗИКА поверхности (диффуз,
                    // зеркальное альбедо, шероховатость), и с данными пака реконструкция чище: сеть
                    // видит полированный металл, а не «серый блок». Металл диффуза не имеет — его цвет
                    // несёт отражение, поэтому диффузное альбедо гасим ровно на металличность пака.
                    if (!isEnt && pbrStr > 0.0) {
                        g_rough = min(g_rough, pbrRough);
                        g_spec  = max(g_spec, pbrTint * pbrF0);
                        g_diff  = albedo * (1.0 - pbrMetal);
                    }
                #endif

                    float ndl = max(dot(nrm, SUN_DIR_f()), 0.0);
                    float sun = (ndl>0.0 && dayDirect() > 0.01 && !inShadow(hp + nrm*0.02, SUN_DIR_f(), false)) ? ndl * dayDirect() : 0.0;
                sun *= cloudShadow(hp);   // тень от облаков и в отражениях
                    sun *= cloudShadow(hp);   // M8.20 тень от облаков (одна выборка — почти бесплатно)

                    // Небесный ambient С ВИДИМОСТЬЮ («GI для бедных»): 4 луча в
                    // полусферу — под кустами/в углах темнее, тени наполняются небом.
                    uint seed = (pix.y * width + pix.x) * 9781u + 1u + frameSeed();
                    vec3 skyLight = vec3(0.0);
                    float skyVis = 0.0;                       // доля лучей, дошедших до НЕБА
                    // Число лучей — из настроек. 0 = видимость не трассируем вовсе: небо светит
                    // всюду одинаково (дёшево, но углы и подкустье перестают темнеть).
                #ifndef RT_AMBIENT
                    skyLight = skyBase(nrm) * 4.0;   // амбиент вырезан: небо светит ровно
                    skyVis = 1.0;
                #else
                    int nAmb = int(cam.rtCfg.y);
                    if (nAmb <= 0) {
                        skyLight = skyBase(nrm) * 4.0;   // приводим к яркости «четырёх лучей»
                        skyVis = 1.0;
                    } else {
                    for (int i = 0; i < nAmb; i++) {
                        vec3 ad = cosineDirStrat(nrm, seed, i, nAmb);
                        rayQueryEXT arq;
                        rayQueryInitializeEXT(arq, tlas, gl_RayFlagsTerminateOnFirstHitEXT,
                                0x03, hp + nrm*0.02, 0.001, ad, cam.rtCfg4.z);
                        walkRay(arq, 2u);   // амбиент мира — тело игрока затеняет, рука прозрачна
                        if (rayQueryGetIntersectionTypeEXT(arq, true)
                                == gl_RayQueryCommittedIntersectionNoneEXT) {
                            skyLight += skyBase(ad);
                            skyVis += 1.0 / float(nAmb);
                        } else {
                            skyLight += vec3(0.30, 0.27, 0.22) * mix(0.08, 1.0, dayAmt());   // отскок = отражённое СОЛНЦЕ: ночью гаснет (кусты не светятся)
                        }
                    }
                    skyLight *= 4.0 / float(nAmb);   // ⚠️ сумма по nAmb лучам -> яркость как у 4-х
                    }
                #endif
                    // M8.7 СВЕТ БЛОКОВ (факелы/лава/светокамень) + SKY-LIGHT.
                    // Блоки: свет лежит в вершинах, интерполируем по треугольнику (как цвет/UV).
                    // СУЩНОСТИ: вершинного lightmap у них нет. Раньше стояла константа (0.25, 0.6) —
                    // из-за неё моб в пещере считал, что над ним ПОЛНЕБА, и светился призраком,
                    // пока блоки вокруг честно тонули в темноте. Теперь честно:
                    //   sky   = доля лучей амбиента, реально дошедших до неба (0 в пещере),
                    //   block = уровень от ближних источников (ванильная формула).
                    vec2 lm = isEnt
                            ? vec2(pointBlockLevel(hp),
                                   w0*entSkyLight(vb,tv.x) + w1*entSkyLight(vb,tv.y) + w2*entSkyLight(vb,tv.z))
                            : (w0*vLight(vb,tv.x) + w1*vLight(vb,tv.y) + w2*vLight(vb,tv.z));
                    float blockL = lm.x, skyL = lm.y;

                    // Небо светит только там, куда оно достаёт (в пещере sky-light = 0):
                    // иначе пещеры «подсвечены небом» и факел не читается.
                    // M8.133: в Энде — сине-сиреневый амбиент (почти равномерный: свет там от
                    // дымки, а не от неба сверху), в Аду — тёплый амбиент цвета тумана биома.
                    vec3 ambient;
                    if (dimType() == 2)
                        ambient = endAmb() * (0.55 + 0.45 * skyL) + MIN_LIGHT;
                    else if (dimType() == 1)
                        ambient = cam.rtCfg6.rgb * (0.9 + 0.5 * blockL) + MIN_LIGHT;
                    else
                        ambient = skyLight * (0.45 / 4.0) * mix(0.40, 1.0, dayAmt()) * (0.06 + 0.94 * skyL) + MIN_LIGHT;

                    // Блочный свет — кривая Eclipse (с «горячим пятном») + ЦВЕТ ближних источников:
                    // фонарь душ голубой, медный факел зелёный, портал фиолетовый.
                    vec3 torch = blockLightEclipse(blockL, lightTint(hp)) * flameFlicker(hp);
                    // M8.138: НАПРАВЛЕННОСТЬ статического блок-света (лава/факелы). Поверхности,
                    // повёрнутые К источнику, ярче; отвёрнутые — темнее (но не в ноль: свет
                    // окутывает). Гейт по «уверенности» (длине вектора) — где источника рядом нет,
                    // свет остаётся прежним ненаправленным. Применяем ДО руки/мобов (у них своё).
                    {
                        vec3 bld = blockLightDir(hp);
                        float conf = length(bld);
                        if (conf > 0.15) {
                            float nd = clamp(dot(nrm, bld / conf) * 0.5 + 0.5, 0.0, 1.0);  // half-Lambert
                            torch *= mix(1.0, nd, min(conf, 1.0) * 0.7);                   // 70% макс. направленности
                        }
                    }
                    // + ДИНАМИЧЕСКИЙ свет предмета в руке, с настоящей RT-тенью
                    torch += handheldLight(hp, nrm, wparams.z, origin.xyz, blockL);
                    // ⚠️ ЗАЩИТА БЕЛОГО МЕТАЛЛА (колпак фонаря = сам источник 15): «горячее пятно»
                    // блок-света выжигало его бело-жёлтые тексели в «светлячки». Для яркого
                    // НЕнасыщенного текселя приглушаем блок-свет (пламя — насыщенное — не трогаем).
                    // + свет ГОРЯЩИХ МОБОВ (для блоков; у сущностей он уже в pointBlockLevel)
                    if (!isEnt) torch += dynamicPointLight(hp, nrm);""",
            """
                    // ⚠️ ПЕРЕСВЕТ БЛОК-СВЕТОМ (M8.63). У «горячего пятна» факела пик 1.2, сверху общий
                    // множитель 2.8 — у самого источника выходит ~3.4. Тёмный камень (альбедо ~0.3) от
                    // этого просто становится ярким, а СВЕТЛАЯ кожа жителя (~0.8) даёт 2.7 — вылет за
                    // белое, и весь бок моба сплавлялся в ровное белое пятно. Поджимаем свет факела,
                    // УЖЕ умноженный на альбедо, мягким насыщением: ниже единицы не трогаем вовсе
                    // (вся прежняя настройка пещер цела), выше — плавно заваливаем.
                    vec3 lit = albedo * torch;
                    float lmax = max(lit.r, max(lit.g, lit.b));
                    lit /= (1.0 + max(lmax - 1.0, 0.0) * 0.8);
                    // M8.135: направленный свет. Верхний мир — солнце; ЭНД — затменное светило
                    // (даёт объём: у обелисков освещённая/теневая сторона, тени на земле).
                    vec3 dirLight;
                    if (dimType() == 2) {
                        float ndlo = max(dot(nrm, endOrb()), 0.0);
                        dirLight = (ndlo > 0.0 && !inShadow(hp + nrm*0.02, endOrb(), false))
                                 ? endOrbLight() * ndlo : vec3(0.0);
                    } else {
                        dirLight = sun * SUN_COL_f() * dimDay();
                    }
                    // ⚠️ МЕТАЛЛ НЕ РАССЕИВАЕТ (labPBR): его цвет несёт ОТРАЖЕНИЕ, а не диффуз. Гасим не
                    // в ноль: отражение у нас острое (блюра по шероховатости нет), и полный металл
                    // превратил бы блок в зеркальную плиту без текстуры — эту беду автор таблицы
                    // материалов уже ловил (см. комментарий про 0.85-0.90 в matProps).
                    float albMul = 1.0;
                #ifdef RT_PBR
                    albMul = 1.0 - 0.6 * pbrMetal;
                #endif
                    col = albedo * (ambient + dirLight) * albMul + lit * albMul;
                    // Сам источник светится (пламя, лава, светокамень) — иначе факел выглядит
                    // «выключенным»: он освещает стены, но сам остаётся тускло-серым.
                    // ⚠️ ТОЛЬКО БЛОКИ (M8.64). emissiveAt красит светом пламени всё, что попало ВНУТРЬ
                    // КЛЕТКИ источника (|d| < 0.52). Сущность, задевшая телом клетку факела, попадала
                    // под эту проверку целиком — и житель/игрок заливался свечением «сплошняком», а
                    // стоило отойти на полблока, как свечение пропадало. Моб не светится оттого, что
                    // стоит рядом с факелом: свет он получает блок-светом, как и все.
                    if (!isEnt) {   // M8.141: крупные блоки — эмиссия из карты (distance-independent),
                        bool fromMat; vec3 se = selfEmission(hitUV, albedo, fromMat);   // спец-режимы — буфер
                        vec3 seAdd = fromMat ? se : emissiveAt(hp, albedo, hitUV);
                    #ifdef RT_PBR
                        // ЭМИССИЯ ПАКА (альфа _s) ЗАМЕНЯЕТ нашу, а не добавляется: сложи мы оба —
                        // светокамень и лава горели бы вдвое (таблица даёт свой цвет, пак свой), и это
                        // читалось бы как засвет. Цвет свечения берём у самой текстуры — так велит labPBR.
                        if (pbrEmis > 0.0) seAdd = albedo * pbrEmis * EMIS_BRIGHT;
                    #endif
                        col += seAdd;
                    }
                    // ⚠️ СВЕТЯЩИЙСЯ СЛОЙ СУЩНОСТИ (M8.66): глаза паука/эндермена (ванильный рендер-тайп
                    // "eyes") и эмиссивные слои ETF. Ваниль рисует их с ПОЛНОЙ яркостью, минуя
                    // освещение; у меня они приезжали обычной геометрией и шейдились обычным светом,
                    // а ночью обычный свет ≈ 0 — глаза еле тлели. Такой слой светит СВОИМ цветом,
                    // не спрашивая ни солнца, ни факелов. Слой и так поднят наружу механизмом слоёв,
                    // а прозрачные его места отсекает альфа-тест.
                    if (isEmis) col = albedo * EMISSIVE_GAIN;
                    // СВЕТЯЩИЙСЯ СПРУТ: он форсит себе block-свет 15 даже в темноте. Самосвечение =
                    // (свет вершины − свет окружения): у спрута в пещере ≈ полное свечение ЕГО ЦВЕТОМ
                    // (albedo — бирюзовая текстура), у обычных мобов ≈ 0 (их не трогаем). smoothstep
                    // требует near-max, чтобы моб у светокамня не самосветился.
                    // === RAIN PUDDLES: лужи на плоских гранях под ОТКРЫТЫМ небом (M8.22) ===
                    // Держатся и высыхают по «влажности» (2-мин хвост из Java). Лужа = зеркальце:
                    // где скапливается вода (шум по мировым XZ) -> плоская нормаль + рябь капель +
                    // Френель + отражение неба/сцены + мокрое затемнение поверхности.
                    if (!isEnt && wetnessF() > 0.01 && nrm.y > 0.6) {
                        float wness   = wetnessF();
                        float skyOpen = clamp(skyL * 1.6 - 0.4, 0.0, 1.0);        // только под небом
                        float pn      = texture(noisetex, hp.xz * 0.03).r;         // где скапливается вода
                        // Порог опускается с влажностью: сперва лужи ТОЛЬКО в самых низких местах
                        // (pn>0.88), к концу дождя — до ~45% площади (pn>0.55). Не заливает всё.
                        float thr     = mix(0.88, 0.55, wness);
                        float puddle  = smoothstep(thr, thr + 0.12, pn)
                                      * skyOpen * smoothstep(0.6, 0.82, nrm.y);
                        if (puddle > 0.01) {
                            vec3 pn3 = vec3(0.0, 1.0, 0.0);
                            if (rainF() > 0.01) {
                                vec2 rp = rainRipple(hp.xz, params.y);
                                pn3 = normalize(pn3 + vec3(rp.x, 0.0, rp.y) * rainF() * 1.2);
                            }
                            vec3 R = reflect(dir, pn3); if (R.y < 0.03) R.y = 0.03;
                            // ⚠️ ПЕРФ: лужа отражает НЕБО аналитически (skyRefl), без вторичного луча.
                            // traceShade на каждый мокрый пиксель × 2-мин хвост влажности = обвал FPS.
                            vec3 refl = skyRefl(R) * smoothstep(0.0, 0.5, skyL);
                            float ndvp = max(dot(-dir, pn3), 0.0);
                            float Fp   = 0.02 + 0.98 * pow(1.0 - ndvp, 5.0);
                            col = mix(col * 0.55, refl, Fp * puddle);              // мокро темнее + блеск
                        }
                    }

                    // === ОТРАЖЕНИЯ БЛОКОВ (M8.24): металл/самоцвет/обсидиан «как вода» ===
                    // Материал опознан картой (matId). Отражающие блоки локальны (как вода) -> луч
                    // traceShade им по карману; гейт по дистанции держит стоимость в узде.
                    // 1..9 — металлы/самоцветы/обсидиан; 16..21 — вторая волна (полировка, кварц,
                    // глазурь, руды, аметист, призмарин). 10..15 — прозрачные, 23/24 — эмиссия
                    // редстоуна: у них свои ветки, лишний луч отражения им не нужен.
                    bool glossy = !isEnt && t < 128.0
                            && ((matId > 0u && matId < 10u) || (matId >= 16u && matId <= 21u));
                #ifdef RT_PBR
                    // ПАК РАСШИРЯЕТ СПИСОК БЛЕСТЯЩИХ: у него блестит не «материал из таблицы», а любой
                    // тексель со своей шероховатостью — глазурь, мокрый камень, полированный мод-блок.
                    // Порог гладкости и гейт по дистанции держат число лучей в узде (как и было).
                    glossy = glossy || (!isEnt && t < 128.0 && pbrStr > 0.45);
                #endif
                    if (glossy) {
                        vec3 mtint; float mf0; float mstr;
                        matProps(matId, mtint, mf0, mstr);
                    #ifdef RT_PBR
                        // ПАК СИЛЬНЕЕ ТАБЛИЦЫ — но только В СТОРОНУ БЛЕСКА (max, не подмена): иначе
                        // блок, которому таблица уже дала отражение, потерял бы его там, где пак
                        // оставил тексель матовым.
                        if (!isEnt && pbrStr > mstr) { mstr = pbrStr; mtint = pbrTint; }
                        mf0 = max(mf0, pbrF0);
                    #endif
                        vec3 R = reflect(dir, nrm);
                        vec3 refl = traceShade(hp + nrm * 0.02, R, smoothstep(0.0, 0.5, skyL));
                    #ifdef RT_PBR
                        // ГЛАДКОСТЬ РЕШАЕТ, ЧТО ЛОВИТ ЛУЧ. Острый зеркальный луч описывает только
                        // гладкую поверхность; шершавую он не описывает вовсе — точечная выборка среды
                        // вместо её среднего. Для таких подмешиваем аналитическое НЕБО (низкочастотная
                        // часть отражения) и гасим зеркальную составляющую: дешевле, чем блюрить трассу,
                        // и честнее, чем показывать у матового блока резкое зеркало.
                        if (!isEnt && pbrStr > 0.0) {
                            float sharp = clamp(1.0 - pbrRough * 1.6, 0.0, 1.0);
                            refl = mix(skyRefl(R), refl, sharp);
                        }
                    #endif
                        float ndv = max(dot(-dir, nrm), 0.0);
                        float F = mf0 + (1.0 - mf0) * pow(1.0 - ndv, 5.0);   // Schlick (у металлов F0 высокий)
                        // ТЕКСТУРА ЧИТАЕТСЯ В САМОМ ЗЕРКАЛЕ: отражение красит ТЕКСЕЛЬ (так делают
                        // шейдерпаки — у металла F0 = цвет поверхности). Тёмные швы между слитками
                        // отражают темнее -> рисунок блока виден даже там, где зеркало сильное.
                        // Без этого блок золота выглядел зеркальной плитой без текстуры.
                        float texL   = dot(albedo, vec3(0.2126, 0.7152, 0.0722));   // яркость текселя (линейная)
                        float texMod = clamp(texL * 2.0 + 0.25, 0.30, 1.25);
                        col = mix(col, refl * mtint * texMod, clamp(F * mstr, 0.0, 1.0));
                    }

                    // === ПЛОТНЫЙ ЛЁД (14): в жизни он ПОЧТИ ПРОЗРАЧНЫЙ и преломляет, отражая
                    // совсем немного. ⚠️ Геометрия у него SOLID (НЕпрозрачная) — луч бы в неё упёрся.
                    // Поэтому смотрим сквозь сами: луч пропускания с отсечением ЗАДНИХ граней
                    // (выходные грани льда для него back-facing -> пропускаются). Работает и для
                    // стены в несколько блоков: внутренних граней там нет, а дальняя — тоже задняя.
                    if (matId == 14u && !isEnt) {
                        const float PI_IOR = 1.31;
                        vec3 pbend = refract(dir, nrm, 1.0 / PI_IOR);
                        vec3 ptd = (dot(pbend, pbend) < 1e-6) ? dir : normalize(mix(dir, pbend, 0.10));
                        // ⚠️ РАНЬШЕ отсекал ВСЕ задние грани — и луч улетал сквозь всю землю
                        // (грань камня ПОД льдом мешер срезает: лёд для него непрозрачный куб),
                        // отсюда была ДЫРА. Теперь пропускаем ТОЛЬКО грани самого льда — по материалу.
                        // ⚠️ КЛЮЧЕВОЕ: нижняя грань льда и ВЕРХНЯЯ ГРАНЬ КАМНЯ ПОД НИМ — в ОДНОЙ
                        // плоскости (одно и то же t). Сдвиг «за грань льда» проскакивал заодно и
                        // грань камня -> дыра ровно под низом. Теперь, когда грань камня существует
                        // (мишин BlockFaceCullM), отсекаем ЗАДНИЕ грани: грань льда изнутри —
                        // back-facing (отсечётся), грань камня — front-facing (попадёт).
                        // ⚠️ ЭПСИЛОН И РЕБРО БЛОКА (источник «супертонкой каёмки»): сдвиг старта
                        // ВДОЛЬ ЛУЧА (hp + ptd*eps) у самой кромки грани выносит точку СНАРУЖИ блока
                        // (луч там выходит через БОКОВУЮ грань почти сразу) -> дальше всё back-facing
                        // -> промах -> небо -> светящаяся щель по контуру. Сдвиг вдоль -НОРМАЛИ
                        // геометрически безопасен: он двигает точку строго ВГЛУБЬ льда и НИКОГДА не
                        // пересекает боковые плоскости блока, где бы на грани мы ни были.
                        vec3 pro = hp - nrm * 0.001;
                        vec3 pthru = skyBase(ptd);
                        bool phit = false;
                        for (int pk = 0; pk < 4; pk++) {
                            rayQueryEXT ir;
                            rayQueryInitializeEXT(ir, tlas, gl_RayFlagsCullBackFacingTrianglesEXT,
                                                  0xFF, pro, 0.001, ptd, 4096.0);
                            walkRay(ir, 7u);
                            if (rayQueryGetIntersectionTypeEXT(ir, true) != gl_RayQueryCommittedIntersectionTriangleEXT) break;
                            // ⚠️ СТРОГО == 14u: раньше было >= 14u, и с приходом материалов 16+
                            // (полировка/кварц/руды) луч стал бы пролетать и сквозь них.
                            if (hitMaterial(ir) == 14u) {           // это грань самого плотного льда -> сквозь неё
                                pro += ptd * (rayQueryGetIntersectionTEXT(ir, true) + 0.002);
                                continue;
                            }
                            pthru = shadeSimple(ir, pro, ptd);      // настоящая геометрия за льдом
                            phit = true;
                            break;
                        }
                        // ЗАПАСНОЙ ПУТЬ: луч ушёл ВНИЗ и не встретил ничего — он проскочил сквозь
                        // полую землю. Неба там нет: показываем СВОЮ поверхность непрозрачно.
                        // Дыры/просвета не будет НИКОГДА, даже если эпсилон где-то ещё подведёт.
                        if (!phit && ptd.y < 0.0) pthru = col;
                        pthru *= mix(vec3(1.0), albedo, 0.22);          // тинт: узор льда проступает и в пропускании
                        float pndv = max(dot(-dir, nrm), 0.0);
                        float pF = 0.03 + 0.97 * pow(1.0 - pndv, 5.0);  // отражает СОВСЕМ немного в лоб
                        vec3 prefl = traceShade(hp + nrm * 0.02, reflect(dir, nrm), smoothstep(0.0, 0.5, skyL));
                        // M8.144s: ПЛОТНЫЙ ЛЁД (packed ice) в ванили НЕПРОЗРАЧНЫЙ. Прежнее преломление
                        // сквозь него давало тёмные глюки на айсбергах (просьба убрать). Теперь как
                        // синий лёд: опаковый, лёгкий холодный оттенок + френелевский блеск. pthru
                        // больше не используется -> цикл преломления выше = мёртвый код (выкидывается).
                        col = mix(col * vec3(0.92, 0.97, 1.06), prefl, pF * 0.5);
                    }
                    // === МОЛНИЯ (M8.46): чистая плазма — ярче всего в кадре, текстуры у неё нет ===
                    if (isBolt) {
                        // Цвет вершины ваниль уже задала (белый с голубизной по краям); в HDR поднимаем
                        // так, чтобы столб выбивался даже на дневном небе — это и есть молния.
                        col = albedo * 60.0;
                    }

                    // === РЕДСТОУН ПОД СИГНАЛОМ (M8.26) ===
                    // 23 — ПЫЛЬ. Силу сигнала (0..15) знать неоткуда: геометрия чанка не несёт
                    // состояние блока. Но ваниль ТИНТУЕТ пыль по силе — от тёмно-бордового (0) к
                    // алому (15), — и этот тинт лежит в ЦВЕТЕ ВЕРШИНЫ, который мы и так читаем.
                    // Значит яркость свечения = яркость тинта: сигнал сильнее -> светит ярче. Даром.
                    else if (matId == 23u && !isEnt) {
                        float sig = smoothstep(0.32, 1.0, vtint.r);          // 0 сигнала ~0.30, 15 -> 1.0
                        col += albedo * sig * sig * 2.6;                     // квадрат: слабый сигнал едва тлеет
                    }
                    // 24 — ГОРЯЩИЕ ЛАМПОЧКИ (рельсы/повторитель/компаратор/наблюдатель/факел/лампа).
                    // У «включённых» блоков ОТДЕЛЬНЫЕ текстуры, поэтому состояние тоже не нужно, а
                    // маска RED в карте материалов пометила ТОЛЬКО красные лампочки — не весь блок.
                    else if (matId == 24u && !isEnt) {
                        col += albedo * 2.4;
                    }
                    // === СИНИЙ ЛЁД (15): оставляем плотным, просто СИНЕЕ оттенок ===
                    else if (matId == 15u && !isEnt) {
                        // СИНИЙ ЛЁД: как ОБЫЧНЫЙ лёд, только синий и НЕПРОЗРАЧНЫЙ (просьба).
                        // Мягкий синий сдвиг БЕЗ затемнения (прошлый тинт 0.66/0.86 гасил блок в темень)
                        // + тот же френелевский блеск, что у обычного льда.
                        col *= vec3(0.84, 0.94, 1.18);
                        float bndv = max(dot(-dir, nrm), 0.0);
                        float bF = 0.05 + 0.95 * pow(1.0 - bndv, 5.0);
                        vec3 brefl = traceShade(hp + nrm * 0.02, reflect(dir, nrm), smoothstep(0.0, 0.5, skyL));
                        col = mix(col, brefl, bF * 0.5);
                    }

                    if (isEnt) {
                        float ebl = w0*entBlockLight(vb,tv.x) + w1*entBlockLight(vb,tv.y) + w2*entBlockLight(vb,tv.z);
                        float selfEmit = smoothstep(0.85, 1.0, ebl) * max(ebl - max(blockL, skyL), 0.0);
                        col += albedo * selfEmit * 1.6;
                        // ГОРЯЩИЙ МОБ: рядом с его огнём (MODE_POINT) тело ГОРИТ — яркая оранжевая
                        // эмиссия + мерцание. burn^2 концентрирует жар к центру огня (не плоский фолл).
                        float burn = 0.0;
                        for (int bi = 0; bi < int(wparams.w); bi++){
                            if (abs(lights[bi].col.w - 4.0) > 0.5) continue;
                            burn = max(burn, clamp(1.0 - distance(hp, lights[bi].posRange.xyz)/2.2, 0.0, 1.0));
                        }
                        if (burn > 0.01) {
                            float bfl = 0.6 + 0.4*sin(params.y*1.2 + dot(floor(hp*4.0), vec3(1.7,2.3,3.1)));
                            col += albedo * vec3(1.8, 0.70, 0.14) * (burn*burn) * bfl;   // яркое горение
                        }
                        // ПОКРАСНЕНИЕ ПРИ УРОНЕ / белая вспышка — поверх освещения (как в ваниле)
                        col = applyEntityOverlay(col, vb, tv);
                    }
                    // === НАШ ОТТЕНОК КАПЕЛЬ вместо ярко-синей ванильной текстуры (M8.23a) ===
                    // Капли шейдились как ванильные (насыщенно-синие, ярко) — оттенок не наш и слишком
                    // светлый. Красим НАШИМ приглушённым сине-серым; яркость СЛЕДУЕТ ЗА НЕБОМ (серо
                    // днём в дождь, темно ночью) и за видимостью неба. Форму/падение оставляем ванильными.
                    if (isWeather) {
                        vec3 skyC = skyBase(vec3(0.0, 1.0, 0.0));
                        float skyLum = dot(skyC, vec3(0.3, 0.5, 0.2));
                        vec3 tint = mix(vec3(0.50, 0.57, 0.66), skyC, 0.45);
                        col = tint * (0.10 + 0.85 * skyLum) * (0.35 + 0.65 * skyVis);
                    }
                    // Дымка только СИЛЬНО вдали (старт ~128 блоков, мягкий набор)
                    // M8.147: ЦВЕТ дымки следует за доступом к НЕБУ. Под землёй (skyL=0) даль обязана
                    // тонуть в ТЕМНОТЕ, а раньше подмешивалось яркое skyBase — и пещерная даль
                    // СВЕТЛЕЛА (репорт: «туман в пещерах светлый, должен быть тёмным»).
                    float fog = fogAmt(t);
                    vec3 fogCol = mix(MIN_LIGHT * 2.0, skyBase(dir), smoothstep(0.0, 0.35, skyL));
                    col = mix(col, fogCol, fog);
                    }
                } else {
                    col     = skyBase(dir) + stars(dir);   // фон гаснет за тучами линейно
                    bodies  = skyBodies(dir);              // а диски — в кубе (см. ниже)
                }

                // === M8.144: ЗВЁЗДНАЯ ГЛАДЬ ПОРТАЛА В ЭНД (аналитическая плоскость 3x3) ===
                // rtCfg6.w = Y глади (sentinel < -1e8 = портала рядом нет); rtCfg5.zw = центр XZ.
                // Пересекаем луч с плоскостью; если гладь БЛИЖЕ блока (p1T) и попали в 3x3 — рисуем
                // звёзды. Рамки/стены спереди перекрывают честно (их p1T меньше). Блок end_portal
                // невидим для BLAS, поэтому луч проходит сквозь — здесь и «ловим» гладь.
                {
                    float epY = cam.rtCfg6.w;
                    if (epY > -1e8 && abs(dir.y) > 0.0005) {
                        float tEP = (epY - origin.y) / dir.y;
                        if (tEP > 0.05 && tEP < p1T) {
                            vec3 epP = origin.xyz + dir * tEP;
                            if (abs(epP.x - cam.rtCfg5.z) < 1.5 && abs(epP.z - cam.rtCfg5.w) < 1.5) {
                                col    = mix(endPortalStars(epP, dir), skyBase(dir), fogAmt(tEP));
                                bodies = vec3(0.0);
                            }
                        }
                    }
                }

                // === ПОДВОДНАЯ ГЛАДЬ = АНАЛИТИЧЕСКАЯ ПЛОСКОСТЬ (не геометрия!) ===
                // Первичный луч уже прошёл сквозь воду и знает дистанцию до БЛОКА (p1T).
                // Пересечение с плоскостью воды Y=gCamSurfaceY считаем МАТЕМАТИЧЕСКИ и честно
                // сравниваем: блок ближе -> виден блок; гладь ближе -> окно/зеркало.
                // Продырявить блок невозможно в принципе. Ни слоёв, ни кромок, ни граней.
                float tSurf = 1e30;
                bool  hitSurf = false;
                if (params.x > 0.5 && wparams.x > -1e8 && dir.y > 0.001) {
                    float ts = (wparams.x - origin.y) / dir.y;   // уровень глади из Java (скан колонки воды)
                    if (ts > 0.05 && ts < p1T) { tSurf = ts; hitSurf = true; }
                }
                if (hitSurf) {
                    vec3 sp = origin.xyz + dir * tSurf;                       // точка на глади
                    vec3 wnw = waterNormal(sp.xz, params.y * 0.05, origin.xz, tSurf);
                    // Мягкая рябь: полная у критического угла хаотично перекидывает окно<->зеркало.
                    vec3 wnU = normalize(mix(vec3(0.0, 1.0, 0.0), wnw, 0.25));
                    if (dot(wnU, dir) > 0.0) wnU = -wnU;                      // нормаль к камере (вниз)
                    vec3 mirrorCol = traceShade(sp + wnU*0.02, reflect(dir, wnU), 1.0);   // ЗЕРКАЛО: подводная сцена
                    vec3 tup = refract(dir, wnU, 1.33);                       // вода->воздух
                    if (dot(tup, tup) < 1e-6) {
                        col = mirrorCol;                                      // за критическим углом -> зеркало (TIR)
                    } else {
                        vec3 windowCol;
                        rayQueryEXT ur;
                        rayQueryInitializeEXT(ur, tlas, gl_RayFlagsNoneEXT, 0xFF, sp - wnU*0.02, 0.02, tup, 4096.0);
                        walkRay(ur, 7u);                                      // вверх в воздух (тело игрока скрыто)
                        if (rayQueryGetIntersectionTypeEXT(ur, true) == gl_RayQueryCommittedIntersectionTriangleEXT)
                            windowCol = shadeSimple(ur, sp - wnU*0.02, tup);  // берег/деревья над водой
                        else
                            windowCol = sky(tup) * 0.8;                       // небо в ОКНЕ Снеллиуса
                        float cosI = clamp(dot(-dir, wnU), 0.0, 1.0);
                        float cosT = sqrt(max(1.0 - 1.33*1.33*(1.0 - cosI*cosI), 0.0));
                        float Rs = (1.33*cosI - cosT) / (1.33*cosI + cosT); Rs *= Rs;
                        float Rp = (1.33*cosT - cosI) / (1.33*cosT + cosI); Rp *= Rp;
                        float Fr = clamp(0.5*(Rs + Rp), 0.0, 1.0);
                        // M8.111 «мир извне просвечивает пятном» (скрин): у КРАЯ окна Снеллиуса
                        // преломлённые лучи выходят в воздух почти горизонтально (cosT -> 0) и
                        // приносят закат/солнце/силуэты с поверхности ШИРОКОЙ яркой каймой:
                        // Френель гасит её медленно, а небо в HDR — даже 10% пропускания видно.
                        // (Прошлые фиксы M8.107/109 чинили ЗЕРКАЛО — не ту ветку, потому «не
                        // помогала».) Гасим окно у края по углу выхода; над головой (cosT ~ 1)
                        // окно нетронуто — берег/деревья/небо сквозь гладь видны как раньше.
                        windowCol *= smoothstep(0.04, 0.34, cosT);
                        col = mix(windowCol, mirrorCol, Fr);                  // окно <-> зеркало по Френелю
                    }
                }

                // Под водой: тонируем ВЕСЬ кадр водным цветом + короткая видимость
                // (поглощение Eclipse + рассеяние в сине-зелёный). Иначе «будто воды нет».
                if (params.x > 0.5) {
                    // столб воды = до глади (если она ближе) либо до блока
                    // M8.112 «мир просвечивает на глубине» (скрин): луч, ушедший вдаль МИМО
                    // всей геометрии, рисовал НЕБО (закат/солнце), а туман для промахов был
                    // зашит 24 блоками — HDR-небо просвечивало на 20-34% (зел/син каналы).
                    // Дальние берега при этом туманились честно (по своей дистанции) и давали
                    // «силуэты мира» на светящемся фоне. Промах в толще = бесконечный столб:
                    // чистая асимптота рассеяния, никакого неба.
                    float dw = hitSurf ? tSurf
                             : ((rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT)
                                ? rayQueryGetIntersectionTEXT(rq, true) : 1.0e4);
                    vec3 wA = vec3(0.335, 0.106, 0.069) * 0.65;   // поглощение по глубине
                    vec3 wS = vec3(0.030, 0.058, 0.105) * 1.5 * waterDayLit();   // подводный синий, ночью гаснет
                    vec3 tr = exp(-wA * dw);
                    col = col * tr + wS * (1.0 - tr);

                    // ГЛУБИНА КАМЕРЫ под гладью = (Y глади − Y камеры). Берём УРОВЕНЬ ГЛАДИ из Java
                    // (wparams.x, скан колонки воды с кэшем под навесом) — НЕ лучом вверх!
                    // Луч упирался в блок над головой -> «глубина 0» -> экран светлел под навесом.
                    float camDepth = 20.0;                     // фолбэк, если уровень глади неизвестен
                    if (wparams.x > -1e8) camDepth = clamp(wparams.x - origin.y, 0.0, 40.0);
                    float depthLit = exp(-camDepth * 0.085);   // 1 у поверхности -> 0 в глубине
                    col *= mix(0.13, 1.0, depthLit);            // СЦЕНА темнеет с глубиной

                #ifdef RT_GODRAYS
                    // GOD-RAYS: лучи солнца в толще. Марш камера->точка, теневой луч к солнцу в
                    // каждом шаге; освещённые участки дают сине-белый in-scatter. Лучи гаснут с
                    // глубиной МЕДЛЕННЕЕ сцены -> на тёмном фоне они заметнее и красивее.
                    float dwm = min(dw, 40.0);
                    uint gseed = (pix.y * width + pix.x) * 747u + 3u + frameSeed();
                    float jit = fract(float(gseed) * 0.00013);
                    float litf = 0.0;
                    for (int i = 0; i < 8; i++) {
                        float fr = (float(i) + jit) / 8.0;
                        vec3 sp = origin.xyz + dir * (fr * dwm);
                        if (!inShadow(sp + SUN_DIR_f()*0.05, SUN_DIR_f(), false)) litf += 1.0;
                    }
                    litf /= 8.0;
                    float toSun = max(dot(dir, SUN_DIR_f()), 0.0);
                    float shaftFade = mix(0.40, 1.0, depthLit);   // лучи гаснут мягче сцены
                    // M8.118: ИСХОДНЫЙ уровень годреев (0.10 + 0.65*toSun^4, /40 — как было до
                    // всех сегодняшних правок; перепробовали 0.35/0.22/0.13 — исходный лучший).
                    // Днём dayAmt=1 -> ровно исходная формула, гейт туч убран (его и не было).
                    // ЛУННЫЕ лучи — свой уровень: половина дневного (константа для подстройки).
                    const float MOON_SHAFTS = 0.50;
                    float shaftGate = max(dayAmt(), MOON_SHAFTS * moonUp());
                    col += vec3(0.30, 0.56, 1.00) * litf * (0.10 + 0.65*pow(toSun, 4.0))
                           * (dwm/40.0) * shaftFade * shaftGate;
                #endif   // RT_GODRAYS: подводные лучи (затемнение сцены по глубине ВЫШЕ — оно остаётся)
                }

                // M8.8 ПОГРУЖЕНИЕ В ЛАВУ: тёплый плотный туман-свечение. Внутри лавы почти ничего
                // не видно — горячая красно-оранжевая мгла, спадает за ~1-2 блока. Иначе камера
                // «внутри» непрозрачной лавы видит её нутро = мусор («лаги под лавой»). Ставим ДО
                // руки, чтобы рука рисовалась поверх мглы.
                if (handCol.w > 0.5) {
                    float dl  = min(p1T, 8.0);
                    float fog = 1.0 - exp(-dl * 2.2);             // менее плотный (было 3.0)
                    // Цвет — ЯРКО-ОРАНЖЕВЫЙ, близко к самой текстуре лавы (не красный). База 0.62:
                    // достаточно, чтобы у стыков не проступало нутро, но не «стена» как при 0.78.
                    col = mix(col, vec3(1.60, 0.82, 0.22), 0.62 + 0.30*fog);
                    col += vec3(0.55, 0.26, 0.05);               // тёплый оранжевый подсвет у глаз
                }

                // M8.9 ВАНИЛЬНАЯ ОБВОДКА блока под прицелом. RT-кадр перезаписывает ванильный, где
                // ваниль её рисовала, — поэтому рисуем сами. Аналитически: пересекаем луч с AABB
                // формы блока (из Java), берём ТОЧКУ ВХОДА (передняя грань) и красим там, где она
                // близка сразу к ДВУМ границам коробки => это ребро. Так работает и для прозрачных/
                // тонких блоков (луч сквозь них проходит), и корректно перекрывается геометрией
                // впереди (tNear > p1T -> пропускаем).
                // M8.10 СЛЕПОТА / ТЬМА СТРАЖА — это ТУМАН В МИРЕ, а не экранный тинт: обзор
                // схлопывается в черноту (радиус падает с ~64 до ~2 блоков). Тьма Стража ПУЛЬСИРУЕТ.
                // Ставим ДО обводки и руки — они остаются видны, как в ваниле.
                {
                    float darkPulse = fx2.y * (0.55 + 0.45 * sin(params.y * 0.35));
                    float blind = clamp(fx1.x + darkPulse, 0.0, 1.0);
                    if (blind > 0.001) {
                        float dv  = min(p1T, 128.0);
                        float rad = mix(64.0, 2.0, blind);         // радиус обзора
                        float f   = 1.0 - exp(-dv / max(rad, 0.5) * 2.2);
                        col = mix(col, vec3(0.0), clamp(f, 0.0, 1.0) * blind);
                    }
                }
                // НОЧНОЕ ЗРЕНИЕ: вытягиваем тени (умножение + подъём чёрного), тонмаппер держит верх
                if (fx1.y > 0.001) col = mix(col, col * 3.2 + vec3(0.05), fx1.y);

                // === M8.20 ОБЛАКА: марш ОТ КАМЕРЫ ДО ГЕОМЕТРИИ ===
                // Так облако ПЕРЕД постройкой видно, а часть, ушедшая ЗА неё, срезается. Это же
                // даёт правильный полёт СКВОЗЬ облака (марш стартует прямо от камеры).
                // Под водой не считаем — сквозь толщу их не видно.
                if (params.x < 0.5 && dimType() == 0) {   // M8.131: облака только в верхнем мире
                    vec4 cl = marchClouds(origin.xyz, dir, p1T, int(cam.rtCfg4.w));
                    float trans = max(1.0 - cl.a, 0.0);
                    // ⚠️ Фон — линейно (trans), СВЕТИЛА — в кубе (trans^3). Диск луны ярче ночного неба
                    // в сотни раз, и даже 1% пропускания давал отчётливый диск с кратерами сквозь тучи.
                    col = col * trans + bodies * trans * trans * trans + cl.rgb;
                } else if (params.x < 0.5) {
                    // M8.131c/d: Энд/Ад — облаков нет, но СВЕТИЛО рисовать надо. Тёмное ТЕЛО
                    // затменного диска ПЕРЕКРЫВАЕТ небо и звёзды (mix, не сложение — иначе диск
                    // цвета неба, репорт), затем сверху аддитивно корона+ореол из bodies.
                    if (dimType() == 2) {
                        float rr = endOrbRad(dir);
                        col = mix(col, vec3(0.006, 0.004, 0.013), smoothstep(1.00, 0.92, rr));
                    }
                    col += bodies;
                } else {
                    // M8.112: светила под водой НЕ добавляем — «col += bodies» рисовал диск
                    // солнца/луны сквозь СОТНИ блоков толщи без ослабления. В окно Снеллиуса
                    // они уже приходят честно (windowCol = sky(tup) в ветке глади).
                }

                // === M8.134c ОБЪЁМНЫЕ ЛУЧИ ВЕРХНЕГО МИРА (god rays сквозь листву) ===
                // Марш камера->точка первичного луча; в каждом шаге теневой луч к солнцу. Где
                // воздух освещён — in-scatter (косые лучи в прорехах листвы/окон), где тень —
                // темно, отсюда контраст «шахт». Своя реализация (стандартный объёмный свет).
                // ⚠️ ПЫЛИНКИ УБРАНЫ (M8.134c, репорт): высыпались яркими крапинами по террейну
                // вместо мягкой парящей пыли. Вернуть — отдельной вехой с честным объёмом.
            // M8.155: ЛУЧИ — НАСТРОЙКА, ВЫРЕЗАЮЩАЯ КОД. Марш с теневым лучом НА КАЖДЫЙ ШАГ
            // (до 16 лучей на пиксель) — один из самых дорогих кусков шейдера. Условие `if(выкл)`
            // тут не сэкономило бы ничего: видеокарта выделяет регистры под самый тяжёлый путь
            // исполнения, и код продолжал бы их занимать, даже не работая (см. облака, M8.144m).
            // Поэтому именно #if, а не рантайм-проверка.
            #if defined(RT_SHADOWS) && defined(RT_GODRAYS)
                // ⚠️ ОПТИМИЗАЦИЯ (M8.144g): в ДОЖДЬ солнце за тучами — god rays НЕ видны, но 16
                // теневых лучей/пиксель × геометрия дождя убивали FPS днём (ночью god rays и так
                // выкл -> «ночью лучше»). Гейтим ВЕСЬ цикл по rainF -> в дождь марш не считается.
                // M8.144q: число шагов god rays ТАЕТ с дождём (плавно) — раньше жёсткий порог
                // rainF<0.5 включал 16 лучей/пиксель в ОДНОМ кадре при выходе из дождя = рывок.
                // Теперь стоимость меняется постепенно (нет снапа при кончающемся дожде).
                int gsN = int(round(16.0 * (1.0 - smoothstep(0.12, 0.50, rainF()))));
                if (params.x < 0.5 && dimType() == 0 && sunH() > 0.03 && gsN >= 2) {
                    float gm = min(p1T, 42.0);
                    // ⚠️ ДИЗЕРИНГ (M8.137b): стабильный IGN убрал «метель», но обнажил ПОЛОСЫ
                    // марша (лесенка — репорт). Баланс: база IGN (пер-пиксельная, стабильная) +
                    // МАЛЫЙ пер-кадровый поворот фазы на 1/8 шага. DLSS усредняет 8 фаз ->
                    // полосы сглаживаются в непрерывный градиент, а сдвиг мал -> без шума.
                    // Плюс 16 шагов вместо 12 — сами полосы мельче.
                    float ign = fract(52.9829189 * fract(dot(vec2(pix), vec2(0.06711056, 0.00583715))));
                    float gj  = fract(ign + float(frameSeed() & 7u) * 0.125);
                    float litf = 0.0;
                    int GS = gsN;                 // тает с дождём (плавный переход, без снапа)
                    for (int i = 0; i < GS; i++) {
                        float fr = (float(i) + gj) / float(GS);
                        vec3 sp = origin.xyz + dir * (fr * gm);
                        if (!inShadow(sp + SUN_DIR_f() * 0.05, SUN_DIR_f(), false)) litf += 1.0;
                    }
                    litf /= float(GS);
                    // ПРОРЕЗЬ шахт: контраст между освещённым и затенённым воздухом. В лесу свет
                    // пробивается пятнами -> litf сильно варьируется -> шахты видны и СБОКУ, не
                    // только к солнцу. Пик к солнцу мягче (pow 3, не 6) + база выше -> заметнее.
                    float toSun = max(dot(dir, SUN_DIR_f()), 0.0);
                    float shaft = (0.10 + 0.5 * pow(toSun, 3.0)) * litf * (gm / 42.0) * dayAmt()
                                  * (1.0 - smoothstep(0.15, 0.5, rainF()));   // плавно гаснут к дождю
                    col += SUN_COL_f() * 0.16 * shaft;
                }
            #endif


                // ОСАДКИ (дождь/снег) больше НЕ рисуем экранным оверлеем: геометрия погоды
                // захватывается из ваниллы (RenderTypeM -> collectWeather) и трассируется как
                // партиклы — настоящий мировой дождь с окклюзией и отражениями (M8.21c).

                // Обводка на ПЕРВИЧНОМ луче. Если впереди вода — рамка тут отсекается (tNear > p1T),
                // и её рисует ПРЕЛОМЛЁННЫЙ луч в водной ветке (см. выше) — уже искажённой.
                if (outlineHit(origin.xyz, dir, p1T)) col = mix(col, vec3(0.0), 0.8);

                // === ОВЕРЛЕЙ РУКИ (проход 6): поверх мира, как ванильный вьюмодел ===
                // Луч руки с ФИКСИРОВАННЫМ FOV (params.zw) — рука не растягивается при большом
                // мировом FOV. Рисуется поверх => не клипается о землю и не тонет в воде.
                // cullMask 0x02 -> только инстанс сущностей (мир отсечён -> нет opaque-авто-
                // коммита + дешевле); candAlphaPass(6) оставит лишь руку.
                {
                    vec3 hdir = normalize(forward.xyz + (u*params.z)*right + (v*params.w)*up.xyz);
                    // M8.114/115: подводная рябь гнёт и луч руки, но фаза у руки — ЭКРАННАЯ
                    // (u,v), а не мировая: рука пришита к камере, и мировая фаза при повороте
                    // «прокатывалась» по ней — рука бешено искажалась (репорт). Теперь рука
                    // колышется только во времени, повороты камеры её не дёргают.
                    if (params.x > 0.5) {
                        float tmh = params.y;
                        hdir = normalize(hdir + right * (0.005*sin(tmh*0.17 + v*20.0))
                                              + up.xyz * (0.005*cos(tmh*0.14 + u*18.0)));
                    }
                    bool underW = params.x > 0.5;
                    // M8.121: рука ФИЗИЧЕСКИ выше на пол-блока (геометрия поднята при захвате,
                    // RtEntities.HAND_LIFT): вьюмодел лежал на высоте ~1 блока, и тени соседних
                    // блоков его накрывали («рука чернеет»). Луч оверлея стартует с той же
                    // добавкой — в кадре рука не сдвигается ни на пиксель, а тень и амбиент
                    // считаются на высоте ~1.5.
                    vec3 horig = origin.xyz + vec3(0.0, 0.5, 0.0);
                    // Прямая проба руки (нужна всегда).
                    rayQueryEXT hrq;
                    rayQueryInitializeEXT(hrq, tlas, gl_RayFlagsNoneEXT, 0x02u, horig, 0.02, hdir, 8.0);
                    walkRay(hrq, 6u);
                    bool hHit = rayQueryGetIntersectionTypeEXT(hrq, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
                    float handT = hHit ? rayQueryGetIntersectionTEXT(hrq, true) : 1e30;

                    // Пиксель смотрит на гладь (голова над водой), и рука здесь НЕ в воздухе
                    // перед прудом (handT>p1T) -> ПРЕЛОМЛЕНИЕ. Преломлённый луч пускаем даже
                    // если прямой руку не задел: тогда рука СМЕЩАЕТСЯ в новые пиксели (а не
                    // только уходит из старых) -> форма изгибается непрерывно, без «дыр».
                    bool refractHand = !underW && p1Water && !(hHit && handT <= p1T);
                    if (refractHand) {
                        // M8.121b: рука физически поднята на пол-блока (см. horig) — преломлённый
                        // луч стартуем с ТОЙ ЖЕ добавкой, иначе он ищет руку на старой глубине и
                        // мажет: рука переставала преломляться (репорт — «только при приседе»).
                        // Со сдвигом относительная геометрия луч↔рука бит-в-бит прежняя.
                        vec3 wsp  = origin.xyz + hdir * p1T + vec3(0.0, 0.5, 0.0);   // точка на поверхности воды (+подъём руки)
                        vec3 rdir = refract(hdir, vec3(0.0, 1.0, 0.0), 1.0/1.33);
                        if (dot(rdir, rdir) > 1e-6) {
                            rayQueryEXT rrq;
                            rayQueryInitializeEXT(rrq, tlas, gl_RayFlagsNoneEXT, 0x02u, wsp, 0.001, rdir, 8.0);
                            walkRay(rrq, 6u);
                            if (rayQueryGetIntersectionTypeEXT(rrq, true) == gl_RayQueryCommittedIntersectionTriangleEXT)
                                { vec4 hh = shadeHand(rrq, wsp, rdir, true, rayQueryGetIntersectionTEXT(rrq, true));
                                  col = mix(col, hh.rgb, hh.a); }
                        }
                    } else if (hHit) {
                        // Рука в воздухе перед прудом ИЛИ камера целиком под водой (тон) -> прямой луч.
                        { vec4 hh = shadeHand(hrq, horig, hdir, underW, underW ? 1.2 : 0.0);
                          col = mix(col, hh.rgb, hh.a); }
                    }
                }

                // === M8.136 ГРЕЙД ПО ИЗМЕРЕНИЮ (сплит-тон, линейный HDR перед DLSS/AgX) ===
                // Верхний мир был «плоским»: даём объём ЦВЕТА — тёплые света (солнце) и прохладные
                // тени (небесный отскок), плюс лёгкая насыщенность. Ад — тёплый «жаркий» тон.
                // Энд не трогаем (его вид уже держит сиреневая атмосфера). Свой грейд.
                if (params.x < 0.5) {
                    float gl = dot(col, vec3(0.2126, 0.7152, 0.0722));
                    if (dimType() == 0) {
                        vec3 shTint = vec3(0.95, 0.985, 1.07);      // прохладные тени
                        vec3 hiTint = vec3(1.055, 1.005, 0.935);   // тёплые света
                        col *= mix(shTint, hiTint, smoothstep(0.02, 0.55, gl));
                        col = mix(vec3(gl), col, 1.08);            // чуть насыщеннее (объём)
                    } else if (dimType() == 1) {
                        vec3 shTint = vec3(1.02, 0.92, 0.83);      // тёплые тени (жар снизу)
                        vec3 hiTint = vec3(1.09, 1.0, 0.88);       // тёплые света
                        col *= mix(shTint, hiTint, smoothstep(0.02, 0.5, gl));
                    }
                }

                // ⚠️ ЭКРАННЫЕ ЭФФЕКТЫ (огонь/яд/иссушение/урон), AgX и гамма ПЕРЕЕХАЛИ в RtPost —
                // они должны идти ПОСЛЕ DLSS. Здесь кадр остаётся ЛИНЕЙНЫМ HDR: это ровно то,
                // что ждёт на входе Ray Reconstruction.
                imageStore(outColor, ivec2(pix), vec4(max(col, vec3(0.0)), 1.0));

                // === GUIDE-БУФЕРЫ (M8.28) ===
                float near = cam.jitterNF.z, far = cam.jitterNF.w;
                bool  isSky = g_viewZ <= 0.0;
                float vz = isSky ? far : max(g_viewZ, near);
                // Глубина в том же виде, что у растеризатора: far*(vz-near)/((far-near)*vz).
                // Матрицу проекции с ТЕМИ ЖЕ near/far мы отдадим DLSS — значит всё согласовано.
                float depthNdc = isSky ? 1.0 : clamp(far * (vz - near) / ((far - near) * vz), 0.0, 1.0);
                imageStore(outDepth, ivec2(pix), vec4(depthNdc));

                // ВЕКТОР ДВИЖЕНИЯ: куда этот пиксель уехал бы, будь мы в ПРОШЛОМ кадре. Мир статичен,
                // движется камера — значит берём мировую точку и проецируем её ПРОШЛОЙ камерой.
                // (Сущности движутся сами; их прошлой позиции у нас нет, для них MV = только камера.)
                vec3 wpos = isSky ? (origin.xyz + dir * 1000.0) : (origin.xyz + dir * vz / max(dot(dir, forward.xyz), 1e-4));
                // Где ЭТО ЖЕ было кадр назад: у статичного мира — там же, у дождя — выше (текстура падает).
                wpos -= g_vel * cam.frameInfo.z;
                vec3 pd  = wpos - cam.pOrigin.xyz;
                float pvz = dot(pd, cam.pForward.xyz);
                vec2 mv = vec2(0.0);
                // ⚠️ РУКА — НУЛЕВОЙ вектор движения. Она прибита к камере: при повороте её пиксели
                // на экране стоят на месте. Репроекция её МИРОВОЙ точки прошлой камерой давала
                // огромный сдвиг («рука летит через мир»), DLSS верила и мешала руку с фоном —
                // именно поэтому шум и дрожание держались ровно на ней.
                if (pvz > 1e-3 && !g_isHand) {
                    vec3 pright = -cam.pLeft.xyz;
                    float pu = (dot(pd, pright)  / pvz) / cam.pLeft.w;    // [-1..1]
                    float pv = (dot(pd, cam.pUp.xyz) / pvz) / cam.pUp.w;
                    vec2 prevPix = vec2((pu * 0.5 + 0.5) * float(width),
                                        (0.5 - pv * 0.5) * float(height));
                    // ⚠️ ВЫЧИТАЕМ JITTER. Луч шёл через точку (pix + 0.5 + jitter), значит
                    // НЕсмещённая камера проецирует эту мировую точку именно туда — а не в центр
                    // пикселя. Раньше я вычитал центр, и в вектор движения каждый кадр подмешивался
                    // сам jitter (±0.5 px): DLSS честно дёргала кадр в такт дрожанию. Гайд требует
                    // MV БЕЗ jitter (иначе нужен флаг MVJittered).
                    mv = prevPix - (vec2(pix) + 0.5 + cam.jitterNF.xy);   // в ПИКСЕЛЯХ, в прошлый кадр
                }
                imageStore(outMotion, ivec2(pix), vec4(mv, 0.0, 0.0));

                imageStore(outNormal,  ivec2(pix), vec4(isSky ? -dir : g_normal, g_rough));
                // ⚠️ У НЕБА АЛЬБЕДО 0.5, А НЕ НОЛЬ. Гайд DLSS-RR прямо называет это типовой ошибкой
                // интеграции: «A common integration error is to leave sky pixels uncleared. A good
                // default albedo for sky is (0.5, 0.5, 0.5)». Сеть ДЕЛИТ цвет на альбедо (демодуляция),
                // и ноль делал небо численно неустойчивым — при движении камеры по облакам ползли
                // яркие мазки. Небо (и облака в нём) — это как раз пиксели без геометрии.
                imageStore(outDiffAlb, ivec2(pix), vec4(isSky ? vec3(0.5) : g_diff, 1.0));
                imageStore(outSpecAlb, ivec2(pix), vec4(isSky ? vec3(0.0) : g_spec, 1.0));
                imageStore(outReact,   ivec2(pix), vec4(g_react));
            }
            """);

    private static RtSnapshot INSTANCE;
    private static boolean failed = false;

    private static volatile double camOx, camOy, camOz;
    private static volatile float fX, fY, fZ, uX, uY, uZ, lX, lY, lZ;
    private static volatile float tanX = 0, tanY = 0;
    private static volatile float underwater = 0f, animTime = 0f;   // камера под водой (0/1) + время (тики) для ряби
    private static volatile float eyeInLava = 0f;                    // M8.8: камера ПОГРУЖЕНА В ЛАВУ (0/1)
    private static volatile float waterSurfaceY = -1.0e9f;           // Y глади над камерой (скан колонки воды в Java)
    private static volatile float playerBlockLight = 0f;              // M8.7: block-light в позиции игрока (0..1) — для руки
    private static volatile float heldLight = 0f;                     // M8.7: свет ПРЕДМЕТА В РУКЕ (0..1) — динамический источник
    private static volatile boolean camSet = false;
    private static volatile VulkanImage atlas;     // атлас блоков (перехвачен в renderSectionLayer)

    /** Захваченный атлас блоков: его РЕАЛЬНЫЙ размер нужен карте материалов (M8.103). */
    public static VulkanImage atlasImage() { return atlas; }

    private static long lastMs = 0;

    // ⚠️ ПОЗА ПОКАЧИВАНИЯ КАМЕРЫ (M8.76). Игра строит её в GameRenderer (bobView/bobHurt) и заливает
    // в свой буфер преобразований — а в отрисовку мира отдаёт матрицу БЕЗ неё. VulkanMod этим буфером
    // не пользуется, поэтому под ним не работает даже ВАНИЛЬНОЕ покачивание, а моды вроде Camera
    // Overhaul (они правят ту же позу) теряются вместе с ним. Забираем позу сами, прямо из игры.
    private static final org.joml.Matrix4f bobPose = new org.joml.Matrix4f();
    private static boolean bobTaken = false;

    /** Из RtWorld.tick (конец кадра): готовим следующий. Позу СБРАСЫВАЕМ В ЕДИНИЧНУЮ — иначе
     *  кадр, в котором игра покачивание не звала (оно выключено в настройках), унаследовал бы
     *  чужую позу от предыдущего. */
    public static void resetBobCapture() { bobTaken = false; bobPose.identity(); }

    /**
     * ⚠️ M8.157: БЕРЁМ ПОСЛЕДНЮЮ ПОЗУ ПЕРЕД РУКОЙ, а не первую в кадре.
     * Раньше брали первую, и это теряло часть покачивания: игра правит ОДНУ И ТУ ЖЕ позу
     * несколькими методами по очереди (bobHurt, bobView), а моды вроде Camera Overhaul
     * добавляют свои преобразования поверх. Первая поза = результат только одного шага, отсюда
     * репорт «влево-вправо качается, а от шагов нет».
     * Руку отсекаем НЕ порядком, а её собственным окном отрисовки (rtInHand в миксине): рука
     * рисуется после мира и крутится куда сильнее (замер: крен 0.609 против ~0.05 у покачивания).
     */
    public static void setBobPose(org.joml.Matrix4fc m) {
        bobTaken = true;
        bobPose.set(m);
    }
    public static org.joml.Matrix4fc bobPose() { return bobPose; }

    public static void setCamera(double ox, double oy, double oz,
                                 float fx, float fy, float fz,
                                 float ux, float uy, float uz,
                                 float lx, float ly, float lz) {
        camOx = ox; camOy = oy; camOz = oz;
        fX = fx; fY = fy; fZ = fz; uX = ux; uY = uy; uZ = uz; lX = lx; lY = ly; lZ = lz;
        camSet = true;
    }

    public static void setProjection(float m00, float m11) {
        if (m00 != 0) tanX = 1.0f / m00;
        if (m11 != 0) tanY = 1.0f / m11;
    }

    /** Из WorldRenderer.setupRenderer: камера под водой + игровое время (для анимации). */
    public static void setUnderwater(boolean uw, float timeTicks) {
        underwater = uw ? 1.0f : 0.0f; animTime = timeTicks;
    }

    // === M8.126: пак с АЛЬФА-МЕТКАМИ (Actually 3D Stuff) ===
    // Его core-шейдер трактует особые значения альфы как метки материалов (фулбрайт и т.п.),
    // а не прозрачность. Детект: кто-то НЕ-ванильный подменил соответствующий core-шейдер.
    // M8.130: измерение для неба/тумана (0 верхний мир, 1 Ад, 2 Энд). Ставит WorldRenderer.
    private static volatile int dimension = 0;
    public static void setDimension(int d) { dimension = d; }

    // M8.133: цвет тумана Ада по биому (наша палитра; плавно наплывает в WorldRenderer)
    private static volatile float netherFogR = 0.30f, netherFogG = 0.06f, netherFogB = 0.05f;
    public static void setNetherFog(float r, float g, float b) { netherFogR = r; netherFogG = g; netherFogB = b; }

    // M8.144: звёздная гладь портала в Энд. Центр 3x3 (XZ) + Y поверхности (blockY+0.75).
    // endPortalY < -1e8 -> портала рядом нет (шейдер пропускает). Скан в WorldRenderer (троттл, верх. мир).
    private static volatile float endPortalCX = 0f, endPortalCZ = 0f, endPortalY = -1.0e9f;
    public static void setEndPortal(float cx, float cz, float y) { endPortalCX = cx; endPortalCZ = cz; endPortalY = y; }
    public static void clearEndPortal() { endPortalY = -1.0e9f; }

    private static volatile float alphaTagPack = 0f;
    public static void detectAlphaTagPack() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) { alphaTagPack = 0f; return; }
            var res = mc.getResourceManager().getResource(
                    net.minecraft.resources.Identifier.parse(
                            "minecraft:shaders/core/rendertype_item_entity_translucent_cull.fsh"));
            boolean tags = res.isPresent() && !"vanilla".equals(res.get().sourcePackId());
            if (tags != (alphaTagPack > 0.5f))
                net.vulkanmod.Initializer.LOGGER.info("[RT] resource pack with alpha tags (Actually 3D Stuff): {}",
                        tags ? ("ACTIVE, pack " + res.get().sourcePackId()) : "disabled");
            alphaTagPack = tags ? 1f : 0f;
        } catch (Throwable t) {
            alphaTagPack = 0f;
        }
    }

    /**
     * M8.122: push для РАСТРОВОГО пост-прохода («Шейдеры» без RT). Раскладка — ТА ЖЕ, что у
     * RT-пуша в recordCompute (класс багов: рассинхрон push-констант — раскладку не менять!):
     * время и экранные эффекты для слоёв поста + размеры кадра. Блит кладёт растр в полном
     * разрешении, поэтому «рендерный» (obox[1].xy) и «экранный» (obox[1].zw) размеры равны.
     */
    public static void fillRasterPush(java.nio.ByteBuffer push, int w, int h) {
        // M8.123: базис камеры + тангенсы + солнце — для SSAO и контактных теней по глубине.
        // ⚠️ params.z/w в RT-пуше — тангенсы РУКИ; пост их не читает, в растре кладём ЭКРАННЫЕ.
        push.putFloat(16, fX).putFloat(20, fY).putFloat(24, fZ);   // forward
        push.putFloat(32, uX).putFloat(36, uY).putFloat(40, uZ);   // up
        push.putFloat(48, lX).putFloat(52, lY).putFloat(56, lZ);   // left
        push.putFloat(72, tanX).putFloat(76, tanY);
        // M8.124: позиция камеры В МИРЕ (по модулю 4096 — фазе волн хватает, а точность float
        // на больших координатах не страдает); origin.w пост не читает (размеры в obox[1])
        push.putFloat(0, (float) (camOx % 4096.0)).putFloat(4, (float) (camOy % 4096.0))
            .putFloat(8, (float) (camOz % 4096.0));
        push.putFloat(176, sunX).putFloat(180, sunY).putFloat(184, sunZ).putFloat(188, timeOfDay);
        push.putFloat(68, animTime);        // params.y — время (языки пламени, зерно)
        push.putFloat(116, fxOnFire);       // outlineInfo.y — ГОРИМ
        // obox[0] = (режим F6, near, far, DLSS ВЫКЛ), obox[1] = размеры (рендер == экран).
        // M8.124b: F6 работает и в фолбэке — свои виды (глубина/маска воды), не гадаем глазами.
        push.putFloat(128, (float) RtPost.debugView).putFloat(132, NEAR).putFloat(136, FAR).putFloat(140, 0f);
        push.putFloat(144, (float) w).putFloat(148, (float) h)
            .putFloat(152, (float) w).putFloat(156, (float) h);
        push.putFloat(192, fxBlind).putFloat(196, fxNight).putFloat(200, fxPoison).putFloat(204, fxWither);
        push.putFloat(208, fxNausea).putFloat(212, fxDark).putFloat(216, fxOneHeart).putFloat(220, fxThreeHeart);
        push.putFloat(224, fxMinorDmg).putFloat(228, fxCritDmg).putFloat(232, fxExitLava).putFloat(236, fxBreak);
    }

    /** M8.8: камера погружена в ЛАВУ (FogType.LAVA) — тёплый плотный туман-свечение. */
    public static void setEyeInLava(boolean v) { eyeInLava = v ? 1.0f : 0.0f; }

    // M8.9: блок ПОД ПРИЦЕЛОМ — ванильная обводка. RT-кадр перезаписывает ванильный, поэтому
    // обводку рисуем прямо в RT-шейдере по этой позиции.
    // M8.10: эффекты камеры/зелий (RT перезаписывает ванильный кадр -> рисуем сами)
    private static volatile float fxBlind = 0f, fxNight = 0f, fxPoison = 0f, fxWither = 0f;
    private static volatile float fxNausea = 0f, fxDark = 0f, fxHurt = 0f, fxHealth = 1f;
    private static volatile float fxOnFire = 0f;   // ГОРИМ (ванильный fire overlay + heat-distort Eclipse)
    private static volatile float fxEnterW = 0f, fxExitW = 0f;   // МОМЕНТ погружения/выныривания (Eclipse)
    /** Всплеск при пересечении глади: значения 1 -> затухают в Java. */
    public static void setWaterTransition(float enter, float exit) { fxEnterW = enter; fxExitW = exit; }
    private static volatile float fxOneHeart = 0f, fxThreeHeart = 0f, fxMinorDmg = 0f, fxCritDmg = 0f;
    private static volatile float fxExitLava = 0f;
    private static volatile float fxBreak = 0f;   // прогресс ломания блока под прицелом (0..1)
    // M8.18 НЕБО: направление на солнце (из игрового времени) + время суток
    private static volatile float sunX = 0.55f, sunY = 0.40f, sunZ = 0.45f, timeOfDay = 0.25f;
    private static volatile float moonPhase = 0f;   // 0..7, 0 = полнолуние
    private static volatile float cloudTime = 0f;   // сдвиг облаков по ветру
    private static volatile float cloudCover = 1.0f; // ПОКРЫТИЕ (свой суточный рандом + дождь)

    /**
     * Покрытие облаков с множителем из настроек. ⚠️ В поле упакованы ДВА числа: старшая часть —
     * ТИП облаков (шаг 8), младшая — их КОЛИЧЕСТВО. Множитель применяем только к количеству,
     * иначе он менял бы тип и небо превращалось бы в другую погоду.
     */
    private static float cloudCoverScaled() {
        float mul = net.vulkanmod.Initializer.CONFIG.cloudAmount / 100f;
        int type = (int) (cloudCover / 8f);
        float amt = Math.min((cloudCover - type * 8f) * mul, 7.99f);
        return type * 8f + amt;
    }
    private static volatile float rainAmt = 0f;      // сила дождя 0..1
    public static void setClouds(float t, float cover, float rain) {
        cloudTime = t; cloudCover = cover; rainAmt = rain;
    }
    private static volatile float wetness = 0f;     // влажность 0..1 (лужи), 2-мин хвост; в дробь фазы луны
    public static void setWetness(float w) { wetness = w; }
    private static volatile float aurora = 0f;
    /** Северное сияние 0..1 (из WorldRenderer: снежный биом у камеры, сглажено). */
    public static void setAurora(float a) { aurora = a; }
    public static void setSun(float x, float y, float z, float tod, float phase) {
        sunX = x; sunY = y; sunZ = z; timeOfDay = tod; moonPhase = phase;
    }
    public static void setBreakProgress(float p) { fxBreak = p; }
    public static void setEffects(float blind, float night, float poison, float wither,
                                  float nausea, float dark, float onFire) {
        fxBlind = blind; fxNight = night; fxPoison = poison; fxWither = wither;
        fxNausea = nausea; fxDark = dark; fxOnFire = onFire;
    }
    /** Униформы Eclipse: oneHeart/threeHeart (низкое HP), Minor/CriticalDamageTaken, exitLava. */
    public static void setEclipseFx(float oneHeart, float threeHeart, float minorDmg,
                                    float critDmg, float exitLava) {
        fxOneHeart = oneHeart; fxThreeHeart = threeHeart;
        fxMinorDmg = minorDmg; fxCritDmg = critDmg; fxExitLava = exitLava;
    }

    // Рёбра контура блока под прицелом (ванильный forAllEdges). У куба их 12, у составных форм
    // больше — 32 хватает с запасом. Живут в UBO: в push-константы не влезли бы.
    public static final int OUTLINE_MAX_EDGES = 32;
    private static final float[] oBoxes = new float[OUTLINE_MAX_EDGES * 6];
    private static volatile int oCount = 0;
    /** data = по 6 float на коробку (min.xyz, max.xyz) в МИРОВЫХ координатах; count <= 4. */
    public static void setOutlineBoxes(float[] data, int count) {
        int n = Math.min(count, OUTLINE_MAX_EDGES);
        System.arraycopy(data, 0, oBoxes, 0, n * 6);
        oCount = n;
    }

    /** Из WorldRenderer: Y ГЛАДИ над камерой (скан колонки воды). -1e9 = глади нет. */
    public static void setWaterSurfaceY(float y) { waterSurfaceY = y; }

    /** M8.7: свет в позиции игрока (для руки) + свет предмета в руке (динамический источник), 0..1. */
    public static void setPlayerLight(float blockLight, float held) {
        playerBlockLight = blockLight; heldLight = held;
    }

    /**
     * ⚠️ АТЛАС БЛОКОВ — СТРОГО ПО ИМЕНИ (M8.69). Раньше он ловился догадкой: «текстура, привязанная
     * в слоте 0 во время отрисовки чанков, если она шире 256 пикселей». Но привязка доживает до
     * следующего кадра, и стоило игроку поставить СУНДУК, как в слоте оказывался сундучный атлас —
     * трассировка начинала читать мировые UV из него. Текстура сундука растягивалась по дну, а там,
     * где альфа-тест по чужому атласу не проходил, блок для луча просто ИСЧЕЗАЛ: мир на миг
     * становился прозрачным. Спрашиваем атлас у менеджера текстур по его идентификатору — ошибиться
     * тут уже нечем.
     */
    public static void grabBlockAtlas() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return;
            var abs = mc.getTextureManager().getTexture(
                    net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (abs != null && abs.getTexture() instanceof net.vulkanmod.render.engine.VkGpuTexture vk) {
                var glt = net.vulkanmod.gl.VkGlTexture.getTexture(vk.glId());
                if (glt != null && glt.getVulkanImage() != null) setAtlas(glt.getVulkanImage());
            }
        } catch (Throwable ignored) {}
    }

    public static void setAtlas(VulkanImage a) { atlas = a; }

    // Отладочный PNG-скриншотер удалён (M8.27): он читал выходной SSBO, которого больше нет —
    // вывод теперь HDR-образ. Да и жил он на Vulkan.waitIdle() раз в 3 с, что давало микролаг.
    public static void tick() { }

    static final int FRAMES = 3;                        // пер-кадровые дескриптор-сеты (async: не трогаем занятый)

    // ==================== ЗАМЕР ВРЕМЕНИ НА ВИДЕОКАРТЕ (M8.91) ====================
    // ⚠️ ЗАЧЕМ. Мы знаем, что упираемся в видеокарту (без ограничения кадров она в полке), но НЕ
    // знаем, что именно её греет: трассировка, DLSS или пост. Оптимизировать вслепую = гадать.
    // Метки времени пишет сам GPU по ходу кадра; читаем их СПУСТЯ FRAMES кадров, без ожидания —
    // иначе замер сам стал бы стопом конвейера и врал бы о том, что мерит.
    private static final int TS_PER_FRAME = 4;         // старт | после трассировки | после DLSS | после поста
    private long tsPool = VK_NULL_HANDLE;
    private final boolean[] tsWritten = new boolean[FRAMES];
    private static double gpuTrace = 0, gpuDlss = 0, gpuPost = 0;
    private static int gpuSamples = 0;
    private static long gpuLogAt = 0;
    private static long cfgLogAt = 0;
    private final long descLayout, descPool, pipelineLayout;
    private long pipeline;             // пересобирается при смене настроек (см. ensurePipeline)
    private long shaderModule;         // модуль текущего пайплайна — рушим вместе с ним
    private String pipeSig = null;     // подпись настроек, под которую собран пайплайн
    private final long[] descSets = new long[FRAMES];
    private int frameIdx = 0;
    private final AccelStruct.RawBuffer dummyQuadTex;   // заглушка binding 5, когда сущностей нет

    // --- камера ПРОШЛОГО кадра + jitter (для DLSS: векторы движения и субпиксельное накопление) ---
    private static final int CAM_STRIDE = 1280;         // камера + jitter + 32 ребра обводки
    static final float NEAR = 0.05f, FAR = 1024.0f;     // те же near/far пойдут в матрицу для DLSS
    private final AccelStruct.RawBuffer camUbo;
    private final ByteBuffer camMap;
    private float pOx, pOy, pOz, pfX = 0, pfY = 0, pfZ = 1, puX = 0, puY = 1, puZ = 0, plX = 1, plY = 0, plZ = 0;
    private float pTanX = 1, pTanY = 1;
    private boolean prevValid = false;
    private long frameCounter = 0;
    private long lastFrameNs = 0;

    /** Halton — «равномерно рассыпанные» точки: сетка лучей дрожит без повторов и сгустков. */
    private static float halton(long i, int base) {
        float f = 1, r = 0;
        while (i > 0) { f /= base; r += f * (i % base); i /= base; }
        return r;
    }

    /**
     * Матрицы для DLSS: worldToView и viewToClip, 32 float подряд (по строкам).
     * ⚠️ Они обязаны в ТОЧНОСТИ описывать ту же камеру, из которой мы пускаем лучи и считаем
     * глубину/векторы движения. Иначе RR будет репроецировать кадр по одной камере, а данные
     * получать по другой — и всё поплывёт.
     */
    private float[] camMatrices() {
        float rx = -lX, ry = -lY, rz = -lZ;                     // right = -left
        float ox = (float) camOx, oy = (float) camOy, oz = (float) camOz;
        float n = NEAR, f = FAR;
        return new float[]{
                // worldToView: строки — базис камеры, последний столбец — перенос
                rx, ry, rz, -(rx * ox + ry * oy + rz * oz),
                uX, uY, uZ, -(uX * ox + uY * oy + uZ * oz),
                -fX, -fY, -fZ, (fX * ox + fY * oy + fZ * oz),   // камера смотрит по -Z
                0f, 0f, 0f, 1f,
                // viewToClip: перспектива Vulkan (y вниз, глубина 0..1) с теми же near/far,
                // по которым шейдер считает NDC-глубину
                1f / tanX, 0f, 0f, 0f,
                0f, -1f / tanY, 0f, 0f,
                0f, 0f, f / (n - f), (n * f) / (n - f),
                0f, 0f, -1f, 0f,
        };
    }

    private RtSnapshot() {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            // 0=TLAS, 1=вывод(SSBO), 2=таблица адресов(SSBO), 3=атлас(sampler2D),
            // 4=текстуры сущностей (sampler2D[256]), 5=квад->слот текстуры (SSBO),
            // 6=ЦВЕТНЫЕ ИСТОЧНИКИ СВЕТА (SSBO), 7=наша текстура шума (эффекты камеры) — M8.12/95
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(23, stack);   // M8.162: +PBR-карты пака
            binds.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)   // M8.27: HDR-образ
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(RtEntities.MAX_TEX).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(6).binding(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(7).binding(7).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(8).binding(8).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(9).binding(9).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(10).binding(10).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);  // 10 = осадки (rain/snow)
            binds.get(11).binding(11).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);  // 11 = удары капель (рябь)
            binds.get(12).binding(12).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);  // 12 = карта материалов (отражения)
            binds.get(13).binding(13).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);  // 13 = камера ПРОШЛОГО кадра + jitter
            for (int i = 14; i <= 19; i++)                                        // 14..19 = guide-буферы DLSS
                binds.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // M8.153: решётка оттенка света (см. RtLightVolume)
            binds.get(20).binding(20).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // M8.162: PBR-карты ресурспака (labPBR _n/_s) в UV атласа — см. RtPbrMaps.
            // ⚠️ Биндим их ВСЕГДА, даже когда ручка выключена: заглушка 1x1 с «нет данных» не
            // искажает ничего, а ветка RT_PBR в шейдере тогда просто не скомпилирована.
            binds.get(21).binding(21).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(22).binding(22).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer pDsl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, dslInfo, null, pDsl), "dsl");
            descLayout = pDsl.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(5, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(FRAMES);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5 * FRAMES);
            // M8.162: одиночных сэмплеров стало ВОСЕМЬ (атлас, шум, трещины, луна, осадки, карта
            // материалов + две PBR-карты пака), плюс массив текстур сущностей на MAX_TEX слотов.
            // ⚠️ Пул обязан быть не меньше набора: VkAllocateDescriptorSets с превышением вернёт
            // ошибку, и RT не запустится вовсе.
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount((8 + RtEntities.MAX_TEX) * FRAMES);
            poolSizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(7 * FRAMES);   // HDR + 6 guide
            poolSizes.get(4).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(FRAMES);      // камера прошлого кадра
            VkDescriptorPoolCreateInfo dpInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(poolSizes).maxSets(FRAMES);
            LongBuffer pDp = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, dpInfo, null, pDp), "descriptor pool");
            descPool = pDp.get(0);

            for (int f = 0; f < FRAMES; f++) {
                VkDescriptorSetAllocateInfo dsAlloc = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default().descriptorPool(descPool).pSetLayouts(stack.longs(descLayout));
                LongBuffer pSet = stack.mallocLong(1);
                check(vkAllocateDescriptorSets(device, dsAlloc, pSet), "alloc set");
                descSets[f] = pSet.get(0);
            }

            dummyQuadTex = AccelStruct.createBuffer(4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            // UBO камеры прошлого кадра: свой слот на КАЖДЫЙ кадр в полёте (перезаписывать чужой,
            // ещё читаемый GPU, нельзя). Держим замапленным — обновление каждый кадр.
            camUbo = AccelStruct.createBuffer((long) CAM_STRIDE * FRAMES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            PointerBuffer pMap = stack.mallocPointer(1);
            check(vkMapMemory(device, camUbo.memory, 0, (long) CAM_STRIDE * FRAMES, 0, pMap), "map cam ubo");
            camMap = MemoryUtil.memByteBuffer(pMap.get(0), CAM_STRIDE * FRAMES);

            // Пул меток времени GPU: по 4 на кадр, на FRAMES кадров вперёд.
            VkQueryPoolCreateInfo qp = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(TS_PER_FRAME * FRAMES);
            LongBuffer pQ = stack.mallocLong(1);
            if (vkCreateQueryPool(device, qp, null, pQ) == VK_SUCCESS) tsPool = pQ.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(256);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descLayout)).pPushConstantRanges(pcr);
            LongBuffer pPl = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, plInfo, null, pPl), "pipeline layout");
            pipelineLayout = pPl.get(0);

            buildPipeline(device);   // первый пайплайн — под текущие настройки
        }
        Initializer.LOGGER.info("[RT] RtSnapshot ready - textured RT pipeline {}x{} built.", W, H);
    }

    // === RT PATCH (M8.6): доступ к RT-кадру для вывода на экран ===
    // Прогнать компьют в SSBO (без PNG-читки), для блита на экран каждый кадр.
    // Записать RT-компьют в кадровый буфер (async, без waitIdle). Возвращает true, если записано.
    public static boolean recordCompute(VkCommandBuffer frameCmd) {
        if (failed || !DeviceManager.rayTracingSupported || !camSet || tanX == 0 || atlas == null) return false;
        // ⚠️ Только при живом мире. На главном меню/выходе level==null, а мир выгружается
        // (буферы/потоки освобождаются) — трассировать TLAS в это время = зависание GPU/ОС.
        if (net.minecraft.client.Minecraft.getInstance().level == null) return false;
        if (RtWorld.INSTANCE == null) return false;
        long tlas = RtWorld.INSTANCE.tlasHandle();
        long vtxTable = RtWorld.INSTANCE.vtxTableBuffer();
        if (tlas == VK_NULL_HANDLE || vtxTable == VK_NULL_HANDLE) return false;
        try {
            if (INSTANCE == null) INSTANCE = new RtSnapshot();
            INSTANCE.recordComputeImpl(frameCmd, tlas, vtxTable, atlas);
            return true;
        } catch (Throwable e) {
            failed = true;
            Initializer.LOGGER.error("[RT] recordCompute failed: ", e);
            return false;
        }
    }
    /**
     * Прочитать метки ПРОШЛОГО прохода этого слота. ⚠️ БЕЗ ожидания (флага WAIT нет): если GPU ещё
     * не дошёл — просто пропускаем кадр. Ждать тут = самому создать тот стоп конвейера, который мы
     * и ищем, и получить замер собственного ожидания вместо замера работы.
     */
    private void readGpuTimes(int slot) {
        if (tsPool == VK_NULL_HANDLE || !tsWritten[slot]) return;
        try (MemoryStack stack = stackPush()) {
            java.nio.LongBuffer res = stack.mallocLong(TS_PER_FRAME);
            int r = vkGetQueryPoolResults(Vulkan.getVkDevice(), tsPool, slot * TS_PER_FRAME,
                    TS_PER_FRAME, res, 8, VK_QUERY_RESULT_64_BIT);
            if (r != VK_SUCCESS) return;   // ещё не готово — не беда, возьмём в следующий раз

            // Метка меряется в тиках GPU; в наносекунды переводит timestampPeriod драйвера.
            double ns = DeviceManager.deviceProperties.limits().timestampPeriod();
            gpuTrace += (res.get(1) - res.get(0)) * ns / 1e6;   // мс
            gpuDlss  += (res.get(2) - res.get(1)) * ns / 1e6;
            gpuPost  += (res.get(3) - res.get(2)) * ns / 1e6;
            gpuSamples++;

            long now = System.currentTimeMillis();
            if (now - gpuLogAt > 3000 && gpuSamples > 0) {
                gpuLogAt = now;
                Initializer.LOGGER.info(String.format(
                        "[RT] ВРЕМЯ GPU: трассировка %.2f мс | DLSS %.2f мс | пост %.2f мс | итого %.2f мс (%dx%d)",
                        gpuTrace / gpuSamples, gpuDlss / gpuSamples, gpuPost / gpuSamples,
                        (gpuTrace + gpuDlss + gpuPost) / gpuSamples, W, H));
                gpuTrace = gpuDlss = gpuPost = 0; gpuSamples = 0;
            }
        }
    }

    /**
     * НАСТРОЙКИ КАК КОД, А НЕ КАК ФЛАГИ.
     *
     * ⚠️ ЗАЧЕМ. Замер сказал странное: выключение теней, отражений, облаков и четырёх лучей амбиента
     * НЕ МЕНЯЛО время трассировки — а уменьшение числа пикселей меняло почти линейно. Так ведёт себя
     * перегруженный «уберщейдер»: видеокарта выделяет регистры под САМЫЙ ТЯЖЁЛЫЙ путь исполнения,
     * поэтому код облаков занимает их, даже когда облака выключены условием. Мало регистров — мало
     * потоков в полёте — ядра стоят в ожидании памяти, и вырезание веток НИЧЕГО не даёт.
     *
     * Поэтому настройка теперь ВЫРЕЗАЕТ КОД препроцессором, а пайплайн пересобирается под новый
     * набор. Это дороже (пересборка ~секунда), зато выключенное действительно исчезает.
     */
    private String pipelineDefines() {
        var c = Initializer.CONFIG;
        StringBuilder d = new StringBuilder();
        if (DeviceManager.rtTextureArraySupported) d.append("#define ENT_TEX\n");
        if (c.rtShadows)          d.append("#define RT_SHADOWS\n");
        if (c.rtReflections)      d.append("#define RT_REFLECTIONS\n");
        if (c.rtClouds)           d.append("#define RT_CLOUDS\n");
        if (c.rtGodRays)          d.append("#define RT_GODRAYS\n");
        if (c.rtAmbientRays > 0)  d.append("#define RT_AMBIENT\n");
        // M8.162: PBR-материалы из ресурспака. Определение ставим ТОЛЬКО когда карты уже собраны
        // (RtPbrMaps.init — ленивый, зовётся из записи дескрипторов): сэмплить нечего — не платим за
        // ветку. Ручка выключена -> карты инвалидируются, определение уходит, пайплайн пересобирается.
        if (c.rtPbr && RtPbrMaps.ready()) d.append("#define RT_PBR\n");
        return d.toString();
    }

    private void buildPipeline(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            String defs = pipelineDefines();
            long shader = createShaderModule(device, COMP_SRC.replace("//DEFINES", defs));
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pPipe = stack.mallocLong(1);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpci, null, pPipe), "compute pipeline");
            pipeline = pPipe.get(0);
            vkDestroyShaderModule(device, shader, null);
            pipeSig = defs;
            Initializer.LOGGER.info("[RT] pipeline compiled for settings: [{}]", defs.replace("\n", " ").trim());
        }
    }

    /** Настройки сменились — пересобрать пайплайн (⚠️ ждём GPU: старый ещё может исполняться). */
    private void ensurePipeline(VkDevice device) {
        String defs = pipelineDefines();
        if (defs.equals(pipeSig)) return;
        vkDeviceWaitIdle(device);
        vkDestroyPipeline(device, pipeline, null);
        buildPipeline(device);
    }

    public static int width()  { return W; }
    public static int height() { return H; }

    /** Сборка compute-модуля из GLSL — нужна и посту (RtPost), поэтому не приватная. */
    static long compileCompute(VkDevice device, String source) { return createShaderModule(device, source); }

    /** Освобождение при выходе (из Vulkan.cleanUp, до уничтожения устройства). */
    public static void shutdown() {
        if (INSTANCE == null) return;
        try {
            VkDevice device = Vulkan.getVkDevice();
            vkDestroyPipeline(device, INSTANCE.pipeline, null);
            vkDestroyPipelineLayout(device, INSTANCE.pipelineLayout, null);
            vkDestroyDescriptorPool(device, INSTANCE.descPool, null);
            vkDestroyDescriptorSetLayout(device, INSTANCE.descLayout, null);
            AccelStruct.destroyBuffer(INSTANCE.dummyQuadTex);
            vkUnmapMemory(device, INSTANCE.camUbo.memory);
            AccelStruct.destroyBuffer(INSTANCE.camUbo);
            RtDlss.shutdown();   // NGX держит свои ресурсы на устройстве — гасим до его уничтожения
            RtPost.shutdown();
            RtLights.shutdown();
        } catch (Throwable t) { Initializer.LOGGER.error("[RT] RtSnapshot shutdown", t); }
        INSTANCE = null;
    }
    // === /RT PATCH ===

    // Записать компьют RT в SSBO в ЗАДАННЫЙ (кадровый) командный буфер — БЕЗ waitIdle (async):
    // компьют идёт в том же сабмите, что блит на экран, барьер обеспечивает порядок.
    private void recordComputeImpl(VkCommandBuffer frameCmd, long tlasHandle, long vtxTableBuffer, VulkanImage atlasTex) {
        VkDevice device = Vulkan.getVkDevice();
        var win = net.minecraft.client.Minecraft.getInstance().getWindow();
        int outW = Math.max(win.getWidth(), 1), outH = Math.max(win.getHeight(), 1);

        // Разрешение трассировки = доля экрана из настроек. Кратно 8: столько же, сколько в группе
        // компьюта, — иначе крайние группы работали бы вхолостую.
        int pct = Math.min(Math.max(Initializer.CONFIG.renderScale, 25), 100);
        // M8.122e: без апскейлера пониженный кадр растягивался бы на экран простым билинейным
        // блитом (мыло). Масштаб принудительно 100% (ручка в UI при этом погашена).
        // M8.156: тумблер апскейлера теперь ОТДЕЛЬНЫЙ от денойзера, и это рабочий режим, а не
        // заглушка: DLSS Ray Reconstruction умеет чистить при РАВНЫХ разрешениях. Значит
        // «денойзер вкл, апскейлер выкл» = RR на 100% — зерно убирается, картинка нативная.
        if (!RtDlss.usable() || !Initializer.CONFIG.upscalingActive()) pct = 100;
        int nW = Math.max(((outW * pct / 100) + 7) / 8 * 8, 64);
        int nH = Math.max(((outH * pct / 100) + 7) / 8 * 8, 64);
        if (nW != W || nH != H || pct != lastScalePct) {
            // ⚠️ Меняя разрешение, ждём видеокарту: кадры «в полёте» ещё читают старые образы, а мы
            // их сейчас освободим. Настройку крутят редко — секундная пауза тут честная плата.
            vkDeviceWaitIdle(device);
            W = nW; H = nH; lastScalePct = pct;
            RtDlss.resetFeature();   // фича привязана к разрешению входа -> пересоздать (NGX НЕ трогаем)
            Initializer.LOGGER.info("[RT] trace resolution: {}x{} ({}% of {}x{})",
                    W, H, pct, outW, outH);
        }

        ensurePipeline(device);   // настройки меняют САМ КОД шейдера -> пересборка пайплайна

        RtImage hdr = RtPost.hdrImage(W, H, outW, outH);   // HDR-выход трассировки (он же вход DLSS)
        if (hdr == null) return;

        // === DLSS (M8.29): поднимаем NGX и фичу Ray Reconstruction один раз ===
        // Фича создаётся ВНУТРИ командного буфера кадра — NGX так и просит (ему нужен cmd list).
        if (RtDlss.enabled && !RtDlss.ready() && RtPost.guides() != null) {
            if (RtDlss.init(Vulkan.getVkInstance().address(),
                            DeviceManager.physicalDevice.address(),
                            device.address())) {
                RtDlss.createFeature(frameCmd.address(), W, H, outW, outH);
            }
        }
        try (MemoryStack stack = stackPush()) {
            frameIdx = (frameIdx + 1) % FRAMES;      // не трогаем сет, ещё занятый кадром «в полёте»
            long descSet = descSets[frameIdx];
            // HDR-образ должен быть в GENERAL, чтобы compute мог в него писать.
            // srcAccess = SHADER_READ: в прошлом кадре его читал пост — пишем поверх (WAR).
            hdr.transition(stack, frameCmd, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            // Обновляем привязки, которые меняются: TLAS, таблица адресов, атлас.
            VkWriteDescriptorSetAccelerationStructureKHR asw =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType$Default().pAccelerationStructures(stack.longs(tlasHandle));
            VkDescriptorBufferInfo.Buffer tableInfo = VkDescriptorBufferInfo.calloc(1, stack);
            tableInfo.get(0).buffer(vtxTableBuffer).offset(0).range(VK_WHOLE_SIZE);
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
            imgInfo.get(0).imageView(atlasTex.getImageView()).sampler(atlasTex.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // M8.4: массив текстур сущностей (пустые слоты добиваем атласом — без
            // partiallyBound) + таблица «квад -> слот» (или заглушка, если сущностей нет)
            var entTex = RtWorld.INSTANCE != null
                    ? RtWorld.INSTANCE.entityTextures() : new net.vulkanmod.vulkan.texture.VulkanImage[0];
            long quadTexBuf = RtWorld.INSTANCE != null ? RtWorld.INSTANCE.entityQuadTexBuffer() : VK_NULL_HANDLE;
            VkDescriptorImageInfo.Buffer entInfos = VkDescriptorImageInfo.calloc(RtEntities.MAX_TEX, stack);
            for (int i = 0; i < RtEntities.MAX_TEX; i++) {
                var tex = (i < entTex.length && entTex[i] != null) ? entTex[i] : atlasTex;
                entInfos.get(i).imageView(tex.getImageView()).sampler(tex.getSampler())
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            VkDescriptorBufferInfo.Buffer quadTexInfo = VkDescriptorBufferInfo.calloc(1, stack);
            quadTexInfo.get(0).buffer(quadTexBuf != VK_NULL_HANDLE ? quadTexBuf : dummyQuadTex.buffer)
                    .offset(0).range(VK_WHOLE_SIZE);

            // M8.7: буфер цветных источников света (если скана ещё не было — заглушка, count=0)
            long lightsBuf = RtLights.buffer();
            VkDescriptorBufferInfo.Buffer lightInfo = VkDescriptorBufferInfo.calloc(1, stack);
            lightInfo.get(0).buffer(lightsBuf != VK_NULL_HANDLE ? lightsBuf : dummyQuadTex.buffer)
                    .offset(0).range(VK_WHOLE_SIZE);
            int lightCount = (lightsBuf != VK_NULL_HANDLE
                    && net.vulkanmod.Initializer.CONFIG.rtColoredLights) ? RtLights.count() : 0;

                        // наша текстура шума (если не загрузилась — подставляем атлас, эффекты сами себя выключат)
            RtNoise.init();
            var noiseTex = RtNoise.get() != null ? RtNoise.get() : atlasTex;
            VkDescriptorImageInfo.Buffer noiseInfo = VkDescriptorImageInfo.calloc(1, stack);
            noiseInfo.get(0).imageView(noiseTex.getImageView()).sampler(noiseTex.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

                        RtCracks.init();
            var crackTex = RtCracks.get() != null ? RtCracks.get() : atlasTex;
            VkDescriptorImageInfo.Buffer crackInfo = VkDescriptorImageInfo.calloc(1, stack);
            crackInfo.get(0).imageView(crackTex.getImageView()).sampler(crackTex.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

                        RtMoon.init();
            var moonTexV = RtMoon.get() != null ? RtMoon.get() : atlasTex;
            VkDescriptorImageInfo.Buffer moonInfo = VkDescriptorImageInfo.calloc(1, stack);
            moonInfo.get(0).imageView(moonTexV.getImageView()).sampler(moonTexV.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

                        RtRain.init();
            var rainTexV = RtRain.get() != null ? RtRain.get() : atlasTex;
            VkDescriptorImageInfo.Buffer rainInfo = VkDescriptorImageInfo.calloc(1, stack);
            rainInfo.get(0).imageView(rainTexV.getImageView()).sampler(rainTexV.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // M8.23: буфер УДАРОВ КАПЕЛЬ (рябь). Если ещё не готов — заглушка (count=0 в заголовке).
            RtRipples.init();
            long ripplesBuf = RtRipples.buffer();
            VkDescriptorBufferInfo.Buffer rippleInfo = VkDescriptorBufferInfo.calloc(1, stack);
            rippleInfo.get(0).buffer(ripplesBuf != VK_NULL_HANDLE ? ripplesBuf : dummyQuadTex.buffer)
                    .offset(0).range(VK_WHOLE_SIZE);

            // M8.24: КАРТА МАТЕРИАЛОВ (отражения блоков). Если атлас ещё не готов — атлас как заглушка.
            RtMaterialMap.init();
            var matTexV = RtMaterialMap.get() != null ? RtMaterialMap.get() : atlasTex;
            VkDescriptorImageInfo.Buffer matInfo = VkDescriptorImageInfo.calloc(1, stack);
            matInfo.get(0).imageView(matTexV.getImageView()).sampler(matTexV.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // M8.162: PBR-КАРТЫ ПАКА (labPBR _n/_s, см. RtPbrMaps). Сборка ленивая: пока паков нет,
            // ручка выключена или атлас ещё не сшит, RtPbrMaps отдаёт ЗАГЛУШКИ 1x1 «нет данных».
            // ⚠️ Подставлять сюда атлас НЕЛЬЗЯ (в отличие от прочих текстур выше): шейдер принял бы
            // альбедо за нормаль и развернул бы освещение. Заглушка же не искажает ничего.
            RtPbrMaps.frameTick();   // отставные текстуры переживают 3 кадра — тут их и отпускаем
            RtPbrMaps.init();
            var pbrTexN = RtPbrMaps.normals();
            var pbrTexS = RtPbrMaps.materials();
            VkDescriptorImageInfo.Buffer pbrNInfo = VkDescriptorImageInfo.calloc(1, stack);
            pbrNInfo.get(0).imageView(pbrTexN.getImageView()).sampler(pbrTexN.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer pbrSInfo = VkDescriptorImageInfo.calloc(1, stack);
            pbrSInfo.get(0).imageView(pbrTexS.getImageView()).sampler(pbrTexS.getSampler())
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkDescriptorImageInfo.Buffer outInfo = VkDescriptorImageInfo.calloc(1, stack);
            outInfo.get(0).imageView(hdr.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            // --- guide-буферы (14..18) + камера прошлого кадра (13) ---
            RtImage[] guides = RtPost.guides();
            if (guides == null) return;
            for (RtImage g : guides)
                g.transition(stack, frameCmd, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // JITTER: субпиксельное дрожание сетки лучей (Halton 2,3), в пикселях [-0.5..0.5].
            // ⚠️ ЧИСЛО ФАЗ — ПО ГАЙДУ: 8 * (выход/рендер)^2. Для 1280->1920 это 18. Раньше стояло
            // 128: сэмплы размазывались по слишком длинному циклу, покрытие пикселя не набиралось,
            // и сеть не сходилась — в гайде это первый симптом в списке, «screen shaking».
            frameCounter++;
            int phases = Math.max(8, Math.round(8f * ((float) outW / W) * ((float) outH / H)));
            long ph = frameCounter % phases + 1;
            // M8.122e: джиттер НУЖЕН ТОЛЬКО DLSS (субпиксельное накопление сэмплов). Без неё
            // дрожание никто не сводит обратно — картинка мелко тряслась бы (GPU без DLSS).
            float jx = 0f, jy = 0f;
            if (RtDlss.usable()) { jx = halton(ph, 2) - 0.5f; jy = halton(ph, 3) - 0.5f; }

            // Первый кадр: прошлой камеры ещё нет — берём текущую (векторы движения = 0)
            if (!prevValid) {
                pOx = (float) camOx; pOy = (float) camOy; pOz = (float) camOz;
                pfX = fX; pfY = fY; pfZ = fZ; puX = uX; puY = uY; puZ = uZ; plX = lX; plY = lY; plZ = lZ;
                pTanX = tanX; pTanY = tanY; prevValid = true;
            }
            int camOff = frameIdx * CAM_STRIDE;
            camMap.putFloat(camOff,       pOx).putFloat(camOff + 4,  pOy).putFloat(camOff + 8,  pOz).putFloat(camOff + 12, 0f);
            camMap.putFloat(camOff + 16,  pfX).putFloat(camOff + 20, pfY).putFloat(camOff + 24, pfZ).putFloat(camOff + 28, 0f);
            camMap.putFloat(camOff + 32,  puX).putFloat(camOff + 36, puY).putFloat(camOff + 40, puZ).putFloat(camOff + 44, pTanY);
            camMap.putFloat(camOff + 48,  plX).putFloat(camOff + 52, plY).putFloat(camOff + 56, plZ).putFloat(camOff + 60, pTanX);
            camMap.putFloat(camOff + 64,  jx).putFloat(camOff + 68,  jy).putFloat(camOff + 72, NEAR).putFloat(camOff + 76, FAR);
            // Номер кадра — для декорреляции шума во времени (см. frameSeed в шейдере).
            // По модулю, чтобы во float не терялась младшая часть за долгую игру.
            long nowNs = System.nanoTime();
            float frameDt = lastFrameNs == 0 ? 0.016f : Math.min((nowNs - lastFrameNs) / 1e9f, 0.1f);
            lastFrameNs = nowNs;
            camMap.putFloat(camOff + 80, (float) (frameCounter % 65536L))
                  .putFloat(camOff + 84, (float) oCount)
                  .putFloat(camOff + 88, frameDt)   // время кадра: по нему дождь «сдвигается» назад
                  .putFloat(camOff + 92, 0f);
            // рёбра обводки: пары точек (a.xyz, b.xyz), по 16 байт на вектор
            for (int b = 0; b < OUTLINE_MAX_EDGES; b++) {
                int off = camOff + 96 + b * 32;
                boolean live = b < oCount;
                for (int k = 0; k < 3; k++) {
                    camMap.putFloat(off + k * 4,      live ? oBoxes[b * 6 + k]     : 0f);
                    camMap.putFloat(off + 16 + k * 4, live ? oBoxes[b * 6 + 3 + k] : 0f);
                }
                camMap.putFloat(off + 12, 0f).putFloat(off + 28, 0f);
            }

            // === RT PATCH === НАСТРОЙКИ в шейдер (см. cam.rtCfg). Смещение = сразу за рёбрами обводки.
            var cfg = net.vulkanmod.Initializer.CONFIG;
            int cfgOff = camOff + 96 + OUTLINE_MAX_EDGES * 32;
            // ЗАМЕР ДОВЕРИЯ К НАСТРОЙКАМ: что Java РЕАЛЬНО кладёт в шейдер. Если тумблер выключен,
            // а тут единица — виноват UI (значение не сохраняется), а не графика.
            if (System.currentTimeMillis() - cfgLogAt > 3000) {
                cfgLogAt = System.currentTimeMillis();
                Initializer.LOGGER.info("[RT] SETTINGS TO SHADER: shadows={} ambient={} reflections={} clouds={} "
                                + "quality={} colouredLight={} resolution={}%",
                        cfg.rtShadows ? 1 : 0, cfg.rtAmbientRays, cfg.rtReflections ? 1 : 0,
                        cfg.rtClouds ? 1 : 0, cfg.rtQuality, cfg.rtColoredLights ? 1 : 0, cfg.renderScale);
            }
            camMap.putFloat(cfgOff,      cfg.rtShadows ? 1f : 0f)
                  .putFloat(cfgOff + 4,  (float) cfg.rtAmbientRays)
                  .putFloat(cfgOff + 8,  cfg.rtReflections ? 1f : 0f)
                  .putFloat(cfgOff + 12, cfg.rtClouds ? 1f : 0f);

            // ТУМАН ПРИВЯЗАН К ДАЛЬНОСТИ ПРОРИСОВКИ (предложение пользователя, и оно верное):
            // раньше начало было зашито на 128 блоков, и при дальности 8 чанков туман сжирал весь
            // горизонт, а при 32 не появлялся вовсе. Теперь дымка ложится на горизонт ОДИНАКОВО
            // при любой дальности: начало — треть пути до края, набор — чуть дальше края.
            var mc = net.minecraft.client.Minecraft.getInstance();
            float farBlocks = (mc.options != null ? mc.options.renderDistance().get() : 12) * 16f;
            // M8.134b: в Энде/Аду дымка — ГУСТАЯ и БЛИЗКАЯ, АБСОЛЮТНЫМИ дистанциями (не от
            // прорисовки): на референсе Eclipse Энд утоплен в лавандовой мгле, дальний террейн
            // растворяется. Привязка к прорисовке давала жидкую далёкую дымку — «плоско» (репорт).
            float fogStart, fogLen, fogDens;
            if (dimension == 2)      { fogStart = 10f; fogLen = 80f; fogDens = 0.82f; }   // Энд: густая лавандовая мгла
            else if (dimension == 1) { fogStart = 5f;  fogLen = 45f; fogDens = 0.88f; }   // Ад: плотный близкий туман
            else { fogStart = farBlocks * 0.35f; fogLen = farBlocks * 1.40f; fogDens = 0.40f * (cfg.fogDensity / 100f); }
            camMap.putFloat(cfgOff + 16, fogStart)                          // начало тумана
                  .putFloat(cfgOff + 20, fogLen)                            // длина набора плотности
                  .putFloat(cfgOff + 24, fogDens)                           // потолок плотности
                  .putFloat(cfgOff + 28, cfg.torchBrightness / 100f);
            camMap.putFloat(cfgOff + 32, cfg.emissiveBrightness / 100f)
                  .putFloat(cfgOff + 36, cfg.minLight / 100f)
                  .putFloat(cfgOff + 40, cfg.starsBrightness / 100f)
                  .putFloat(cfgOff + 44, aurora);   // северное сияние: снежный биом (Java, плавно)

            // КАЧЕСТВО ТРАССИРОВКИ = ДАЛЬНОСТЬ ЛУЧЕЙ. Луч на 4096 блоков обходит всё дерево BVH;
            // обрезанный до 128 — только ближние ветви. Дальняя тень всё равно тонет в тумане,
            // поэтому картинка почти та же, а работы кратно меньше.
            // ⚠️ «УЛЬТРА» = В ТОЧНОСТИ ТО, ЧТО БЫЛО ЗАШИТО ДО НАСТРОЕК (4096/4096/64, полные облака),
            // и оно же по умолчанию. Иначе новая сборка молча поменяла бы пользователю картинку и
            // FPS, хотя он не тронул ни одной ручки: все наши замеры и «одобрено глазами» — отсюда.
            int q = Math.min(Math.max(cfg.rtQuality, 0), 3);
            float shadowDist = switch (q) { case 0 -> 96f;  case 1 -> 256f; case 2 -> 1024f; default -> 4096f; };
            float reflDist   = switch (q) { case 0 -> 128f; case 1 -> 512f; case 2 -> 2048f; default -> 4096f; };
            float ambDist    = switch (q) { case 0 -> 24f;  case 1 -> 40f;  case 2 -> 56f;   default -> 64f;  };
            float cloudQ     = q <= 1 ? 1f : 0f;   // 1 = дешёвый марш (без эрозии), 0 = полный
            camMap.putFloat(cfgOff + 48, shadowDist)
                  .putFloat(cfgOff + 52, reflDist)
                  .putFloat(cfgOff + 56, ambDist)
                  .putFloat(cfgOff + 60, cloudQ);
            // rtCfg5.x — альфа-метки Actually 3D Stuff (M8.126; детект на перезагрузке ресурсов)
            camMap.putFloat(cfgOff + 64, alphaTagPack)
                  .putFloat(cfgOff + 68, (float) dimension)   // M8.130: 0 верхний, 1 Ад, 2 Энд
                  .putFloat(cfgOff + 72, endPortalCX).putFloat(cfgOff + 76, endPortalCZ);  // M8.144: центр глади портала Энда (XZ)
            // M8.133: rtCfg6 — цвет тумана Ада по биому (наша палитра); w = Y глади портала Энда (M8.144, sentinel<-1e8 = нет)
            camMap.putFloat(cfgOff + 80, netherFogR).putFloat(cfgOff + 84, netherFogG)
                  .putFloat(cfgOff + 88, netherFogB).putFloat(cfgOff + 92, endPortalY);
            // M8.146: rtCfg7 — осадки в отдельном BLAS: индекс их инстанса (шейдер опознаёт попадание)
            // + слот их текстуры (у осадков она одна на кадр, per-quad quadTex им не нужен).
            camMap.putFloat(cfgOff + 96,  (float) RtWorld.weatherInstanceIdx)
                  .putFloat(cfgOff + 100, (float) RtEntities.weatherSlot())
                  .putFloat(cfgOff + 104, 0f).putFloat(cfgOff + 108, 0f);

            // M8.153 rtCfg8: НАЧАЛО решётки объёма света (мировые блоки) + признак готовности.
            // Пока решётка не запечена (первые кадры после входа в мир, смена измерения), w = 0 и
            // шейдер идёт прежним путём по буферу источников — переход незаметен.
            boolean volOk = RtLightVolume.valid();
            camMap.putFloat(cfgOff + 112, (float) RtLightVolume.originXf())
                  .putFloat(cfgOff + 116, (float) RtLightVolume.originYf())
                  .putFloat(cfgOff + 120, (float) RtLightVolume.originZf())
                  .putFloat(cfgOff + 124, volOk ? 1f : 0f);

            VkDescriptorBufferInfo.Buffer camInfo = VkDescriptorBufferInfo.calloc(1, stack);
            // M8.126: +80, а не +64 — добавился rtCfg5 (диапазон обязан покрывать ВЕСЬ cam-блок,
            // иначе чтение rtCfg5 упрётся в границу дескриптора и вернёт мусор)
            // M8.146: +16 Б под rtCfg7 (осадки) -> cfg-блок теперь 7 vec4 = 112 Б.
            // ⚠️ Диапазон обязан покрывать ВЕСЬ cam-блок, иначе чтение rtCfg7 упрётся в границу и вернёт мусор.
            // M8.153: диапазон вырос на 16 байт — добавился rtCfg8 (начало решётки объёма света)
            camInfo.get(0).buffer(camUbo.buffer).offset(camOff).range(96 + OUTLINE_MAX_EDGES * 32 + 128);

            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(23, stack);   // M8.162: +PBR-карты пака
            w.get(12).sType$Default().dstSet(descSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(outInfo);
            w.get(13).sType$Default().dstSet(descSet).dstBinding(13)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(camInfo);
            for (int i = 0; i < guides.length; i++) {
                VkDescriptorImageInfo.Buffer gi = VkDescriptorImageInfo.calloc(1, stack);
                gi.get(0).imageView(guides[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w.get(14 + i).sType$Default().dstSet(descSet).dstBinding(14 + i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(gi);
            }
            w.get(0).sType$Default().dstSet(descSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).pNext(asw.address());
            w.get(1).sType$Default().dstSet(descSet).dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(tableInfo);
            w.get(2).sType$Default().dstSet(descSet).dstBinding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfo);
            w.get(3).sType$Default().dstSet(descSet).dstBinding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(RtEntities.MAX_TEX).pImageInfo(entInfos);
            w.get(4).sType$Default().dstSet(descSet).dstBinding(5)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(quadTexInfo);
            w.get(5).sType$Default().dstSet(descSet).dstBinding(6)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(lightInfo);
            w.get(6).sType$Default().dstSet(descSet).dstBinding(7)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(noiseInfo);
            w.get(7).sType$Default().dstSet(descSet).dstBinding(8)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(crackInfo);
            w.get(8).sType$Default().dstSet(descSet).dstBinding(9)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(moonInfo);
            w.get(9).sType$Default().dstSet(descSet).dstBinding(10)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(rainInfo);
            w.get(10).sType$Default().dstSet(descSet).dstBinding(11)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(rippleInfo);
            w.get(11).sType$Default().dstSet(descSet).dstBinding(12)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(matInfo);
            // M8.153 ОБЪЁМ ЦВЕТА СВЕТА. Буфер заводится по требованию и живёт всю сессию, поэтому
            // привязка всегда указывает на живую память; читать её или нет, шейдер решает по
            // флагу готовности в rtCfg8.w (пока решётка не запечена — идёт старым путём).
            VkDescriptorBufferInfo.Buffer volInfo = VkDescriptorBufferInfo.calloc(1, stack);
            volInfo.get(0).buffer(RtLightVolume.bufferHandle()).offset(0).range(VK_WHOLE_SIZE);
            w.get(20).sType$Default().dstSet(descSet).dstBinding(20)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(volInfo);
            // M8.162: PBR-карты пака. Биндим всегда (заглушка безвредна), сэмплит их шейдер только
            // с #define RT_PBR — он появляется, когда карты реально собраны (см. pipelineDefines).
            w.get(21).sType$Default().dstSet(descSet).dstBinding(21)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(pbrNInfo);
            w.get(22).sType$Default().dstSet(descSet).dstBinding(22)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(pbrSInfo);
            vkUpdateDescriptorSets(device, w, null);

            ByteBuffer push = stack.malloc(256);
            push.putFloat(0, (float) camOx).putFloat(4, (float) camOy).putFloat(8, (float) camOz).putFloat(12, (float) W);
            push.putFloat(16, fX).putFloat(20, fY).putFloat(24, fZ).putFloat(28, (float) H);
            push.putFloat(32, uX).putFloat(36, uY).putFloat(40, uZ).putFloat(44, tanY);
            push.putFloat(48, lX).putFloat(52, lY).putFloat(56, lZ).putFloat(60, tanX);
            // params.zw = ФИКСИРОВАННЫЕ тангенсы FOV руки (вьюмодел). tan(35°)=FOV 70 по
            // вертикали (как в ванилле), аспект берём из текущей проекции -> рука не
            // растягивается при большом мировом FOV (плечо не торчит при спринте).
            float handTanY = 0.70021f;   // tan(radians(70)/2)
            float handTanX = tanY != 0 ? tanX * (handTanY / tanY) : handTanY;
            push.putFloat(64, underwater).putFloat(68, animTime).putFloat(72, handTanX).putFloat(76, handTanY);
            // wparams: x=Y глади, y=block-light у игрока (для руки), z=свет предмета в руке,
            //          w=ЧИСЛО цветных источников света в буфере (binding 6)
            push.putFloat(80, waterSurfaceY).putFloat(84, playerBlockLight)
                .putFloat(88, heldLight).putFloat(92, (float) lightCount);
            // handCol: ЦВЕТ света предмета в руке (факел тёплый, факел душ голубой, медный зелёный)
            push.putFloat(96, RtLights.heldR()).putFloat(100, RtLights.heldG())
                .putFloat(104, RtLights.heldB()).putFloat(108, eyeInLava);   // handCol.w = глаз в лаве
            // outline: КОРОБКИ ФОРМЫ блока под прицелом (ванильная обводка рисуется в RT)
            // ⚠️ Сами коробки обводки в push БОЛЬШЕ НЕ ПИШЕМ — они уехали в UBO (их стало восемь).
            // Место obox[0..1] в push теперь занимает пост под свои поля (режим, near/far, размеры).
            push.putFloat(112, (float) oCount).putFloat(116, fxOnFire).putFloat(120, fxEnterW).putFloat(124, fxExitW);
            // M8.18 НЕБО: направление на солнце + время суток
            push.putFloat(176, sunX).putFloat(180, sunY).putFloat(184, sunZ).putFloat(188, timeOfDay);
            // moonPhase (целая часть) + wetness (дробная, для луж): push-константы переполнены
            push.putFloat(240, moonPhase + Math.min(Math.max(wetness, 0f), 0.98f))
                .putFloat(244, cloudTime).putFloat(248, cloudCoverScaled()).putFloat(252, rainAmt);
            // M8.10/M8.12 эффекты камеры (униформы Eclipse)
            push.putFloat(192, fxBlind).putFloat(196, fxNight).putFloat(200, fxPoison).putFloat(204, fxWither);
            push.putFloat(208, fxNausea).putFloat(212, fxDark).putFloat(216, fxOneHeart).putFloat(220, fxThreeHeart);
            push.putFloat(224, fxMinorDmg).putFloat(228, fxCritDmg).putFloat(232, fxExitLava).putFloat(236, fxBreak);

            // Замер GPU: читаем результаты ПРОШЛОГО прохода этого слота, потом переоткрываем метки.
            readGpuTimes(frameIdx);
            int tsBase = frameIdx * TS_PER_FRAME;
            if (tsPool != VK_NULL_HANDLE) {
                vkCmdResetQueryPool(frameCmd, tsPool, tsBase, TS_PER_FRAME);
                // ⚠️ СТАРТОВАЯ МЕТКА — ТОЖЕ В КОНЦЕ КОНВЕЙЕРА (BOTTOM_OF_PIPE), А НЕ В НАЧАЛЕ.
                // С TOP_OF_PIPE метка пишется, как только GPU ДОБРАЛСЯ до команды, а не когда
                // закончил предыдущую работу. А до нас в том же буфере идут: растеризация мира,
                // сборка BLAS сущностей, пересборка TLAS. Интервал «трассировки» включал их все —
                // и потому не менялся ни от одного тумблера шейдера: я мерил ВЕСЬ КАДР, а не проход.
                vkCmdWriteTimestamp(frameCmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, tsPool, tsBase);
            }

            vkCmdBindPipeline(frameCmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(frameCmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descSet), null);
            vkCmdPushConstants(frameCmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(frameCmd, (W + 7) / 8, (H + 7) / 8, 1);
            if (tsPool != VK_NULL_HANDLE)
                vkCmdWriteTimestamp(frameCmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, tsPool, tsBase + 1);

            // ================== DLSS RAY RECONSTRUCTION ==================
            // Шумный HDR + guide-буферы -> чистый кадр в полном разрешении. Это и есть тот самый
            // денойзер, ради которого затевались guide-буферы и jitter.
            boolean dlssDone = false;
            RtImage up = RtPost.upscaledImage();
            if (RtDlss.ready() && RtDlss.enabled && up != null) {
                // Все ресурсы — в GENERAL: NGX сам решает, что читать, а что писать.
                for (RtImage img : new RtImage[]{hdr, guides[0], guides[1], guides[2], guides[3], guides[4], guides[5]})
                    img.transition(stack, frameCmd, VK_IMAGE_LAYOUT_GENERAL,
                            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                up.transition(stack, frameCmd, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                long[] imgs = {
                        hdr.image, hdr.view,
                        guides[0].image, guides[0].view,   // глубина
                        guides[1].image, guides[1].view,   // векторы движения
                        guides[2].image, guides[2].view,   // нормаль + шероховатость (в w)
                        guides[3].image, guides[3].view,   // диффузное альбедо
                        guides[4].image, guides[4].view,   // зеркальное альбедо
                        up.image, up.view,
                        guides[5].image, guides[5].view,   // маска реактивности (вода)
                };
                int[] fmts = {hdr.format, guides[0].format, guides[1].format, guides[2].format,
                              guides[3].format, guides[4].format, up.format, guides[5].format};

                // ⚠️ Знак jitter: мы смещаем ЛУЧ на +jitter, а DLSS ждёт смещение в своей конвенции.
                // Ошибись знаком — она накопит субпиксельные сэмплы «не туда»: дрожание не погасится,
                // шум останется. Переключается на лету (F7), чтобы проверить глазами, а не гадать.
                dlssDone = RtDlss.evaluate(frameCmd.address(), imgs, fmts, W, H, up.width, up.height,
                                           jx * RtDlss.jitterSignX(), jy * RtDlss.jitterSignY(),
                                           !prevValid, camMatrices());
            }
            // ================== ВСТРОЕННЫЙ ДЕНОЙЗЕР (M8.158) ==================
            // Наша временная очистка вместо DLSS — то, ради чего мод перестаёт быть «зелёным».
            // Она НЕ увеличивает кадр, поэтому включается только когда апскейлер выключен и
            // разрешение трассировки равно экранному (Config.upscalingActive это уже обеспечивает,
            // а размеры сверяем — на случай кадра, где смена разрешения ещё не доехала).
            else if (Initializer.CONFIG.rtDenoiserOn
                    && Initializer.CONFIG.rtDenoiser == net.vulkanmod.config.Config.DENOISER_BUILTIN
                    && up != null) {
                // Возвращает false: чистый кадр лежит в hdr (рендерное разрешение), поднимет его пост.
                dlssDone = RtDenoise.record(frameCmd, stack, hdr, guides, up, W, H, !prevValid);
            }

            // ПОСТ-ПРОХОД: эффекты + AgX + гамма (HDR -> LDR). Барьеры образов ставит он сам.
            // ⚠️ obox посту не нужен — переиспользуем его под служебные поля:
            // obox[0] = (режим отладки, near, far, работает ли DLSS), obox[1] = размеры (рендер, экран).
            push.putFloat(128, (float) RtPost.debugView).putFloat(132, NEAR).putFloat(136, FAR)
                .putFloat(140, dlssDone ? 1f : 0f);
            push.putFloat(144, (float) W).putFloat(148, (float) H)
                .putFloat(152, (float) (up != null ? up.width : W))
                .putFloat(156, (float) (up != null ? up.height : H));
            if (tsPool != VK_NULL_HANDLE)
                vkCmdWriteTimestamp(frameCmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, tsPool, tsBase + 2);

            RtPost.record(frameCmd, push, dlssDone);

            if (tsPool != VK_NULL_HANDLE) {
                vkCmdWriteTimestamp(frameCmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, tsPool, tsBase + 3);
                tsWritten[frameIdx] = true;
            }

            // Камера этого кадра становится «прошлой» для следующего — так считаются векторы движения
            pOx = (float) camOx; pOy = (float) camOy; pOz = (float) camOz;
            pfX = fX; pfY = fY; pfZ = fZ; puX = uX; puY = uY; puZ = uZ; plX = lX; plY = lY; plZ = lZ;
            pTanX = tanX; pTanY = tanY;
        }
    }

    private static void encodeAsync(int[] px) {
        Thread t = new Thread(() -> {
            try {
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, W, H, px, 0, W);
                File f = new File("rt-snapshot.png");
                ImageIO.write(img, "png", f);
                Initializer.LOGGER.info("[RT] RT frame saved: {}", f.getAbsolutePath());
            } catch (Throwable e) {
                Initializer.LOGGER.error("[RT] writing the PNG failed: ", e);
            }
        }, "RT-snapshot-encode");
        t.setDaemon(true);
        t.start();
    }

    private static long createShaderModule(VkDevice device, String source) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_3, VK_API_VERSION_1_3);
            // ⚠️ ИСХОДНИК КЛАДЁМ В КУЧУ, А НЕ В СТЕК. Перегрузка shaderc_compile_into_spv со String
            // кодирует текст через MemoryStack, а он ФИКСИРОВАННЫЙ (64 КБ у LWJGL по умолчанию).
            // Наш GLSL этот предел перерос, и сборка пайплайна падала с «OutOfMemoryError: Out of
            // stack space» — трассировка молча отваливалась в растровый фолбэк (M8.156e). Тот же
            // порог в 64 КБ мы недавно ловили с другой стороны, на строковой константе class-файла.
            // memUTF8 берёт память вне стека, поэтому шейдер может расти дальше без сюрпризов.
            ByteBuffer srcBuf  = MemoryUtil.memUTF8(source, false);   // длину shaderc берёт из буфера
            ByteBuffer nameBuf = MemoryUtil.memUTF8("world_snapshot.comp");
            ByteBuffer entryBuf = MemoryUtil.memUTF8("main");
            long res;
            try {
                res = shaderc_compile_into_spv(compiler, srcBuf, shaderc_compute_shader,
                        nameBuf, entryBuf, options);
            } finally {
                MemoryUtil.memFree(srcBuf);
                MemoryUtil.memFree(nameBuf);
                MemoryUtil.memFree(entryBuf);
            }
            try {
                if (shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success)
                    throw new RuntimeException("shaderc: " + shaderc_result_get_error_message(res));
                ByteBuffer spirv = shaderc_result_get_bytes(res);
                try (MemoryStack stack = stackPush()) {
                    VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                    LongBuffer pMod = stack.mallocLong(1);
                    check(vkCreateShaderModule(device, ci, null, pMod), "shader module");
                    return pMod.get(0);
                }
            } finally {
                shaderc_result_release(res);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new RuntimeException("[RT] Vulkan error in '" + what + "': code " + result);
    }
}
