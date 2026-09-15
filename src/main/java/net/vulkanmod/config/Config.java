package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.vulkanmod.config.video.VideoModeSet;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class Config {
    public VideoModeSet.VideoMode videoMode = VideoModeSet.getDummy().getVideoMode();
    public int windowMode = 0;

    public int advCulling = 2;
    public boolean indirectDraw = true;

    public boolean uniqueOpaqueLayer = true;
    public boolean entityCulling = true;
    public int device = -1;
    public boolean useWayland = false;

    public int ambientOcclusion = 1;
    public int frameQueueSize = 2;
    public int builderThreads = 0;

    public boolean backFaceCulling = true;
    public boolean textureAnimations = true;

    // === RT PATCH === НАСТРОЙКИ ТРАССИРОВКИ (страница «Трассировка» в настройках графики).
    // ⚠️ Каждая ручка обязана что-то реально выключать в шейдере или в Java. Настройка, которая
    // ничего не меняет, — обман: пользователь крутит её и верит, что стало быстрее.
    public boolean rtEnabled = true;        // мастер-тумблер: RT-картинка вместо растеризации
    // M8.122: «Шейдеры» — наш пост (AgX + экранные эффекты) поверх ванильного растра, когда RT
    // выключен. Иерархия: RT ВКЛ подразумевает шейдеры; оба ВЫКЛ = чистый VulkanMod-растр.
    public boolean shadersEnabled = true;
    public boolean rtShadows = true;        // теневой луч к солнцу
    public int rtAmbientRays = 4;           // лучей небесного света на пиксель: 0 (выкл) / 2 / 4
    public boolean rtReflections = true;    // отражения (вода, стекло, металлы)
    public boolean rtClouds = true;         // объёмные облака (марш по лучу — дорого)
    public boolean rtGodRays = true;        // объёмные лучи солнца (марш с теневым лучом на шаг — дорого)
    public boolean rtColoredLights = true;  // цветной свет от блоков (факелы, лава, фонари)
    // M8.162: PBR-МАТЕРИАЛЫ ИЗ РЕСУРСПАКОВ (labPBR _n/_s). Паки AVPBR/SPBR и им подобные кладут рядом
    // с текстурой нормаль и спеку — по ним трассировка получает РЕЛЬЕФ, шероховатость, металличность
    // и эмиссию КАЖДОГО блока атласа. Выключатель существует не «на всякий случай»: карты строятся
    // в память (две текстуры размером с атлас, на HD-паках это сотни мегабайт), и владелец слабой
    // карты вправе от них отказаться, оставшись на нашей таблице материалов.
    public boolean rtPbr = true;
    // M8.156 ДЕНОЙЗЕР И АПСКЕЙЛЕР — ДВЕ РАЗНЫЕ РАБОТЫ, поэтому две пары «тумблер + выбор»
    // (просьба пользователя, «как в Cyberpunk»). Путаница идёт от того, что DLSS Ray
    // Reconstruction делает обе работы разом: чистит шум И увеличивает. У прочих это разные
    // инструменты: FSR 3.1 только увеличивает (шума НЕ убирает), наш временной денойзер только
    // чистит. Поэтому FSR в списке денойзеров стоять не может — выбравший его получил бы гладко
    // растянутое зерно и решил, что мод сломан.
    public boolean rtUpscalerOn = true;         // поднимать ли кадр до разрешения экрана
    public int     rtUpscaler   = UPSCALER_DLSS;
    public boolean rtDenoiserOn = true;         // убирать ли зерно трассировки
    public int     rtDenoiser   = DENOISER_DLSS;

    public static final int UPSCALER_DLSS  = 0;  // DLSS (внутри Ray Reconstruction), только NVIDIA
    public static final int UPSCALER_FSR   = 1;  // FSR 3.1 — в работе
    // ⚠️ БАЗОВЫЙ — НЕ РОВНЯ ОСТАЛЬНЫМ, и это отражено в названии. DLSS и FSR — временные
    // апскейлеры, восстанавливающие кадр по накопленным субпиксельным сэмплам. Здесь же простое
    // билинейное увеличение с подчёркиванием контуров (RtPost): работает на любом железе и стоит
    // копейки, но деталей не добавляет. Его задача — вернуть экономию лучей там, где DLSS нет.
    public static final int UPSCALER_BASIC = 2;

    public static final int DENOISER_DLSS    = 0;  // DLSS Ray Reconstruction (NVIDIA)
    public static final int DENOISER_BUILTIN = 1;  // наш временной — в работе (для AMD/Intel)
    public static final int DENOISER_FSR_RR  = 2;  // FSR Ray Regeneration — в работе (только RDNA 4)

    /** Реализован ли выбор СЕГОДНЯ. Нереализованный ведёт себя как выключенный — честнее, чем
     *  молча подменять его на DLSS: человек сразу видит зерно и понимает, что очистки нет. */
    public static boolean denoiserImplemented(int d) { return d == DENOISER_DLSS || d == DENOISER_BUILTIN; }
    public static boolean upscalerImplemented(int u) { return u == UPSCALER_DLSS; }

    /** Нужен ли живой проход DLSS.
     *  ⚠️ ТОЛЬКО от ДЕНОЙЗЕРА, и это не упрощение. У нас создаётся фича Ray Reconstruction, а она
     *  ВСЕГДА чистит — это вся её суть, отключить чистку нельзя. Значит «денойзер ВЫКЛ, апскейлер
     *  DLSS» запустил бы RR, и он всё равно чистил бы: интерфейс говорил бы одно, а движок делал
     *  другое. Для чистого апскейла нужна ОТДЕЛЬНАЯ фича DLSS Super Resolution — её мы не создаём.
     *  Поэтому сегодня апскейл DLSS существует лишь как побочный эффект включённого денойзера. */
    public boolean dlssActive() {
        return rtDenoiserOn && rtDenoiser == DENOISER_DLSS;
    }

    /** M8.156b: АПСКЕЙЛЕР DLSS ПОДЧИНЯЕТ СЕБЕ ДЕНОЙЗЕР — и это асимметрия самого железа, а не
     *  наша прихоть. Ray Reconstruction слит: выбрав его апскейлером, ты неизбежно получаешь и
     *  его чистку, отказаться нельзя. Обратное же развязывается свободно: RR умеет работать при
     *  РАВНЫХ разрешениях («почистить, размер не трогать»), а увеличить потом может кто угодно —
     *  хоть FSR. Поэтому «денойз DLSS + апскейл FSR» разрешено, а «апскейл DLSS + денойз FSR» нет. */
    public boolean upscalerOwnsDenoiser() {
        return rtUpscalerOn && rtUpscaler == UPSCALER_DLSS;
    }

    /** Реально ли поднимается разрешение. Масштаб < 100% осмыслен, только когда есть чем
     *  восстанавливать кадр; сегодня это умеет лишь проход DLSS. */
    public boolean upscalingActive() {
        if (!rtUpscalerOn) return false;
        return switch (rtUpscaler) {
            case UPSCALER_DLSS  -> dlssActive();   // апскейл живёт внутри прохода Ray Reconstruction
            case UPSCALER_BASIC -> true;           // билинейка с подчёркиванием в RtPost, работает всегда
            default -> false;                      // FSR 3.1 — в работе, масштаб держим 100%
        };
    }

    /** ⚠️ УСТАРЕЛО, оставлено ТОЛЬКО для переноса старых конфигов (см. Initializer). Живой
     *  рубильник тракта — RtDlss.enabled, он выводится из dlssActive(). Новый код это не читает. */
    public boolean dlss = true;
    public int renderScale = 67;            // РАЗРЕШЕНИЕ ТРАССИРОВКИ, % от экрана (с него DLSS восстанавливает кадр)
    public int rtQuality = 3;               // КАЧЕСТВО: 0 низкое, 1 среднее, 2 высокое, 3 ультра
    // ⚠️ По умолчанию УЛЬТРА — это ровно то, что было зашито в коде до появления настроек.
    // Поставь я «высокое», новая сборка тихо поменяла бы картинку и FPS без ведома игрока.

    // === RT PATCH === СТРАНИЦА «ИЗОБРАЖЕНИЕ» — порт ручек Eclipse (Post_Processing / Color_Processing).
    // Всё в процентах: так их показывает UI, а шейдер уже переводит в свои единицы (см. RtPost).
    public int motionBlur = 50;    // размытие в движении: доля вектора движения кадра (0 = выкл)
    public int vignette = 0;       // виньетка: затемнение углов
    public int filmGrain = 0;      // зерно плёнки
    public int chromatic = 0;      // хроматическая аберрация линзы
    public int exposureBias = 0;   // сдвиг экспозиции, десятые доли стопа (-20..20 => -2..+2 EV)
    public int saturation = 100;   // насыщенность (100 = как снято)
    public int lensFlare = 60;     // блики линзы от солнца/луны (гасятся, если светило загорожено)

    // === RT PATCH === СТРАНИЦА «МИР» — порт ручек Eclipse (Torch_Colors, Ambient_Colors, Fog, Clouds).
    // Множители на константы, которые у нас УЖЕ есть в шейдере (см. RtSnapshot: rtCfg2/rtCfg3).
    public int fogDensity = 100;          // плотность дымки на горизонте (границы — от дальности прорисовки)
    public int torchBrightness = 100;     // яркость света факелов (Eclipse: TORCH_AMOUNT)
    public int emissiveBrightness = 100;  // яркость светящихся текселей (Eclipse: Emissive_Brightness)
    public int minLight = 100;            // минимальное освещение: пол яркости в полной темноте
    public int starsBrightness = 100;     // яркость звёзд (Eclipse: STARS_BRIGHTNESS)
    public int cloudAmount = 100;         // количество облаков (тип погоды не меняется)

    public void write() {
        if (!Files.exists(CONFIG_PATH.getParent())) {
            try {
                Files.createDirectories(CONFIG_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path CONFIG_PATH;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            // ⚠️ STATIC ОБЯЗАТЕЛЕН. excludeFieldsWithModifiers ЗАМЕНЯЕТ список по умолчанию
            // (там были TRANSIENT и STATIC), а не дополняет его. Без STATIC Gson лезет писать в
            // ЛЮБОЕ статическое поле класса — и первая же добавленная сюда константа роняет игру
            // на старте: «Cannot set value of static final field» (M8.156d, краш при загрузке
            // конфига). Константы в Config класть можно только с этим исключением.
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .create();

    public static Config load(Path path) {
        Config config;
        Config.CONFIG_PATH = path;

        if (Files.exists(path)) {
            try (FileReader fileReader = new FileReader(path.toFile())) {
                config = GSON.fromJson(fileReader, Config.class);
            } catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
            }
        }
        else {
            config = new Config();
            config.write();
        }

        return config;
    }
}
