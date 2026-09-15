package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class WorldRenderer {
    private static WorldRenderer INSTANCE;

    public static WorldRenderer init(EntityRenderDispatcher entityRenderDispatcher,
                                     BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                                     RenderBuffers renderBuffers,
                                     LevelRenderState levelRenderState,
                                     FeatureRenderDispatcher featureRenderDispatcher)
    {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        else {
            return INSTANCE = new WorldRenderer(entityRenderDispatcher, blockEntityRenderDispatcher, renderBuffers, levelRenderState, featureRenderDispatcher);
        }
    }

    public RenderRegionBuilder renderRegionCache;

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private final EntityRenderDispatcher entityRenderDispatcher;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final LevelRenderState levelRenderState;
    private final FeatureRenderDispatcher featureRenderDispatcher;

    private float partialTick;
    /** M8.5: последний ДОСТОВЕРНЫЙ уровень глади (вода->воздух). Под навесом колонка закрыта
     *  блоком, и скан по ней врёт — тогда берём это значение (уровень водоёма не меняется). */
    private static float cachedWaterSurfaceY = -1.0e9f;
    /** M8.7: сглаживание света по времени — иначе block-light щёлкает целыми ступенями (0..15). */
    private static float smoothPlayerLight = 0f, smoothHeldLight = 0f;
    private static long  lastLightNs = 0L;
    // M8.10: плавные эффекты камеры/зелий
    private static float smBlind = 0f, smNight = 0f, smPoison = 0f, smWither = 0f, smNausea = 0f, smDark = 0f;
    private static float smFire = 0f;
    private static float enterWaterFx = 0f, exitWaterFx = 0f;
    // Униформы Eclipse (gameplay_effects)
    private static float smOneHeart = 0f, smThreeHeart = 0f;
    private static float minorDmgFx = 0f, critDmgFx = 0f, lavaFx = 0f, prevHealth = 20f;
    private static float storminess = 0f;      // сглаженная «штормовость» 0..1 (тучи собираются плавно)
    private static float wetnessAcc = 0f;      // влажность для луж 0..1 (2-мин высыхание)
    private static float auroraAcc = 0f;        // северное сияние 0..1 (снежный биом, плавно)
    // M8.133: цвет тумана Ада по биому (плавный наплыв при смене биома)
    private static float netFogR = 0.30f, netFogG = 0.06f, netFogB = 0.05f;
    private static long lastNetherNs = 0L;
    private static long lastPortalNs = 0L;   // M8.144: троттл скана портала в Энд
    private static long  lastCloudNs = 0L;
    private static long  lastFxNs = 0L;
    private final Vector3d cameraPos = new Vector3d();
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;

    IndirectBuffer[] indirectBuffers;

    private long terrainSampler;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(EntityRenderDispatcher entityRenderDispatcher,
                          BlockEntityRenderDispatcher blockEntityRenderDispatcher,
                          RenderBuffers renderBuffers,
                          LevelRenderState levelRenderState,
                          FeatureRenderDispatcher featureRenderDispatcher)
    {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;
        this.entityRenderDispatcher = entityRenderDispatcher;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.levelRenderState = levelRenderState;
        this.featureRenderDispatcher = featureRenderDispatcher;

        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();

        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();
        TerrainRenderType.updateMapping();

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
        });
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
        }
    }

    private void benchCallback() {
        BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
    }

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Setup_Renderer");

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();

        benchCallback();

        Vec3 camPos = camera.position();
        this.cameraPos.set(camPos.x(), camPos.y(), camPos.z());

        // === RT PATCH (M8.3): отдать позицию+базис камеры пробнику и снапшоту лучей ===
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) {
            org.joml.Vector3fc fwd = camera.forwardVector();
            org.joml.Vector3fc up = camera.upVector();
            org.joml.Vector3fc left = camera.leftVector();
            net.vulkanmod.vulkan.rt.RtProbe.setCamera(camPos.x(), camPos.y(), camPos.z(),
                    fwd.x(), fwd.y(), fwd.z());
            // ⚠️ ЗДЕСЬ КАМЕРУ БОЛЬШЕ НЕ СТАВИМ (M8.78). Замер показал: базис, посчитанный из МАТРИЦЫ
            // ВИДА (см. renderSectionLayer), затирался вот этим вызовом — в шейдер приезжал ровный
            // базис из векторов объекта Camera, и никакой крен до лучей не доходил. Векторы камеры
            // не знают ни о покачивании, ни о модах вроде Camera Overhaul: те правят матрицу.
            // Камеру для трассировки ставим ТОЛЬКО из матрицы вида, одним местом.
            net.vulkanmod.vulkan.rt.RtEntities.setCamera(camPos.x(), camPos.y(), camPos.z());
            net.vulkanmod.vulkan.rt.RtEntities.beginLevel();   // окно сбора сущностей открыто
            // M8.5: камера под водой -> подводный тинт/искажение; время для анимации ряби
            boolean underwater = this.minecraft.level != null
                    && camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER;
            // Модуль МАЛЫЙ (3600 тиков): на float32 у большого времени (~1e6) терялась дробь
            // partialTick -> анимация волн шла скачками 20/сек. Малое время -> плавно.
            float animT = this.minecraft.level != null
                    ? (float) (this.minecraft.level.getGameTime() % 3600L) + this.partialTick : 0f;
            net.vulkanmod.vulkan.rt.RtSnapshot.setUnderwater(underwater, animT);
            // M8.130: измерение -> небо/туман (0 верхний, 1 Ад, 2 Энд)
            if (this.minecraft.level != null) {
                var dimKey = this.minecraft.level.dimension();
                int dim = dimKey == net.minecraft.world.level.Level.NETHER ? 1
                        : dimKey == net.minecraft.world.level.Level.END ? 2 : 0;
                net.vulkanmod.vulkan.rt.RtSnapshot.setDimension(dim);
                // M8.133: в Аду — цвет тумана по биому (наша палитра), плавно наплывает (~4с)
                if (dim == 1) {
                    net.minecraft.core.BlockPos cbp = net.minecraft.core.BlockPos.containing(camPos.x, camPos.y, camPos.z);
                    var nbiome = this.minecraft.level.getBiome(cbp);
                    float tr = 0.30f, tg = 0.06f, tb = 0.05f;   // Пустоши: оранжево-красный (дефолт)
                    if      (nbiome.is(net.minecraft.world.level.biome.Biomes.CRIMSON_FOREST))   { tr = 0.34f; tg = 0.06f; tb = 0.05f; }
                    else if (nbiome.is(net.minecraft.world.level.biome.Biomes.WARPED_FOREST))    { tr = 0.10f; tg = 0.18f; tb = 0.22f; }
                    else if (nbiome.is(net.minecraft.world.level.biome.Biomes.SOUL_SAND_VALLEY)) { tr = 0.07f; tg = 0.17f; tb = 0.19f; }
                    else if (nbiome.is(net.minecraft.world.level.biome.Biomes.BASALT_DELTAS))    { tr = 0.20f; tg = 0.19f; tb = 0.20f; }
                    long nns2 = System.nanoTime();
                    float ndt = (lastNetherNs == 0L) ? 0.05f : Math.min((nns2 - lastNetherNs) / 1e9f, 0.2f);
                    lastNetherNs = nns2;
                    float nk = 1f - (float) Math.exp(-ndt / 0.6f);
                    netFogR += (tr - netFogR) * nk; netFogG += (tg - netFogG) * nk; netFogB += (tb - netFogB) * nk;
                    net.vulkanmod.vulkan.rt.RtSnapshot.setNetherFog(netFogR, netFogG, netFogB);
                }
                // M8.144h: звёздная гладь портала — детект по УЖЕ СОБРАННОМУ sectionLights (дёшево,
                // микросекунды, вся дальность). Убрали блочный скан 22к/500мс = периодический спайк.
                if (dim == 0) {
                    long pns = System.nanoTime();
                    if (pns - lastPortalNs > 400_000_000L) {
                        lastPortalNs = pns;
                        float[] ep = net.vulkanmod.vulkan.rt.RtLights.nearestEndPortal(camPos.x, camPos.y, camPos.z);
                        if (ep != null) net.vulkanmod.vulkan.rt.RtSnapshot.setEndPortal(ep[0], ep[1], ep[2]);
                        else            net.vulkanmod.vulkan.rt.RtSnapshot.clearEndPortal();
                    }
                } else {
                    net.vulkanmod.vulkan.rt.RtSnapshot.clearEndPortal();
                }
            }
            // M8.8: камера ПОГРУЖЕНА В ЛАВУ -> тёплый плотный туман (иначе видно нутро лавы = мусор).
            // getFluidInCamera() МИГАЕТ на стыках потоков лавы (переменная высота flowing-лавы) ->
            // туман пропадал, проступало нутро. Подстраховка: прямая проверка флюида в клетке глаза
            // с учётом реальной высоты потока.
            boolean eyeLava = this.minecraft.level != null
                    && camera.getFluidInCamera() == net.minecraft.world.level.material.FogType.LAVA;
            if (!eyeLava && this.minecraft.level != null) {
                var lvl = this.minecraft.level;
                var ep = new net.minecraft.core.BlockPos(
                        Mth.floor(camPos.x()), Mth.floor(camPos.y()), Mth.floor(camPos.z()));
                var fs = lvl.getFluidState(ep);
                if (fs.is(net.minecraft.tags.FluidTags.LAVA)
                        && camPos.y() < ep.getY() + fs.getHeight(lvl, ep))
                    eyeLava = true;
            }
            net.vulkanmod.vulkan.rt.RtSnapshot.setEyeInLava(eyeLava);

            // M8.9: БЛОК ПОД ПРИЦЕЛОМ -> ванильная обводка. RT-кадр перезаписывает ванильный кадр,
            // поэтому обводку рисуем внутри RT-шейдера по этой позиции.
            var hit = this.minecraft.hitResult;
            boolean outSet = false;
            if (hit instanceof net.minecraft.world.phys.BlockHitResult bhr
                    && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && this.minecraft.level != null) {
                var bp = bhr.getBlockPos();
                var lvl2 = this.minecraft.level;
                var shape = lvl2.getBlockState(bp).getShape(lvl2, bp);
                if (!shape.isEmpty()) {
                    // ⚠️ БЕРЁМ РЁБРА, А НЕ КОРОБКИ. Форма составного блока (кровать, забор, фонарь) —
                    // это НАБОР коробок, и рисуя рёбра каждой, мы получали лишние линии на внутренних
                    // стыках: кровать выглядела расчерченной сеткой. Ваниль обводит ВНЕШНИЙ контур —
                    // forAllEdges выдаёт ровно его, те же отрезки, что рисует сама игра.
                    final int MAX = net.vulkanmod.vulkan.rt.RtSnapshot.OUTLINE_MAX_EDGES;
                    float[] d = new float[MAX * 6];
                    int[] cnt = {0};
                    shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
                        if (cnt[0] >= MAX) return;
                        int o = cnt[0] * 6;
                        d[o    ] = (float) (bp.getX() + x1);
                        d[o + 1] = (float) (bp.getY() + y1);
                        d[o + 2] = (float) (bp.getZ() + z1);
                        d[o + 3] = (float) (bp.getX() + x2);
                        d[o + 4] = (float) (bp.getY() + y2);
                        d[o + 5] = (float) (bp.getZ() + z2);
                        cnt[0]++;
                    });
                    net.vulkanmod.vulkan.rt.RtSnapshot.setOutlineBoxes(d, cnt[0]);
                    outSet = true;
                }
            }
            if (!outSet) net.vulkanmod.vulkan.rt.RtSnapshot.setOutlineBoxes(
                    new float[net.vulkanmod.vulkan.rt.RtSnapshot.OUTLINE_MAX_EDGES * 6], 0);

            // M8.16 ТРЕЩИНЫ ЛОМАНИЯ: ломаемый блок = блок под прицелом (его коробки уже ушли выше),
            // поэтому в RT нужен только ПРОГРЕСС. Ваниль рисует трещины в проходе мира, который мы
            // перезаписываем, — рисуем их сами в шейдере.
            float brk = 0f;
            var gm = this.minecraft.gameMode;
            if (outSet && gm instanceof net.vulkanmod.mixin.render.MultiPlayerGameModeAccessor acc
                    && acc.rtIsDestroying())
                brk = Mth.clamp(acc.rtGetDestroyProgress(), 0f, 1f);
            net.vulkanmod.vulkan.rt.RtSnapshot.setBreakProgress(brk);

            // === M8.18 ДВИЖЕНИЕ СОЛНЦА по игровому времени ===
            // В MC: восток = +X, dayTime 0 = восход (солнце на горизонте на востоке),
            // 6000 = полдень (в зените), 12000 = закат (запад), 18000 = полночь.
            // Отсюда угол над горизонтом: theta = timeOfDay * 2PI, и направление на солнце:
            //   (cos theta, sin theta, 0). Луна — ровно напротив (-sunDir).
            // Небольшой наклон пути по Z: иначе тени идеально вдоль осей и картинка «плоская»
            // (у шейдерпаков это sunPathRotation).
            if (this.minecraft.level != null) {
                // getTimeOfDay() в 1.21.11 нет — считаем из тиков: 0=восход, 6000=полдень,
                // 12000=закат, 18000=полночь. partialTick даёт плавность между тиками.
                long dayT = this.minecraft.level.getDayTime() % 24000L;
                float tod = ((float) dayT + this.partialTick) / 24000f;   // 0..1
                float theta = tod * (float) (Math.PI * 2.0);
                float sx = (float) Math.cos(theta);
                float sy = (float) Math.sin(theta);
                float sz = 0.28f;                                  // наклон пути (косые тени)
                float inv = 1.0f / (float) Math.sqrt(sx*sx + sy*sy + sz*sz);
                // ФАЗА ЛУНЫ (0..7, 0 = полнолуние). getMoonPhase() в 1.21.11 нет — ванильная
                // формула: фаза меняется каждые сутки, цикл 8.
                float phase = (float) ((this.minecraft.level.getDayTime() / 24000L) % 8L);
                net.vulkanmod.vulkan.rt.RtSnapshot.setSun(sx*inv, sy*inv, sz*inv, tod, phase);

                // === M8.20 ОБЛАКА: ветер + ПОГОДА ДНЯ ===
                var lvl3 = this.minecraft.level;
                long day = lvl3.getDayTime() / 24000L;
                // Сдвиг по ветру — как у Eclipse: время/24 * Cloud_Speed(1.2)
                float ct = (float) ((lvl3.getGameTime() % 1000000L) + this.partialTick) / 24.0f * 1.2f;

                // СУТОЧНЫЙ РАНДОМ: каждое УТРО выбираются ДВА параметра — ТИП облаков и их
                // КОЛИЧЕСТВО. Хеш от номера дня -> (тип 0..3, количество 0..1). Так каждый день
                // небо разное: то кучевые средней плотности, то редкие перистые, то ни облачка.
                // ⚠️ Один тип в день (а не все 4 слоя сразу) — чтобы не было «каши» из наслоений.
                long h = day * 6364136223846793005L + 1442695040888963407L;
                h ^= (h >>> 33); h *= 0xff51afd7ed558ccdL; h ^= (h >>> 33);
                int   type = (int) ((h >>> 40) & 0x3L);                  // 0..3: кучевые/средние/пласт/перистые
                float amt  = ((h >>> 12) & 0xFFFFFFL) / (float) 0xFFFFFF; // 0..1: количество

                float cover;
                if (amt < 0.12f) cover = 0.0f;                 // иногда — ни облачка
                else             cover = 0.30f + amt * 0.70f;  // 0.30 редкие .. 1.0 плотно

                // ПОГОДА перебивает выбор дня и задаёт ПОДХОДЯЩИЙ тип облаков:
                //   гроза  -> кучево-дождевые (тип 0), очень плотные и тёмные;
                //   дождь  -> сплошной дождевой пласт (тип 1);
                //   снег   -> тот же пласт (тип 1), но осадки = снег (знак w).
                float rain = lvl3.getRainLevel(this.partialTick);
                float thunder = lvl3.getThunderLevel(this.partialTick);

                // ПЛАВНЫЙ ПЕРЕХОД день<->шторм. Сглаженная «штормовость» s: тучи собираются и
                // рассеиваются ~8с, а не мгновенно. Переход идёт ЧЕРЕЗ НОЛЬ покрытия в середине
                // (s≈0.5) — поэтому смена ТИПА (дневной -> грозовой тип0) невидима: облака сперва
                // тают, затем формируются грозовые. Никакой резкости.
                long sns = System.nanoTime();
                float sdt = (lastCloudNs == 0L) ? 0.016f : Math.min((sns - lastCloudNs) / 1.0e9f, 0.2f);
                lastCloudNs = sns;
                float sTarget = Mth.clamp(rain + thunder, 0f, 1f);
                // тучи СОБИРАЮТСЯ быстрее (~3.5с), РАССЕИВАЮТСЯ медленнее (~8с)
                float sTau = (sTarget > storminess) ? 3.5f : 8.0f;
                storminess += (sTarget - storminess) * (1f - (float) Math.exp(-sdt / sTau));
                float s = storminess;
                if (s < 0.5f) {                        // дневные облака тают к нулю
                    float f = s / 0.5f; f = f * f * (3f - 2f * f);
                    cover *= (1f - f);
                } else {                               // грозовые/дождевые тучи формируются
                    type = 0;
                    float f = (s - 0.5f) / 0.5f; f = f * f * (3f - 2f * f);
                    cover = (2.0f + 0.35f * thunder) * f;
                }

                // Снег вместо дождя? — по осадкам биома в точке камеры.
                boolean isSnow = false;
                if (rain > 0.02f) {
                    BlockPos bp = BlockPos.containing(camPos.x, camPos.y, camPos.z);
                    isSnow = lvl3.getBiome(bp).value()
                            .getPrecipitationAt(bp, lvl3.getSeaLevel())
                            == net.minecraft.world.level.biome.Biome.Precipitation.SNOW;
                }

                // ВЛАЖНОСТЬ для луж: быстро растёт в дождь, СОХНЕТ ~2 мин (просьба). Снег луж не даёт.
                float wetTarget = isSnow ? 0f : Mth.clamp(rain * 1.6f, 0f, 1f);
                // Лужи НАБИРАЮТСЯ медленно (~28с) — проступают постепенно за время дождя, а не разом
                // по "/weather rain". Высыхают ~2 мин после дождя.
                float wetTau = (wetTarget > wetnessAcc) ? 12.0f : 110.0f;   // лужи набираются быстрее
                wetnessAcc += (wetTarget - wetnessAcc) * (1f - (float) Math.exp(-sdt / wetTau));
                net.vulkanmod.vulkan.rt.RtSnapshot.setWetness(wetnessAcc);

                // СЕВЕРНОЕ СИЯНИЕ (M8.103): снежный биом в точке камеры -> плавно ~6 с.
                BlockPos abp = BlockPos.containing(camPos.x, camPos.y, camPos.z);
                boolean cold = lvl3.getBiome(abp).value()
                        .getPrecipitationAt(abp, lvl3.getSeaLevel())
                        == net.minecraft.world.level.biome.Biome.Precipitation.SNOW;
                auroraAcc += ((cold ? 1f : 0f) - auroraAcc) * (1f - (float) Math.exp(-sdt / 6.0f));
                net.vulkanmod.vulkan.rt.RtSnapshot.setAurora(auroraAcc);

                // УДАРЫ КАПЕЛЬ по воде -> кольца ряби (привязка к каплям). animT — те же тики, что
                // params.y в шейдере (gameTime % 3600 + partialTick), чтобы возраст колец совпадал.
                float rippleTime = (float) (this.minecraft.level.getGameTime() % 3600L) + this.partialTick;
                net.vulkanmod.vulkan.rt.RtRipples.update(camPos.x, camPos.z, isSnow ? 0f : rain, rippleTime);

                // ДОЖДЬ ИДЁТ ИЗ ТУЧ, не из воздуха: капли гаснут выше ОСНОВАНИЯ облаков. Дождевые
                // тучи — тип 0, основание ~200 (CL0_h). Полоса 200..225 — мягкий край; выше капель
                // нет (это же убирает дождь, когда камера поднимается над тучами).
                net.vulkanmod.vulkan.rt.RtEntities.setWeatherClip((float) camPos.y, 225f, 25f);

                // ПОГОДА в одно поле: |w| = дождь+гроза (0..2), ЗНАК = снег(-)/дождь(+).
                // Шейдер: rainF=min(|w|,1), thunderF=|w|-1, snowF=знак минуса.
                float w = rain + thunder;
                if (isSnow) w = -w;

                // Упаковка типа И количества облаков: packed = cover + type*8 (в push-константах
                // нет места под отдельное поле). Шейдер: type=floor(p/8), amount=p-8*type.
                float packed = cover + type * 8.0f;
                net.vulkanmod.vulkan.rt.RtSnapshot.setClouds(ct, packed, w);

            }

            // === M8.10 ЭФФЕКТЫ КАМЕРЫ/ЗЕЛИЙ ===
            // RT-кадр перезаписывает ванильный, где ваниль рисовала эти эффекты, — делаем сами.
            // Слепота/тьма — это ТУМАН в мире (не экранный тинт), поэтому уходят в RT как затухание.
            {
                float tBlind = 0f, tNight = 0f, tPoison = 0f, tWither = 0f, tNausea = 0f, tDark = 0f;
                float tFire = 0f;
                float hurt = 0f, health = 1f;
                var p2 = this.minecraft.player;
                if (p2 != null) {
                    if (p2.isOnFire()) tFire = 1f;   // ванильный fire overlay + heat-distort Eclipse
                    if (p2.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS))   tBlind  = 1f;
                    if (p2.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) tNight = 1f;
                    if (p2.hasEffect(net.minecraft.world.effect.MobEffects.POISON))      tPoison = 1f;
                    if (p2.hasEffect(net.minecraft.world.effect.MobEffects.WITHER))      tWither = 1f;
                    if (p2.hasEffect(net.minecraft.world.effect.MobEffects.NAUSEA))      tNausea = 1f;
                    if (p2.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS))    tDark   = 1f;
                }
                // Плавный наплыв/спад (как в ваниле), FPS-независимо
                long nns = System.nanoTime();
                float dt2 = (lastFxNs == 0L) ? 0.016f : Math.min((nns - lastFxNs) / 1.0e9f, 0.1f);
                lastFxNs = nns;
                float k2 = 1f - (float) Math.exp(-dt2 / 0.35f);
                smBlind  += (tBlind  - smBlind)  * k2;
                smNight  += (tNight  - smNight)  * k2;
                smPoison += (tPoison - smPoison) * k2;
                smWither += (tWither - smWither) * k2;
                smNausea += (tNausea - smNausea) * k2;
                smDark   += (tDark   - smDark)   * k2;
                smFire   += (tFire   - smFire)   * k2;
                net.vulkanmod.vulkan.rt.RtSnapshot.setEffects(
                        smBlind, smNight, smPoison, smWither, smNausea, smDark, smFire);

                // === Униформы Eclipse (shaders.properties): порог по ЗДОРОВЬЮ в ХИТПОИНТАХ ===
                //   LOW_HEALTH_EFFECT_START = 6.0 (3 сердца), CRITICALLY_LOW = 2.0 (1 сердце).
                //   oneHeart/threeHeart у Iris = smooth(...) — сглаживаем так же.
                float hp = (p2 != null) ? p2.getHealth() : 20f;
                float tOne   = (p2 != null && hp <= 2.0f) ? 1f : 0f;
                float tThree = (p2 != null && hp <= 6.0f) ? 1f : 0f;
                smOneHeart   += (tOne   - smOneHeart)   * k2;
                smThreeHeart += (tThree - smThreeHeart) * k2;

                // Minor/CriticalDamageTaken: ловим ПАДЕНИЕ здоровья и гасим (как smooth у Iris)
                if (p2 != null) {
                    float drop = prevHealth - hp;
                    if (drop > 0.01f) {
                        if (drop >= 4.0f) critDmgFx = 1f;   // крупный урон
                        else              minorDmgFx = 1f;  // мелкий урон
                    }
                    prevHealth = hp;
                }
                critDmgFx  *= (float) Math.exp(-dt2 / 0.60f);   // Iris: fade 3.0 c
                minorDmgFx *= (float) Math.exp(-dt2 / 0.25f);   // Iris: fade 1.0 c

                // exitLava = smooth(isEyeInWater==2 || is_burning, 0.5, 3.0) — дословно
                boolean hot = eyeLava || (p2 != null && p2.isOnFire());
                if (hot) lavaFx = Math.min(1f, lavaFx + dt2 / 0.5f);    // rise 0.5 c
                else     lavaFx = Math.max(0f, lavaFx - dt2 / 3.0f);    // fall 3.0 c

                net.vulkanmod.vulkan.rt.RtSnapshot.setEclipseFx(
                        smOneHeart, smThreeHeart, minorDmgFx, critDmgFx, lavaFx);

                // === Униформы Eclipse (shaders.properties, ДОСЛОВНО) ===
                //   exitWater  = smooth(isEyeInWater==1, 0.0, 5.0)  -> подъём МГНОВЕННЫЙ, спад 5 c
                //   enterWater = smooth(isEyeInWater==1, 1.5, 0.0)  -> подъём 1.5 c, спад МГНОВЕННЫЙ
                // ⚠️ enterWater — НЕ всплеск, а «я уже под водой» (0->1). Всплеск даёт множитель
                // (1-enterWater) в шейдере: максимум в момент входа, гаснет за 1.5 с. Спад ОБЯЗАН
                // быть мгновенным — иначе гейт (enterWater > 0) не закрывается и шум висит вечно.
                if (underwater) {
                    exitWaterFx  = 1f;                                        // rise 0.0
                    enterWaterFx = Math.min(1f, enterWaterFx + dt2 / 1.5f);   // rise 1.5 c
                } else {
                    exitWaterFx  = Math.max(0f, exitWaterFx - dt2 / 5.0f);    // fall 5.0 c
                    enterWaterFx = 0f;                                         // fall 0.0 — МГНОВЕННО
                }
                net.vulkanmod.vulkan.rt.RtSnapshot.setWaterTransition(enterWaterFx, exitWaterFx);
            }
            // M8.5: Y ГЛАДИ над камерой — СКАНОМ КОЛОНКИ ВОДЫ. Уходит в RT: там подводная гладь =
            // аналитическая плоскость Y=wparams.x, её дистанция честно сравнивается с дистанцией
            // до блока => «дыр» быть не может.
            // ⚠️ ВАЖНО, обо что оборвался скан:
            //   * упёрлись в ВОЗДУХ  -> это настоящая гладь (запоминаем уровень);
            //   * упёрлись в БЛОК (навес над головой) -> колонка закрыта, гладь по ней НЕ считается.
            //     Берём последний известный уровень водоёма (он не меняется, пока ты в нём). Иначе
            //     «гладь» падала на уровень камеры под навесом = мусор/дыры.
            float wSurfY = -1.0e9f;
            if (underwater && this.minecraft.level != null) {
                var lvl = this.minecraft.level;
                var p = new net.minecraft.core.BlockPos.MutableBlockPos(
                        Mth.floor(camPos.x()), Mth.floor(camPos.y()), Mth.floor(camPos.z()));
                float lastWaterY = -1.0e9f;
                boolean airAbove = false;
                for (int i = 0; i < 128; i++) {
                    if (lvl.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                        lastWaterY = p.getY() + 0.9f;      // верх этого водного блока ≈ гладь
                        p.move(0, 1, 0);
                        continue;
                    }
                    airAbove = lvl.getBlockState(p).isAir();   // вода кончилась: воздух или блок?
                    break;
                }
                if (airAbove && lastWaterY > -1.0e8f) {
                    wSurfY = lastWaterY;
                    cachedWaterSurfaceY = wSurfY;              // настоящая гладь — запомнили
                } else {
                    wSurfY = cachedWaterSurfaceY;              // под навесом — последний известный уровень
                }
            } else {
                cachedWaterSurfaceY = -1.0e9f;                 // вышли из воды — сброс
            }
            net.vulkanmod.vulkan.rt.RtSnapshot.setWaterSurfaceY(wSurfY);

            // M8.7 СВЕТ: (а) block-light в позиции игрока — им освещается РУКА (у неё нет
            // вершинного light, это меш сущности, и она оставалась чёрной у факела);
            // (б) свет ПРЕДМЕТА В РУКЕ -> динамический источник в RT с настоящими тенями
            // (стиль LambDynamicLights). Берём максимум по обеим рукам.
            float pTarget = 0f, hLight = 0f;
            var pl = this.minecraft.player;
            if (pl != null && this.minecraft.level != null) {
                var lvl = this.minecraft.level;
                // (а) Block-light у игрока — ТРИЛИНЕЙНО по 8 соседним блокам. Сырой getBrightness
                // даёт целые ступени 0..15 и ПРЫГАЕТ при переходе между блоками = резкие скачки
                // света на руке. Интерполяция размазывает переход по блоку.
                double px = camPos.x() - 0.5, py = camPos.y() - 0.5, pz = camPos.z() - 0.5;
                int bx = Mth.floor(px), by = Mth.floor(py), bz = Mth.floor(pz);
                float fx = (float) (px - bx), fy = (float) (py - by), fz = (float) (pz - bz);
                var mp = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int dx = 0; dx <= 1; dx++)
                    for (int dy = 0; dy <= 1; dy++)
                        for (int dz = 0; dz <= 1; dz++) {
                            mp.set(bx + dx, by + dy, bz + dz);
                            float l = lvl.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, mp) / 15.0f;
                            pTarget += l * (dx == 0 ? 1f - fx : fx)
                                         * (dy == 0 ? 1f - fy : fy)
                                         * (dz == 0 ? 1f - fz : fz);
                        }
                // (б) Свет ПРЕДМЕТА В РУКЕ (обе руки) -> динамический источник в RT (+ его ЦВЕТ)
                hLight = net.vulkanmod.vulkan.rt.RtLights.scanHeldItem(pl.getMainHandItem(), pl.getOffhandItem());
                // (в) ЦВЕТНОЙ СВЕТ: скан светящихся блоков вокруг игрока -> SSBO (троттл внутри).
                // Вершинный lightmap знает только УРОВЕНЬ света, но не КТО его излучил, поэтому
                // список источников (позиция+цвет+дальность) собираем здесь и шлём в шейдер.
                net.vulkanmod.vulkan.rt.RtLights.update(lvl, camPos.x(), camPos.y(), camPos.z());
                // (в2) M8.153 ОБЪЁМ ЦВЕТА СВЕТА: решётка смешанного оттенка вокруг камеры. Сама
                // заливка идёт в ФОНОВОМ потоке (см. RtLightVolume) — здесь только проверка
                // «не пора ли пересобрать» и выгрузка готового на GPU.
                net.vulkanmod.vulkan.rt.RtLightVolume.update(camPos.x(), camPos.y(), camPos.z());
            }
            // (г) Свет от МИРА сглаживаем по времени (~150 мс, независимо от FPS) — он меняется
            // непрерывно, и сглаживание убирает рябь на границах освещённости.
            long nowNs = System.nanoTime();
            float dt = (lastLightNs == 0L) ? 0.016f
                     : Math.min((nowNs - lastLightNs) / 1.0e9f, 0.1f);
            lastLightNs = nowNs;
            float k = 1f - (float) Math.exp(-dt / 0.15f);
            smoothPlayerLight += (pTarget - smoothPlayerLight) * k;
            // ...а вот свет ПРЕДМЕТА В РУКЕ — МГНОВЕННО. Смена/уборка предмета — дискретное
            // событие (как в ваниле и LambDynamicLights): факел убрал -> свет пропал сразу.
            // Сглаживание тут давало «плавное затухание» и выглядело как призрак света.
            smoothHeldLight = hLight;
            net.vulkanmod.vulkan.rt.RtSnapshot.setPlayerLight(smoothPlayerLight, smoothHeldLight);
        }
        // === /RT PATCH ===
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        mcProfiler.push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        profiler.push("reposition");
        if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }
        profiler.pop();

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        mcProfiler.popPush("cull");

        mcProfiler.popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.xRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.yRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {
            //Debug
//            this.graphNeedsUpdate = true;

            if (this.graphNeedsUpdate()) {
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.xRot();
                this.lastCamRotY = camera.yRot();

                this.sectionGraph.update(camera, frustum, spectator);
            }
        }

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        mcProfiler.pop();
        profiler.pop();
    }

    public void uploadSections() {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("upload");

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Uploads");

        try {
            if (this.taskDispatcher.updateSections())
                this.graphNeedsUpdate = true;
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
        }

        profiler.pop();

        mcProfiler.pop();
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        // === RT PATCH (M8.98/100): сетка чанков пересоздаётся ОПТОМ — сбрасываем геометрию
        // лучей (иначе старый мир «призраком» в TLAS). Заодно перезагрузка ресурсов (F3+T/
        // смена паков) пересобирает атлас — инвалидируем карту материалов и атлас трещин,
        // иначе вода теряла материал 30 и шейдилась стеклом, металлы гасли. ===
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) {
            net.vulkanmod.vulkan.rt.RtWorld.resetWorld();
            net.vulkanmod.vulkan.rt.RtMaterialMap.invalidate();
            net.vulkanmod.vulkan.rt.RtCracks.invalidate();
            // M8.162: смена паков/F3+T пересобирает и PBR-карты пака — их UV указывают в ТОТ ЖЕ
            // атлас, и после пересборки они увели бы рельеф/спеку на чужие блоки. Сборка ленивая:
            // случится на следующем кадре уже по новому атласу.
            net.vulkanmod.vulkan.rt.RtPbrMaps.invalidate();
            net.vulkanmod.vulkan.rt.RtSnapshot.detectAlphaTagPack();   // M8.126: Actually 3D Stuff
        }
        // === /RT PATCH ===
        if (this.level != null) {
            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        // === RT PATCH (M8.98): выход из мира (level=null) минует allChanged — сбросить тут ===
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported && level == null)
            net.vulkanmod.vulkan.rt.RtWorld.resetWorld();
        // === /RT PATCH ===
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;

