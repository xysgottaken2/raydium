package net.vulkanmod.config.option;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.gui.*;
import net.vulkanmod.config.video.*;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.light.LightMode;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public abstract class Options {

    public static boolean fullscreenDirty = false;

    private static final Config config = Initializer.CONFIG;
    private static final Minecraft minecraft = Minecraft.getInstance();
    private static final Window window = minecraft.getWindow();
    private static final net.minecraft.client.Options mcOptions = minecraft.options;

    // === RT PATCH (M8.122e) === живая связь страницы «Графика» с тумблером «Шейдеры»:
    // пока шейдеры ВКЛ, ванильный вид (пресет/частицы/облака/листва и т.д.) на картинку не
    // влияет — наш конвейер рисует своё, и эти ручки гаснут. Список наполняет getGraphicsOpts,
    // а тумблер обновляет active без Apply. Фильтрация текстур — исключение (сэмплер атласа
    // действует всегда) и уезжает наверх, к тумблерам.
    private static Option<Boolean> shadersToggle;                 // ставится в getRayTracingOpts
    private static final List<Option<?>> shaderGated = new ArrayList<>();
    private static Option<?>[] texFilterPair = new Option<?>[0];  // экспорт из getGraphicsOpts
    private static boolean shadersLiveOn() {
        return shadersToggle != null ? shadersToggle.getNewValue() : config.shadersEnabled;
    }

    public static List<OptionPage> getOptionPages() {
        List<OptionPage> optionPages = new ArrayList<>();

        OptionPage page = new OptionPage(
                Component.translatable("vulkanmod.options.pages.video").getString(),
                Options.getVideoOpts()
        );
        optionPages.add(page);

        // === RT PATCH (M8.122d) === Настройки трассировки ЖИВУТ НА СТРАНИЦЕ «ГРАФИКА» (идея
        // пользователя): сверху — «Шейдеры»/«Трассировка» со своими ручками, ниже — ванильные
        // настройки VulkanMod именованными блоками. Так тумблер «Шейдеры» виден и на железе без
        // RT (раньше страница «Трассировка» целиком пряталась за rayTracingSupported — растровый
        // фолбэк было бы не включить), а ручка RT на таком железе просто заблокирована.
        OptionBlock[] rtBlocks = Options.getRayTracingOpts();
        OptionBlock[] rasterBlocks = Options.getGraphicsOpts();   // наполняет shaderGated и texFilterPair
        // M8.122e: фильтрация текстур — наверх, к тумблерам (действует всегда, просьба пользователя)
        Option<?>[] topOpts = new Option<?>[rtBlocks[0].options().length + texFilterPair.length];
        System.arraycopy(rtBlocks[0].options(), 0, topOpts, 0, rtBlocks[0].options().length);
        System.arraycopy(texFilterPair, 0, topOpts, rtBlocks[0].options().length, texFilterPair.length);
        rtBlocks[0] = new OptionBlock(rtBlocks[0].title(), topOpts);
        // Ванильным блокам — честные заголовки: чанки действуют всегда (и в RT), вид — растровый.
        if (rasterBlocks.length >= 3) {
            rasterBlocks[0] = new OptionBlock(
                    Component.translatable("vulkanmod.options.graphics.block.common").getString(),
                    rasterBlocks[0].options());
            rasterBlocks[1] = new OptionBlock(
                    Component.translatable("vulkanmod.options.graphics.block.raster").getString(),
                    rasterBlocks[1].options());
            rasterBlocks[2] = new OptionBlock(
                    Component.translatable("vulkanmod.options.graphics.block.raster2").getString(),
                    rasterBlocks[2].options());
        }
        // M8.156 ПОРЯДОК (просьба пользователя): ЧАНКИ — НАВЕРХ, до наших шейдерных ручек.
        // Прорисовка и симуляция чанков действуют ВСЕГДА и влияют на кадры сильнее любой нашей
        // настройки, поэтому искать их под трассировкой противоестественно.
        OptionBlock[] gfxMerged = new OptionBlock[rtBlocks.length + rasterBlocks.length];
        int gm = 0;
        gfxMerged[gm++] = rasterBlocks[0];                       // чанки: прорисовка/симуляция
        for (OptionBlock rb : rtBlocks) gfxMerged[gm++] = rb;    // наши: шейдеры и трассировка
        for (int i = 1; i < rasterBlocks.length; i++) gfxMerged[gm++] = rasterBlocks[i];
        page = new OptionPage(
                Component.translatable("vulkanmod.options.pages.graphics").getString(),
                gfxMerged
        );
        optionPages.add(page);

        page = new OptionPage(
                Component.translatable("vulkanmod.options.pages.optimizations").getString(),
                Options.getOptimizationOpts()
        );
        optionPages.add(page);

        page = new OptionPage(
                Component.translatable("vulkanmod.options.pages.other").getString(),
                Options.getOtherOpts()
        );
        optionPages.add(page);

        // === RT PATCH === страницы «Изображение»/«Мир» — только если железо умеет RT.
        // Сами тумблеры и ручки трассировки переехали на страницу «Графика» (M8.122d, выше).
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) {
            page = new OptionPage(
                    Component.translatable("vulkanmod.options.pages.image").getString(),
                    Options.getImageOpts()
            );
            optionPages.add(page);

            page = new OptionPage(
                    Component.translatable("vulkanmod.options.pages.world").getString(),
                    Options.getRtWorldOpts()
            );
            optionPages.add(page);
        }

        return optionPages;
    }

    public static OptionBlock[] getVideoOpts() {
        VideoModeManager.checkConfigVideoMode(config);
        VideoModeManager.selectBestMonitor(window);
        var resolutions = VideoModeManager.getVideoResolutions();

        var videoMode = config.videoMode;
        var videoModeSet = VideoModeManager.getVideoModeSet(videoMode);

        if (videoModeSet == null) {
            videoModeSet = resolutions[resolutions.length - 1];
            videoMode = videoModeSet.getVideoMode();
        }

        VideoModeManager.selectedVideoMode = videoMode;
        var refreshRates = videoModeSet.getRefreshRates();

        var windowModeOption = new CyclingOption<>(Component.translatable("vulkanmod.options.windowMode"),
                                                   WindowMode.values(),
                                                   value -> {
                                                       boolean exclusiveFullscreen = value == WindowMode.EXCLUSIVE_FULLSCREEN;
                                                       mcOptions.fullscreen()
                                                                       .set(exclusiveFullscreen);

                                                       config.windowMode = value.mode;
                                                       fullscreenDirty = true;
                                                   },
                                                   () -> WindowMode.fromValue(config.windowMode))
                .setTranslator(value -> Component.translatable(WindowMode.getComponentName(value)));

        CyclingOption<Integer> refreshRateOption = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.refreshRate"),
                refreshRates.toArray(new Integer[0]),
                (value) -> {
                    VideoModeManager.selectedVideoMode.refreshRate = value;
                    VideoModeManager.applySelectedVideoMode();

                    if (mcOptions.fullscreen().get()) {
                        fullscreenDirty = true;
                    }
                },
                () -> VideoModeManager.selectedVideoMode.refreshRate)
                .setTranslator(refreshRate -> Component.nullToEmpty(refreshRate.toString()))
                .setActivationFn(() -> windowModeOption.getNewValue() == WindowMode.EXCLUSIVE_FULLSCREEN);

        Option<VideoModeSet> resolutionOption = new CyclingOption<>(
                Component.translatable("options.fullscreen.resolution"),
                resolutions,
                (value) -> {
                    VideoModeManager.selectedVideoMode = value.getVideoMode(refreshRateOption.getNewValue());
                    VideoModeManager.applySelectedVideoMode();

                    if (mcOptions.fullscreen().get()) {
                        fullscreenDirty = true;
                    }
                },
                () -> {
                    var selectedVideoMode = VideoModeManager.selectedVideoMode;
                    var selectedVideoModeSet = VideoModeManager.getVideoModeSet(selectedVideoMode);

                    return selectedVideoModeSet != null ? selectedVideoModeSet : VideoModeSet.getDummy();
                })
                .setTranslator(resolution -> Component.nullToEmpty(resolution.toString()))
                .setActivationFn(() -> windowModeOption.getNewValue() == WindowMode.EXCLUSIVE_FULLSCREEN);

        resolutionOption.setOnChange(() -> {
            VideoModeSet newSet = resolutionOption.getNewValue();
            Integer[] rates = newSet.getRefreshRates().toArray(new Integer[0]);
            refreshRateOption.setValues(rates);
            refreshRateOption.setNewValue(rates[rates.length - 1]);
        });

        windowModeOption.setOnChange(() -> {
            resolutionOption.updateActiveState();
            refreshRateOption.updateActiveState();
        });

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        windowModeOption,
                        resolutionOption,
                        refreshRateOption,
                        new RangeOption(Component.translatable("options.framerateLimit"),
                                10, 260, 10,
                                value -> Component.nullToEmpty(value == 260
                                        ? Component.translatable("options.framerateLimit.max").getString()
                                        : String.valueOf(value)),
                                value -> {
                                    mcOptions.framerateLimit().set(value);
                                    minecraft.getFramerateLimitTracker().setFramerateLimit(value);
                                },
                                () -> mcOptions.framerateLimit().get()),
                        new SwitchOption(Component.translatable("options.vsync"),
                                value -> {
                                    mcOptions.enableVsync().set(value);
                                    window.updateVsync(value);
                                },
                                () -> mcOptions.enableVsync().get()),
                        new CyclingOption<>(Component.translatable("options.inactivityFpsLimit"),
                                            InactivityFpsLimit.values(),
                                            value -> mcOptions.inactivityFpsLimit().set(value),
                                            () -> mcOptions.inactivityFpsLimit().get())
                                .setTranslator(InactivityFpsLimit::caption)
                }),
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.guiScale"),
                                0, Math.max(window.calculateScale(0, minecraft.isEnforceUnicode()), mcOptions.guiScale().get()), 1,
                                value -> Component.translatable(value == 0 ? "options.guiScale.auto" : String.valueOf(value)),
                                value -> {
                                    mcOptions.guiScale().set(value);
                                    minecraft.resizeDisplay();
                                },
                                () -> mcOptions.guiScale().get()),
                        new RangeOption(Component.translatable("options.gamma"),
                                0, 100, 1,
                                value -> Component.translatable(switch (value) {
                                    case 0 -> "options.gamma.min";
                                    case 50 -> "options.gamma.default";
                                    case 100 -> "options.gamma.max";
                                    default -> String.valueOf(value);
                                }),
                                value -> mcOptions.gamma().set(value * 0.01),
                                () -> (int) (mcOptions.gamma().get() * 100.0))
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.attackIndicator"),
                                            AttackIndicatorStatus.values(),
                                            value -> mcOptions.attackIndicator().set(value),
                                            () -> mcOptions.attackIndicator().get())
                                .setTranslator(AttackIndicatorStatus::caption),
                        new SwitchOption(Component.translatable("options.autosaveIndicator"),
                                value -> mcOptions.showAutosaveIndicator().set(value),
                                () -> mcOptions.showAutosaveIndicator().get())
                })
        };
    }

    /**
     * === RT PATCH === Страница «Трассировка».
     *
     * ⚠️ ПРАВИЛО: каждая ручка обязана выключать РЕАЛЬНУЮ работу — луч, марш, проход. Настройка,
     * которая ничего не отключает, врёт пользователю: он крутит её и верит, что стало быстрее.
     * Поэтому тут ровно то, что читает шейдер (cam.rtCfg) или проверяет Java.
     */
    public static OptionBlock[] getRayTracingOpts() {
        var rtOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.enabled"),
                value -> {
                    config.rtEnabled = value;
                    net.vulkanmod.vulkan.rt.RtScreen.enabled = value;
                    // ⚠️ Включили обратно — секции надо ПЕРЕСТРОИТЬ: пока RT был выключен, мы не
                    // строили для них BLAS, и мир для лучей пуст. Ванильный allChanged() заставляет
                    // игру перезалить чанки, а вместе с ними приедут и наши BLAS.
                    if (value && minecraft.levelRenderer != null) minecraft.levelRenderer.allChanged();
                },
                () -> config.rtEnabled)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.enabled.tooltip"))
                .setImpact(PerformanceImpact.HIGH);

        // M8.156 ДЕНОЙЗЕР И АПСКЕЙЛЕР РАЗДЕЛЕНЫ (просьба пользователя, «как в Cyberpunk»):
        // у каждого свой тумблер и свой выбор реализации. Это не косметика — работы разные.
        // DLSS Ray Reconstruction делает обе разом, а FSR 3.1 только увеличивает и шума НЕ
        // убирает, поэтому держать его в списке денойзеров было бы враньём.
        // Нереализованные пункты помечены «в работе» и ведут себя как выключенные: человек
        // видит зерно и понимает, что очистки нет (молча подменять их на DLSS — хуже).
        Runnable syncDlss = () -> net.vulkanmod.vulkan.rt.RtDlss.enabled = config.dlssActive();

        var upscalerOnOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.upscaler.on"),
                value -> { config.rtUpscalerOn = value; syncDlss.run(); },
                () -> config.rtUpscalerOn)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.upscaler.on.tooltip"));

        var upscalerOption = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.rt.upscaler"),
                new Integer[]{ Config.UPSCALER_DLSS, Config.UPSCALER_FSR, Config.UPSCALER_BASIC },
                value -> {
                    config.rtUpscaler = value;
                    // Выбор DLSS апскейлером ПРИНУДИТЕЛЬНО ставит его же денойзером: проход один
                    // и тот же, и отказаться от его чистки нельзя. Ручки денойзера ниже гаснут.
                    if (value == Config.UPSCALER_DLSS) {
                        config.rtDenoiserOn = true;
                        config.rtDenoiser   = Config.DENOISER_DLSS;
                    }
                    syncDlss.run();
                },
                () -> config.rtUpscaler)
                .setTranslator(v -> Component.translatable("vulkanmod.options.rt.upscaler." + v))
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.upscaler.tooltip"))
                .setImpact(PerformanceImpact.LOW);

        var denoiserOnOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.denoiser.on"),
                value -> { config.rtDenoiserOn = value; syncDlss.run(); },
                () -> config.rtDenoiserOn)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.denoiser.on.tooltip"));

        var denoiserOption = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.rt.denoiser"),
                new Integer[]{ Config.DENOISER_DLSS, Config.DENOISER_BUILTIN, Config.DENOISER_FSR_RR },
                value -> {
                    config.rtDenoiser = value;
                    // Уходя со DLSS, уводим и апскейлер: DLSS поднимает кадр ТОЛЬКО заодно со своим
                    // денойзером. Оставь мы его выбранным — масштаб молча вернулся бы к 100%, и
                    // ползунок разрешения врал бы (ровно это и случилось при первой проверке).
                    if (value != Config.DENOISER_DLSS && config.rtUpscaler == Config.UPSCALER_DLSS)
                        config.rtUpscaler = Config.UPSCALER_BASIC;
                    syncDlss.run();
                },
                () -> config.rtDenoiser)
                .setTranslator(v -> Component.translatable("vulkanmod.options.rt.denoiser." + v))
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.denoiser.tooltip"))
                .setImpact(PerformanceImpact.LOW);

        // РАЗРЕШЕНИЕ ТРАССИРОВКИ. Главный рычаг производительности: пикселей вчетверо меньше — лучей
        // вчетверо меньше. С DLSS кадр восстанавливается до полного, поэтому 50-67% почти незаметны.
        var scaleOption = new RangeOption(Component.translatable("vulkanmod.options.rt.scale"), 25, 100, 1,
                v -> config.renderScale = v, () -> config.renderScale)
                .setTranslator(v -> Component.literal(v + "%" + (v >= 100 ? " (DLAA)" : "")))
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.scale.tooltip"))
                .setImpact(PerformanceImpact.HIGH);

        // КАЧЕСТВО = ДАЛЬНОСТЬ ЛУЧЕЙ (тень/отражение/амбиент) + качество марша облаков.
        var qualityOption = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.rt.quality"),
                new Integer[]{0, 1, 2, 3},
                v -> config.rtQuality = v, () -> config.rtQuality)
                .setTranslator(v -> Component.translatable("vulkanmod.options.rt.quality." + v))
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.quality.tooltip"))
                .setImpact(PerformanceImpact.HIGH);

        var shadowsOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.shadows"),
                value -> config.rtShadows = value, () -> config.rtShadows)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.shadows.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM);

        var ambientOption = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.rt.ambient"),
                new Integer[]{0, 2, 4},
                value -> config.rtAmbientRays = value,
                () -> config.rtAmbientRays)
                .setTranslator(v -> Component.translatable("vulkanmod.options.rt.ambient." + v))
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.ambient.tooltip"))
                .setImpact(PerformanceImpact.HIGH);

        var reflOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.reflections"),
                value -> config.rtReflections = value, () -> config.rtReflections)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.reflections.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM);

        var cloudsOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.clouds"),
                value -> config.rtClouds = value, () -> config.rtClouds)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.clouds.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM);

        var godRaysOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.godrays"),
                value -> config.rtGodRays = value, () -> config.rtGodRays)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.godrays.tooltip"))
                .setImpact(PerformanceImpact.HIGH);

        var lightsOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.lights"),
                value -> config.rtColoredLights = value, () -> config.rtColoredLights)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.lights.tooltip"))
                .setImpact(PerformanceImpact.LOW);

        // === M8.162 PBR-МАТЕРИАЛЫ ИЗ РЕСУРСПАКОВ === (просьба пользователя: «кнопка, чтобы шейдер
        // читал PBR-паки avpbr/spbr»). Пак (AVPBR Retextured, SPBR и им подобные) кладёт рядом с
        // текстурой блока две карты — <имя>_n (нормаль) и <имя>_s (гладкость/F0/металл/эмиссия);
        // по ним трассировка получает РЕЛЬЕФ, честную шероховатость, металличность и свечение
        // КАЖДОГО текселя, а не «материал по имени спрайта», как наша таблица.
        // ⚠️ Ручка выключает РЕАЛЬНУЮ работу: без неё карты пака не читаются вовсе (RtPbrMaps.init
        // молчит), ветка #define RT_PBR не компилируется, а карты отпускают память — на HD-паках это
        // две текстуры размером с атлас, сотни мегабайт. Включение собирает их заново.
        // Почему не HIGH: на кадр это одна выборка двух текстур, дороже становится только число
        // зеркальных лучей там, где пак действительно гладкий.
        var pbrOption = new SwitchOption(Component.translatable("vulkanmod.options.rt.pbr"),
                value -> {
                    config.rtPbr = value;
                    // Выключили — карты недействительны (память в отставку, шейдер получит заглушки).
                    // Включили — ничего не делаем: сборка ленивая, случится на следующем кадре.
                    if (!value) net.vulkanmod.vulkan.rt.RtPbrMaps.invalidate();
                },
                () -> config.rtPbr)
                .setTooltip(v -> Component.translatable("vulkanmod.options.rt.pbr.tooltip"))
                .setImpact(PerformanceImpact.LOW);

        // M8.122: «Шейдеры» — наш пост (AgX + экранные эффекты) поверх ванильного растра при
        // выключенной трассировке. Иерархия (M8.122c, по слову пользователя): «Шейдеры» доступны
        // ВСЕГДА, и их выключение УТАСКИВАЕТ RT за собой (RT без шейдеров не работает) — виджет
        // перещёлкивается честно, он читает getNewValue() при отрисовке. А вот включить RT при
        // выключенных шейдерах нельзя — ручка заблокирована. Три режима:
        // RT ВКЛ -> растр + шейдеры -> чистый растр. Инвариант: RT ВКЛ => шейдеры ВКЛ.
        var shadersOption = new SwitchOption(Component.translatable("vulkanmod.options.shaders.enabled"),
                value -> config.shadersEnabled = value,
                () -> config.shadersEnabled)
                .setTooltip(v -> Component.translatable("vulkanmod.options.shaders.enabled.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM);
        // M8.125: растровый фолбэк ОТЛОЖЕН (слово пользователя, 2026-07-16 — showcase-видео).
        // «Трассировка» НЕКЛИКАБЕЛЬНА и намертво следует за «Шейдерами»: два режима —
        // всё ВКЛ (полный RT) / всё ВЫКЛ (чистый VulkanMod). Код фолбэка не удалён.
        rtOption.setActivationFn(() -> false);

        shadersToggle = shadersOption;   // M8.122e: живой гейт для ванильных ручек «Графики»

        // Трассировка выключена -> подчинённые ручки гаснут: они бы всё равно ни на что не влияли.
        Option<?>[] sub = { qualityOption,
                shadowsOption, ambientOption, reflOption, cloudsOption, lightsOption, godRaysOption,
                pbrOption,
                upscalerOnOption, upscalerOption, denoiserOnOption, denoiserOption };
        for (Option<?> o : sub) o.setActivationFn(() -> rtOption.getNewValue());
        // M8.122e: DLSS — только на железе, где его инициализация не провалилась (старые GPU
        // без тензорных ядер: тумблер гаснет; RT при выключенном RT и так не пускает).
        // Выбор реализации и МАСШТАБ гаснут вместе со своим тумблером — ручка, которая ни на что
        // не влияет, только путает (просьба пользователя: «делай её серой, как переключатель»).
        upscalerOption.setActivationFn(() -> rtOption.getNewValue() && upscalerOnOption.getNewValue());
        // Ручки денойзера гаснут, когда апскейлером выбран DLSS: он уже определил и денойзер.
        denoiserOnOption.setActivationFn(() -> rtOption.getNewValue() && !config.upscalerOwnsDenoiser());
        denoiserOption.setActivationFn(() -> rtOption.getNewValue()
                && denoiserOnOption.getNewValue() && !config.upscalerOwnsDenoiser());
        denoiserOnOption.setOnChange(denoiserOption::updateActiveState);
        // M8.122e: «Разрешение трассировки» имеет смысл ТОЛЬКО с DLSS: без апскейлера кадр
        // растягивался бы простым блитом — мыло. В рантайме без DLSS масштаб принудительно 100%.
        // Масштаб трассировки осмыслен только при РАБОТАЮЩЕЙ модели апскейлера. Мало включить
        // тумблер: DLSS поднимает кадр лишь заодно со своим денойзером, а FSR ещё не написан.
        // ⚠️ Раньше ползунок был активен при любой модели, и человек ставил 50%, а движок молча
        // возвращал 100% — настройка врала. Теперь гаснет ровно тогда, когда не работает.
        scaleOption.setActivationFn(() -> {
            if (!rtOption.getNewValue() || !upscalerOnOption.getNewValue()) return false;
            int u = upscalerOption.getNewValue();
            if (u == Config.UPSCALER_BASIC) return true;
            if (u == Config.UPSCALER_DLSS)
                return denoiserOnOption.getNewValue() && denoiserOption.getNewValue() == Config.DENOISER_DLSS;
            return false;   // FSR 3.1 — в работе
        });
        denoiserOption.setOnChange(scaleOption::updateActiveState);
        denoiserOnOption.setOnChange(scaleOption::updateActiveState);
        upscalerOnOption.setOnChange(() -> {
            upscalerOption.updateActiveState();
            scaleOption.updateActiveState();
            denoiserOnOption.updateActiveState();
            denoiserOption.updateActiveState();
        });
        upscalerOption.setOnChange(() -> {
            denoiserOnOption.updateActiveState();
            denoiserOption.updateActiveState();
            scaleOption.updateActiveState();
        });
        rtOption.setOnChange(() -> {
            for (Option<?> o : sub) o.updateActiveState();
            upscalerOnOption.updateActiveState(); upscalerOption.updateActiveState();
            denoiserOnOption.updateActiveState(); denoiserOption.updateActiveState();
            scaleOption.updateActiveState();
        });
        shadersOption.setOnChange(() -> {
            // M8.125: RT следует за «Шейдерами» в обе стороны (его onChange притушит/оживит
            // подчинённые ручки DLSS/качества)
            rtOption.setNewValue(shadersOption.getNewValue());
            // ванильный вид на «Графике» гаснет, пока шейдеры включены (и оживает без них)
            for (Option<?> o : shaderGated) o.updateActiveState();
        });

        return new OptionBlock[]{
                // «Шейдеры» ВЫШЕ трассировки: RT без шейдеров не работает — иерархия сверху вниз.
                new OptionBlock("", new Option<?>[]{ shadersOption, rtOption,
                        upscalerOnOption, upscalerOption, scaleOption,
                        denoiserOnOption, denoiserOption, qualityOption }),
                new OptionBlock(Component.translatable("vulkanmod.options.rt.block.light").getString(),
                        new Option<?>[]{ shadowsOption, ambientOption, lightsOption }),
                new OptionBlock(Component.translatable("vulkanmod.options.rt.block.detail").getString(),
                        new Option<?>[]{ reflOption, cloudsOption, godRaysOption, pbrOption })
        };
    }

    /**
     * === RT PATCH === Страница «Изображение» — порт ручек Eclipse (Post_Processing, Color_Processing).
     *
     * Всё это — свойства КАМЕРЫ И ПЛЁНКИ, а не света в сцене, поэтому живут в пост-проходе (RtPost).
     * Работают только при включённой трассировке: без неё пост-проход не запускается вовсе — и ручки
     * гаснут, чтобы не притворяться работающими.
     */
    public static OptionBlock[] getImageOpts() {
        var mblur = new RangeOption(Component.translatable("vulkanmod.options.img.motionBlur"), 0, 100, 5,
                v -> config.motionBlur = v, () -> config.motionBlur)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.motionBlur.tooltip"))
                .setImpact(PerformanceImpact.LOW);

        var expo = new RangeOption(Component.translatable("vulkanmod.options.img.exposure"), -20, 20, 1,
                v -> config.exposureBias = v, () -> config.exposureBias)
                .setTranslator(v -> Component.literal((v > 0 ? "+" : "") + String.format("%.1f EV", v / 10f)))
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.exposure.tooltip"));

        var sat = new RangeOption(Component.translatable("vulkanmod.options.img.saturation"), 0, 200, 5,
                v -> config.saturation = v, () -> config.saturation)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.saturation.tooltip"));

        var vign = new RangeOption(Component.translatable("vulkanmod.options.img.vignette"), 0, 100, 5,
                v -> config.vignette = v, () -> config.vignette)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.vignette.tooltip"));

        var grain = new RangeOption(Component.translatable("vulkanmod.options.img.grain"), 0, 100, 5,
                v -> config.filmGrain = v, () -> config.filmGrain)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.grain.tooltip"));

        var chroma = new RangeOption(Component.translatable("vulkanmod.options.img.chromatic"), 0, 100, 5,
                v -> config.chromatic = v, () -> config.chromatic)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.chromatic.tooltip"));

        var flare = new RangeOption(Component.translatable("vulkanmod.options.img.flare"), 0, 200, 10,
                v -> config.lensFlare = v, () -> config.lensFlare)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.img.flare.tooltip"));

        // M8.122: грейд/линза работают и в растровом фолбэке («Шейдеры» без RT) — пост тот же.
        // РАЗМЫТИЕ и БЛИК остаются только при RT: им нужны guide-буферы (движение, глубина),
        // а в растре там мусор прошлого RT-кадра — ручки бы врали.
        Option<?>[] shared = { expo, sat, vign, grain, chroma };
        for (Option<?> o : shared) o.setActivationFn(() -> config.rtEnabled || config.shadersEnabled);
        Option<?>[] rtOnly = { mblur, flare };
        for (Option<?> o : rtOnly) o.setActivationFn(() -> config.rtEnabled);

        return new OptionBlock[]{
                new OptionBlock(Component.translatable("vulkanmod.options.img.block.camera").getString(),
                        new Option<?>[]{ mblur, expo, sat }),
                new OptionBlock(Component.translatable("vulkanmod.options.img.block.lens").getString(),
                        new Option<?>[]{ flare, vign, grain, chroma })
        };
    }

    /**
     * === RT PATCH === Страница «Мир» — порт ручек Eclipse (Torch_Colors, Ambient_Colors, Fog, Clouds).
     *
     * Это множители на константы, которые шейдер уже использует. Границы тумана сюда НЕ вынесены
     * намеренно: они считаются от ДАЛЬНОСТИ ПРОРИСОВКИ, поэтому горизонт подёрнут дымкой одинаково
     * и на 8 чанках, и на 32. Ручкой остаётся только плотность.
     */
    public static OptionBlock[] getRtWorldOpts() {
        var fog = new RangeOption(Component.translatable("vulkanmod.options.world.fog"), 0, 200, 5,
                v -> config.fogDensity = v, () -> config.fogDensity)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.world.fog.tooltip"));

        var clouds = new RangeOption(Component.translatable("vulkanmod.options.world.clouds"), 0, 200, 5,
                v -> config.cloudAmount = v, () -> config.cloudAmount)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.world.clouds.tooltip"));

        var stars = new RangeOption(Component.translatable("vulkanmod.options.world.stars"), 0, 300, 10,
                v -> config.starsBrightness = v, () -> config.starsBrightness)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.world.stars.tooltip"));

        var torch = new RangeOption(Component.translatable("vulkanmod.options.world.torch"), 0, 300, 10,
                v -> config.torchBrightness = v, () -> config.torchBrightness)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.world.torch.tooltip"));

        var emis = new RangeOption(Component.translatable("vulkanmod.options.world.emissive"), 0, 300, 10,
                v -> config.emissiveBrightness = v, () -> config.emissiveBrightness)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.world.emissive.tooltip"));

        var minl = new RangeOption(Component.translatable("vulkanmod.options.world.minLight"), 0, 300, 10,
                v -> config.minLight = v, () -> config.minLight)
                .setTranslator(Options::pct)
                .setTooltip(v -> Component.translatable("vulkanmod.options.world.minLight.tooltip"));

        Option<?>[] all = { fog, clouds, stars, torch, emis, minl };
        for (Option<?> o : all) o.setActivationFn(() -> config.rtEnabled);

        return new OptionBlock[]{
                new OptionBlock(Component.translatable("vulkanmod.options.world.block.sky").getString(),
                        new Option<?>[]{ fog, clouds, stars }),
                new OptionBlock(Component.translatable("vulkanmod.options.world.block.light").getString(),
                        new Option<?>[]{ torch, emis, minl })
        };
    }

    /** «57%» — общий переводчик для процентных ручек. */
    private static Component pct(int v) { return Component.literal(v + "%"); }

    public static OptionBlock[] getGraphicsOpts() {
        var texFilteringOption = new CyclingOption<>(Component.translatable("options.textureFiltering"),
                                                     TextureFilteringMethod.values(),
                                                     value -> {
                                                         var oldValue = mcOptions.textureFiltering()
                                                                                        .get();

                                                         if ((oldValue == TextureFilteringMethod.ANISOTROPIC && value != TextureFilteringMethod.ANISOTROPIC)
                                                             || (value == TextureFilteringMethod.ANISOTROPIC && oldValue != TextureFilteringMethod.ANISOTROPIC)) {
                                                             minecraft.delayTextureReload();
                                                             WorldRenderer.getInstance()
                                                                          .resetSampler();
                                                         }

                                                         mcOptions.textureFiltering()
                                                                         .set(value);
                                                     },
                                                     () -> mcOptions.textureFiltering()
                                                                           .get())
                .setTranslator(TextureFilteringMethod::caption)
                .setTooltip(value -> switch (value) {
                    case NONE -> Component.translatable("options.textureFiltering.none.tooltip");
                    case RGSS -> Component.translatable("options.textureFiltering.rgss.tooltip");
                    case ANISOTROPIC -> Component.translatable("options.textureFiltering.anisotropic.tooltip");
                })
                .setImpact(PerformanceImpact.MEDIUM);



        var maxAnisotropyOption = new RangeOption(Component.translatable("options.maxAnisotropy"),
                                                  1, 3, 1,
                                                  value -> {
                                                      var oldValue = mcOptions.maxAnisotropyBit()
                                                                                     .get();

                                                      if (mcOptions.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
                                                          && !oldValue.equals(value)) {
                                                          minecraft.delayTextureReload();
                                                          WorldRenderer.getInstance()
                                                                       .resetSampler();
                                                      }

                                                      mcOptions.maxAnisotropyBit()
                                                                      .set(value);
                                                  },
                                                  () -> mcOptions.maxAnisotropyBit()
                                                                        .get())
                .setTranslator((value) -> Component.translatable("options.multiplier", Integer.toString(1 << value)))
                .setTooltip(v -> Component.translatable("options.maxAnisotropy.tooltip"));

        maxAnisotropyOption.setActivationFn(() -> texFilteringOption.getNewValue() == TextureFilteringMethod.ANISOTROPIC);
        texFilteringOption.setOnChange(maxAnisotropyOption::updateActiveState);

        // M8.122e: фильтрация текстур действует ВСЕГДА (сэмплер атласа; DLSS ей не мешает) —
        // экспорт наверх, к тумблерам шейдеров (getOptionPages подошьёт её в верхний блок).
        texFilterPair = new Option<?>[]{ texFilteringOption, maxAnisotropyOption };

        OptionBlock[] blocks = new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.renderDistance"),
                                2, 32, 1,
                                value -> mcOptions.renderDistance().set(value),
                                () -> mcOptions.renderDistance().get())
                                .setTooltip(v -> Component.literal("Chunk render distance"))
                                .setImpact(PerformanceImpact.HIGH),
                        new RangeOption(Component.translatable("options.simulationDistance"),
                                5, 32, 1,
                                value -> mcOptions.simulationDistance().set(value),
                                () -> mcOptions.simulationDistance().get()),
                        new CyclingOption<>(Component.translatable("options.prioritizeChunkUpdates"),
                                PrioritizeChunkUpdates.values(),
                                value -> mcOptions.prioritizeChunkUpdates().set(value),
                                () -> mcOptions.prioritizeChunkUpdates().get())
                                .setTranslator(PrioritizeChunkUpdates::caption)
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.graphics.preset"),
                                new GraphicsPreset[]{GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.CUSTOM},
                                value -> mcOptions.graphicsPreset().set(value),
                                () -> mcOptions.graphicsPreset().get())
                                .setTranslator(g -> Component.translatable(g.getKey())),
                        texFilteringOption,
                        maxAnisotropyOption,
                        new CyclingOption<>(Component.translatable("options.particles"),
                                            new ParticleStatus[]{ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL},
                                            value -> mcOptions.particles().set(value),
                                            () -> mcOptions.particles().get())
                                .setImpact(PerformanceImpact.MEDIUM)
                                .setTranslator(ParticleStatus::caption),
                        new CyclingOption<>(Component.translatable("options.renderClouds"),
                                            CloudStatus.values(),
                                            value -> mcOptions.cloudStatus().set(value),
                                            () -> mcOptions.cloudStatus().get())
                                .setTranslator(CloudStatus::caption),
                        new RangeOption(Component.translatable("options.renderCloudsDistance"),
                                        2, 128, 1,
                                        value -> mcOptions.cloudRange().set(value),
                                        () -> mcOptions.cloudRange().get()),
                        new SwitchOption(Component.translatable("options.cutoutLeaves"),
                                         value -> mcOptions.cutoutLeaves().set(value),
                                         () -> mcOptions.cutoutLeaves().get())
                                .setTooltip(value -> Component.translatable("options.cutoutLeaves.tooltip")),
                        new RangeOption(Component.translatable("options.chunkFade"),
                                        0, 40, 1,
                                        (value) -> mcOptions.chunkSectionFadeInTime().set(value / 20.0),
                                        () -> (int) (mcOptions.chunkSectionFadeInTime().get() * 20))
                                .setTranslator(value -> Component.literal(String.valueOf(value / 20.0f)))
                                .setTooltip(v -> Component.translatable("options.chunkFade.tooltip")),
                        // TODO: improved transparency