//        this.entityRenderDispatcher.setLevel(level);
        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    // === RT PATCH (M8.4): принудительный off-screen рендер ЛОКАЛЬНОГО ИГРОКА в TLAS. ===
    // В ваниле от 1-го лица тело игрока не рисуется (только рука). Мы досабмичиваем его в
    // (пустое на фазе translucent) хранилище сабмитов и сливаем в режиме captureOnly:
    // геометрия+скин попадают в наш захват как «тело игрока», но НЕ на экран (GPU не трогаем,
    // все immediate-дро идут через RenderTypeM.draw, который в captureOnly выходит без рисования).
    // Тело в TLAS => тень+отражение игрока; первичный луч из глаза пропускает его по маске.
    private void capturePlayerBody(double camX, double camY, double camZ) {
        if (!net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) return;
        Minecraft mc = this.minecraft;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        // только 1-е лицо: в 3-м ванилла сама рисует игрока (он уже попадает в TLAS)
        if (!mc.options.getCameraType().isFirstPerson()) return;
        try {
            var player = mc.player;
            float pt = this.partialTick;
            double px = Mth.lerp((double) pt, player.xOld, player.getX()) - camX;
            double py = Mth.lerp((double) pt, player.yOld, player.getY()) - camY;
            double pz = Mth.lerp((double) pt, player.zOld, player.getZ()) - camZ;

            var state = this.entityRenderDispatcher.extractEntity(player, pt);
            SubmitNodeStorage storage = this.featureRenderDispatcher.getSubmitNodeStorage();
            PoseStack pose = new PoseStack();

            net.vulkanmod.vulkan.rt.RtEntities.setCaptureOnly(true);
            this.entityRenderDispatcher.submit(state, this.levelRenderState.cameraRenderState,
                    px, py, pz, pose, storage);
            this.featureRenderDispatcher.renderAllFeatures();   // рисует в общий bufferSource (сам чистит storage)
            // ВАЖНО: renderAllFeatures НЕ делает endBatch — последний слой модели остаётся
            // в буфере и флашится позже (когда captureOnly уже сброшен) => утекает на экран
            // ХАОТИЧНО (какой слой последний — скачет). Дофлашиваем ЗДЕСЬ, в окне captureOnly:
            // тогда и последний слой ловится в захват и НЕ рисуется на экран.
            this.renderBuffers.bufferSource().endBatch();
        } catch (Throwable t) {
            Initializer.LOGGER.error("[RT] capturePlayerBody: {}", t.toString());
        } finally {
            net.vulkanmod.vulkan.rt.RtEntities.setCaptureOnly(false);
        }
    }
    // === /RT PATCH ===

    public void renderSectionLayer(TerrainRenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        // === RT PATCH (M8.3): FOV/аспект + перехват атласа блоков для текстур в RT ===
        if (net.vulkanmod.vulkan.device.DeviceManager.rayTracingSupported) {
            net.vulkanmod.vulkan.rt.RtSnapshot.setProjection(projection.m00(), projection.m11());
            // ⚠️ БАЗИС ЛУЧЕЙ — ИЗ МАТРИЦЫ ВИДА, А НЕ ИЗ ВЕКТОРОВ КАМЕРЫ (M8.74). Раньше я брал
            // forward/up/left прямо у объекта Camera. Но моды вроде Camera Overhaul качают камеру,
            // правя МАТРИЦУ ВИДА (наклон при беге, крен при падении) — в векторах камеры этого нет,
            // и трассировка о наклоне просто не знала: растр качался, лучи стояли. Берём базис из той
            // самой матрицы, по которой игра рисует мир: её строки и есть оси камеры в мире.
            // Так любой мод, который крутит вид, заработает сам, без правок под каждого.
            // ПОЛНАЯ матрица вида = поза покачивания × матрица камеры. Покачивание игра держит
            // ОТДЕЛЬНО (см. RtSnapshot.setBobPose): она заливает его в свой буфер преобразований, а
            // в отрисовку мира отдаёт матрицу БЕЗ него. Поэтому под VulkanMod не работало даже
            // ванильное покачивание, а Camera Overhaul (он правит ту же позу) терялся вместе с ним.
            org.joml.Matrix4f view = new org.joml.Matrix4f(net.vulkanmod.vulkan.rt.RtSnapshot.bobPose())
                    .mul(modelView);
            org.joml.Vector3f mvRight = new org.joml.Vector3f(view.m00(), view.m10(), view.m20());
            org.joml.Vector3f mvUp    = new org.joml.Vector3f(view.m01(), view.m11(), view.m21());
            org.joml.Vector3f mvBack  = new org.joml.Vector3f(view.m02(), view.m12(), view.m22());
            if (mvBack.lengthSquared() > 1e-6f) {
                mvRight.normalize(); mvUp.normalize(); mvBack.normalize();
                // Покачивание не только наклоняет, но и СДВИГАЕТ камеру (это и есть ощущение шага).
                // Сдвиг лежит в переносе матрицы вида — разворачиваем его обратно в мир:
                // P = -(right*tx + up*ty + back*tz).
                float tx = view.m30(), ty = view.m31(), tz = view.m32();
                double ox = camX - (mvRight.x * tx + mvUp.x * ty + mvBack.x * tz);
                double oy = camY - (mvRight.y * tx + mvUp.y * ty + mvBack.y * tz);
                double oz = camZ - (mvRight.z * tx + mvUp.z * ty + mvBack.z * tz);
                net.vulkanmod.vulkan.rt.RtSnapshot.setCamera(ox, oy, oz,
                        -mvBack.x, -mvBack.y, -mvBack.z,       // forward = -back
                        mvUp.x, mvUp.y, mvUp.z,
                        -mvRight.x, -mvRight.y, -mvRight.z);   // left = -right
            }
            net.vulkanmod.vulkan.rt.RtSnapshot.grabBlockAtlas();   // по имени, а не по догадке
            // M8.4: сущности отрисованы ДО translucent-слоя. Досабмичиваем ТЕЛО ИГРОКА
            // (в 1-м лице ванилла его не рисует) и закрываем окно сбора. Всё после —
            // рука 1-го лица/GUI — в трассировку не попадает.
            if (renderType == TerrainRenderType.TRANSLUCENT) {
                capturePlayerBody(camX, camY, camZ);
                net.vulkanmod.vulkan.rt.RtEntities.endLevel();
            }
        }
        // === /RT PATCH ===

        // === RT PATCH (M8.124): фолбэк — снимок глубины ТВЕРДИ до прозрачного слоя (вода).
        // Вне гейта rayTracingSupported: фолбэк живёт и на железе без лучей. Сам метод
        // гейтится по режиму; rebindMainTarget ниже возобновит проход после копии. ===
        if (renderType == TerrainRenderType.TRANSLUCENT
                && Renderer.getInstance().getMainPass() instanceof net.vulkanmod.vulkan.pass.DefaultMainPass dmp)
            dmp.captureSolidDepth(Renderer.getCommandBuffer());
        // === /RT PATCH ===

        Renderer.getInstance().getMainPass().rebindMainTarget();

        this.sortTranslucentSections(camX, camY, camZ);

        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        Zone zone = mcProfiler.zone(() -> "render_" + renderType);

        final boolean isTranslucent = renderType == TerrainRenderType.TRANSLUCENT;
        final boolean indirectDraw = Initializer.CONFIG.indirectDraw;

        if (!isTranslucent) {
            GlStateManager._disableBlend();
        } else {
            GlStateManager._enableBlend();
            VRenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        VRenderSystem.enableCull();
        VRenderSystem.depthFunc(GL11.GL_LEQUAL);

        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._disablePolygonOffset();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getTerrainShader(renderType);
        renderer.bindGraphicsPipeline(pipeline);

        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture atlasTexture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
        var texView = atlasTexture.getTextureView();
        boolean useAnisotropy = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC;
        int maxAnisotropy = this.minecraft.options.maxAnisotropyValue();
        var texture = (VkGpuTexture)texView.texture();

        if (this.terrainSampler == 0L) {
            this.terrainSampler = SamplerManager.getSampler(true, true, texture.getVulkanImage().mipLevels - 1, useAnisotropy, maxAnisotropy);
        }

        texture.getVulkanImage().setSampler(this.terrainSampler);

        VRenderSystem.setShaderTexture(0, texView);
        VRenderSystem.setShaderTexture(2, Minecraft.getInstance().gameRenderer.lightTexture().getTextureView());

        VTextureSelector.bindShaderTextures(pipeline);

        int atlasTexWidth = texView.getWidth(0);
        int atlasTexHeight = texView.getHeight(0);

        VRenderSystem.setTextureSize(atlasTexWidth, atlasTexHeight);
        VRenderSystem.setCurrentTime((int) System.currentTimeMillis());

        long currentTimeMs = System.currentTimeMillis();
        float fadeTime = Minecraft.getInstance().options.chunkSectionFadeInTime().get().floatValue();
        int fadeTimeMs = (int) (fadeTime * 1000);
        float fadeTimeInv = fadeTime > 0 ? 1 / (fadeTime * 1000) : 1;

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(renderType)) {
            renderType.setCutoutUniform();

            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                if (drawBuffers.getAreaBuffer(renderType) != null && queue.size() > 0) {

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, renderType,
                                            camX, camY, camZ,
                                            currentTimeMs, fadeTimeMs, fadeTimeInv);

                    renderer.uploadAndBindUBOs(pipeline);

                    if (indirectDraw) {
                        drawBuffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame], queue, renderType);
                    }
                    else {
                        drawBuffers.buildDrawBatchesDirect(cameraPos, queue, renderType);
                    }
                }
            }
        }

        if (renderType == TerrainRenderType.CUTOUT || renderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();
//            uniformBuffers.submitUploads();
        }

        // Need to reset push constants in case the pipeline will still be used for rendering
        if (!indirectDraw) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        zone.close();
    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        ProfilerFiller mcProfiler = net.minecraft.util.profiling.Profiler.get();
        mcProfiler.push("translucent_sort");
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;
        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            int j = 0;

            Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

            while (iterator.hasNext() && j < 200) {
                RenderSection section = iterator.next();
                section.resortTransparency(this.taskDispatcher, this.cameraPos);

                if (!section.isCompletelyEmpty()) {
                    ++j;
                }
            }
        }

        mcProfiler.pop();
    }

    public void renderBlockEntities(PoseStack poseStack, LevelRenderState levelRenderState,
                                    SubmitNodeStorage submitNodeStorage,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Block-entities");

        Vec3 vec3 = levelRenderState.cameraRenderState.pos;
        double camX = vec3.x();
        double camY = vec3.y();
        double camZ = vec3.z();

        for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    SortedSet<BlockDestructionProgress> sortedSet = destructionProgress.get(blockPos.asLong());
                    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay;
                    if (sortedSet != null && !sortedSet.isEmpty()) {
                        poseStack.pushPose();
                        poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
                        crumblingOverlay = new ModelFeatureRenderer.CrumblingOverlay(sortedSet.last()
                                                                                              .getProgress(), poseStack.last());
                        poseStack.popPose();
                    } else {
                        crumblingOverlay = null;
                    }

                    BlockEntityRenderState blockEntityRenderState = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity, this.partialTick, crumblingOverlay);
                    if (blockEntityRenderState != null) {
                        levelRenderState.blockEntityRenderStates.add(blockEntityRenderState);
                    }
                }
            }
        }

        Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

        while (iterator.hasNext()) {
            BlockEntity blockEntity2 = iterator.next();
            if (blockEntity2.isRemoved()) {
                iterator.remove();
            } else {
                BlockEntityRenderState blockEntityRenderState2 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockEntity2, this.partialTick, null);
                if (blockEntityRenderState2 != null) {
                    levelRenderState.blockEntityRenderStates.add(blockEntityRenderState2);
                }
            }
        }

        for (BlockEntityRenderState blockEntityRenderState : levelRenderState.blockEntityRenderStates) {
            BlockPos blockPos = blockEntityRenderState.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
            var blockEntityRenderDispatcher = this.minecraft.getBlockEntityRenderDispatcher();
            blockEntityRenderDispatcher.submit(blockEntityRenderState, poseStack, submitNodeStorage, levelRenderState.cameraRenderState);
            poseStack.popPose();
        }
    }

    public void resetSampler() {
        this.terrainSampler = 0L;
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        if (this.sectionGrid == null)
            return null;
        return this.sectionGrid.chunkAreaManager;
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        if (this.sectionGraph == null) {
            return null;
        }

        return this.sectionGraph.getStatistics();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::scheduleFree);
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vector3d getCameraPos() {
        return INSTANCE.cameraPos;
    }

}