//                        new SwitchOption(Component.translatable("options.improvedTransparency"),
//                                         value -> minecraftOptions.improvedTransparency().set(value),
//                                         () -> minecraftOptions.improvedTransparency().get())
//                                .setTooltip(Component.translatable("options.improvedTransparency.tooltip")),
                        new CyclingOption<>(Component.translatable("options.ao"),
                                new Integer[]{LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK},
                                value -> {
                                    mcOptions.ambientOcclusion().set(value > LightMode.FLAT);
                                    config.ambientOcclusion = value;
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> config.ambientOcclusion)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case LightMode.FLAT -> "options.off";
                                    case LightMode.SMOOTH -> "options.on";
                                    case LightMode.SUB_BLOCK -> "vulkanmod.options.ao.subBlock";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(value -> value == LightMode.SUB_BLOCK
                                ? Component.translatable("vulkanmod.options.ao.subBlock.tooltip")
                                : Component.empty())
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.biomeBlendRadius"),
                                0, 7, 1,
                                value -> Component.nullToEmpty("%d x %d".formatted(value * 2 + 1, value * 2 + 1)),
                                value -> {
                                    mcOptions.biomeBlendRadius().set(value);
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> mcOptions.biomeBlendRadius().get())
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.entityShadows"),
                                value -> mcOptions.entityShadows().set(value),
                                () -> mcOptions.entityShadows().get())
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.entityDistanceScaling"),
                                        2, 20, 1,
                                        value -> mcOptions.entityDistanceScaling().set(value / 4.0),
                                        () -> (int) (mcOptions.entityDistanceScaling().get() * 4.0))
                                        .setImpact(PerformanceImpact.HIGH)
                                        .setTranslator(value -> Component.literal(String.valueOf(value / 4.0))),
                        new CyclingOption<>(Component.translatable("options.mipmapLevels"),
                                new Integer[]{0,1,2,3,4},
                                value -> {
                                    mcOptions.mipmapLevels().set(value);
                                    minecraft.updateMaxMipLevel(value);
                                    minecraft.delayTextureReload();
                                },
                                () -> mcOptions.mipmapLevels().get())
                                .setTranslator(v -> Component.literal(String.valueOf(v)))
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.weatherRadius"),
                                        3, 10, 1,
                                        value -> mcOptions.weatherRadius().set(value),
                                        () -> mcOptions.weatherRadius().get())
                                .setTooltip(value -> Component.translatable("options.weatherRadius.tooltip")),
                        new SwitchOption(Component.translatable("options.vignette"),
                                         value -> mcOptions.vignette().set(value),
                                         () -> mcOptions.vignette().get())
                                .setTooltip(value -> Component.translatable("options.vignette.tooltip")),
                })
        };

        // M8.122e: пока ШЕЙДЕРЫ включены, ванильный ВИД (блоки после чанков) на картинку не
        // влияет — наш конвейер рисует своё. Гасим эти ручки (живой гейт — по тумблеру, без
        // Apply); фильтрация текстур исключена — она переезжает наверх и действует всегда.
        // Блок чанков (blocks[0]) НЕ гасим: дальность прорисовки и дистанция симуляции
        // действуют и в RT — из этих чанков строятся BLAS.
        shaderGated.clear();
        for (int b = 1; b < blocks.length; b++) {
            blocks[b] = new OptionBlock(blocks[b].title(),
                    java.util.Arrays.stream(blocks[b].options())
                            .filter(o -> o != texFilteringOption && o != maxAnisotropyOption)
                            .toArray(Option[]::new));
            for (Option<?> o : blocks[b].options()) {
                o.setActivationFn(() -> !shadersLiveOn());
                shaderGated.add(o);
            }
        }
        return blocks;
    }

    public static OptionBlock[] getOptimizationOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.advCulling"),
                                new Integer[]{1, 2, 3, 10},
                                value -> config.advCulling = value,
                                () -> config.advCulling)
                                .setTranslator(v -> Component.translatable(switch (v) {
                                    case 1 -> "vulkanmod.options.advCulling.aggressive";
                                    case 2 -> "vulkanmod.options.advCulling.normal";
                                    case 3 -> "vulkanmod.options.advCulling.conservative";
                                    case 10 -> "options.off";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(v -> v <= 3 ? Component.translatable("vulkanmod.options.advCulling.tooltip") : Component.empty())
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.entityCulling"),
                                v -> config.entityCulling = v,
                                () -> config.entityCulling)
                                .setTooltip(v -> Component.translatable("vulkanmod.options.entityCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.uniqueOpaqueLayer"),
                                v -> {
                                    config.uniqueOpaqueLayer = v;
                                    TerrainRenderType.updateMapping();
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> config.uniqueOpaqueLayer)
                                .setTooltip(v -> Component.translatable("vulkanmod.options.uniqueOpaqueLayer.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.backfaceCulling"),
                                v -> {
                                    config.backFaceCulling = v;
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> config.backFaceCulling)
                                .setTooltip(v -> Component.translatable("vulkanmod.options.backfaceCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.indirectDraw"),
                                v -> config.indirectDraw = v,
                                () -> config.indirectDraw)
                                .setTooltip(v -> Component.translatable("vulkanmod.options.indirectDraw.tooltip"))
                                .setImpact(PerformanceImpact.HIGH)
                })
        };
    }

    public static OptionBlock[] getOtherOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("vulkanmod.options.builderThreads"),
                                0, Runtime.getRuntime().availableProcessors() - 1, 1,
                                value -> {
                                    config.builderThreads = value;
                                    WorldRenderer.getInstance().getTaskDispatcher().createThreads(value);
                                },
                                () -> config.builderThreads)
                                .setTranslator(v -> v == 0
                                ? Component.translatable("vulkanmod.options.builderThreads.auto")
                                : Component.literal(String.valueOf(v))),
                        new RangeOption(Component.translatable("vulkanmod.options.frameQueue"),
                                2, 5, 1,
                                value -> {
                                    config.frameQueueSize = value;
                                    Renderer.scheduleSwapChainUpdate();
                                },
                                () -> config.frameQueueSize)
                                .setTooltip(v -> Component.translatable("vulkanmod.options.frameQueue.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.textureAnimations"),
                                v -> config.textureAnimations = v,
                                () -> config.textureAnimations)
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("vulkanmod.options.wayland"),
                                         v -> config.useWayland = v,
                                         () -> config.useWayland)
                                .setActivationFn(Platform::isLinux)
                                .setTooltip(v -> Component.translatable("vulkanmod.options.wayland.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.deviceSelector"),
                                IntStream.range(-1, DeviceManager.suitableDevices.size())
                                        .boxed()
                                        .toArray(Integer[]::new),
                                value -> config.device = value,
                                () -> config.device)
                                .setTranslator(v -> Component.translatable(
                                        v == -1 ? "vulkanmod.options.deviceSelector.auto"
                                                : DeviceManager.suitableDevices.get(v).deviceName))
                                .setTooltip(v -> Component.literal(
                                Component.translatable("vulkanmod.options.deviceSelector.tooltip").getString() + ": " +
                                        DeviceManager.device.deviceName))
                })
        };
    }
}