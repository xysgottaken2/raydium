package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.162): PBR-МАТЕРИАЛЫ ИЗ РЕСУРСПАКОВ (labPBR: _n + _s) ===
// Задача: чтобы трассировка читала нормали, шероховатость, металличность и эмиссию ИЗ ПАКА, а не
// только по нашей таблице MATERIAL (RtMaterialMap), где материал зашит по имени спрайта.
//
// ЧТО ЧИТАЕМ. Формат — labPBR (shaderLABS, «LabPBR Material Standard»): паки AVPBR Retextured,
// SPBR (Shulker's PBR), Vanilla PBR и десятки им подобных кладут рядом с текстурой две карты:
//   block/stone.png  →  block/stone_n.png (нормаль) и block/stone_s.png (спека)
// Так же, как это делает Iris (он собирает из этих файлов атласы blocks_n/blocks_s), поэтому
// отдельный properties-файл НЕ НУЖЕН: соглашение об суффиксах и есть стандарт этих паков.
//
// СХЕМА КАНАЛОВ labPBR 1.3 (по ней и разбираем):
//   _n: R = x, G = y (DirectX: зелёный растёт ВНИЗ по картинке), B = ЗАТЕНЕНИЕ (AO), A = высота (POM)
//       ⚠️ В labPBR синий канал _n — НЕ Z! Z восстанавливается как sqrt(1 - x*x - y*y) — именно так
//       делают шейдерпаки, чтобы отдать B-канал под AO. Мы считаем Z при сборке карты и кладём в B
//       НАШЕЙ карты уже ЕДИНИЧНЫЙ вектор: в шейдере не нужен sqrt на каждый пиксель, а усреднение
//       при уменьшении идёт ПО ВЕКТОРАМ (иначе нормали «худеют», как при мипах без пересчёта).
//       AO пака пока не используем — у нас честный трассируемый амбиент.
//   _s: R = перцептивная ГЛАДКОСТЬ (roughness = (1 - s)^2), G = F0 (0..229 линейно) или МЕТАЛЛ
//       (230..237 — идентификаторы железа/золота/алюминия/хрома/меди/свинца/платины/серебра,
//        255 = F0 равно альбедо), B = пористость (0..64) или SSS (65..255), A = ЭМИССИЯ (0..254,
//        где 255 = «не светится»: так выглядит обычный PNG без альфы).
//
// ПОЧЕМУ АТЛАС ТОГО ЖЕ РАЗМЕРА, а не набор отдельных текстур. Шейдер уже сэмплит атлас по UV
// попадания; карта материалов живёт ТОЧНО в этих же UV. Держим PBR в том же пространстве — тогда
// шейдеру не нужны ни таблица «спрайт→текстура», ни второй слой UV, ни texture-array: одна выборка
// по уже известному uv. Разница разрешений (пак часто даёт _n в 2-8 раз крупнее диффуза) снимается
// усреднением по площади при сборке (см. blitNormal/blitSpecular).
//
// ⚠️ ПАМЯТЬ. Карта — RGBA8 на ВЕСЬ атлас (как и карта материалов). Атлас HD-пака бывает 8192², это
// 256 МБ на карту. Поэтому при атласе больше 4096 карту PBR пишем ВДВОЕ мельче: шейдер сэмплит её
// по НОРМИРОВАННЫМ UV, и половинное разрешение нормали/шероховатости незаметно (они низкочастотны
// рядом с альбедо), а память падает вчетверо. Если PBR-карт в паках нет вовсе — не строим НИЧЕГО
// (две пустые текстуры на весь атлас = та самая память ни за что), а шейдеру отдаём ЗАГЛУШКИ 1x1:
// в них «нет данных» (плоская нормаль, погашенная спека), и шейдер по ним не меняет ничего.
//
// ОТКУДА СПИСОК КАРТ. Не из приватного поля атласа (миксин ради этого не нужен): карты паков — это
// ОБЫЧНЫЕ ФАЙЛЫ, и ResourceManager.listResources отдаёт их все, включая паки-архивы и модовые
// неймспейсы. Из имени файла (textures/block/stone_n.png) получаем имя спрайта (block/stone) и
// просим его у атласа: он сам скажет UV, куда мы и положим разобранную карту.

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.lwjgl.vulkan.VK10.*;

public class RtPbrMaps {

    // ФОН = «в паке данных нет». Нормаль плоская (0,0,1), спека погашена целиком: гладкость 0
    // (шероховатость 1 — ничего не зеркалит), F0 = 0, пористость 0, эмиссия 255 («не светится»).
    // ⚠️ Именно поэтому шейдеру НЕ НУЖЕН отдельный признак «тут данные пака»: отсутствие данных
    // само по себе означает «ничего не добавляем» (см. композицию через max/min в RtSnapshot).
    private static final byte[] SPEC_NO_DATA = {0, 0, 0, (byte) 255};
    // Плоская нормаль (0,0,1) в байтах — «данных пака нет» (то же, чем залит фон карты).
    private static final byte[] SPEC_FLAT = {(byte) 128, (byte) 128, (byte) 255, (byte) 255};

    private static VulkanImage normalTex;
    private static VulkanImage materialTex;
    private static boolean ready = false;
    private static boolean failed = false;
    private static int spritesN = 0, spritesS = 0;

    // Заглушки 1x1 «нет данных» — их видит шейдер, когда пака нет, карты ещё собираются или
    // ресурсы перезагружаются прямо сейчас. Подставлять вместо них АТЛАС нельзя: шейдер принял бы
    // альбедо за нормаль и развернул бы освещение. А 1x1 с плоской нормалью не искажает НИЧЕГО.
    private static VulkanImage flatTex, emptyTex;

    // M8.100-приём как в RtMaterialMap: старая текстура могла читаться кадрами в полёте — рушим её
    // ОТЛОЖЕННО, спустя несколько кадров (см. frameTick).
    private static final ArrayList<VulkanImage> retired = new ArrayList<>();
    private static int retireIn = -1;   // -1 = ждать нечего; иначе — через сколько кадров освобождать

    public static boolean ready() { return ready; }

    /** Нормали в UV атласа (или заглушка «нет данных» — она гарантированно не null). */
    public static VulkanImage normals() {
        if (normalTex != null) return normalTex;
        if (flatTex == null) flatTex = uploadBytes(SPEC_FLAT, 1, 1);
        return flatTex;
    }

    /** Спека в UV атласа (или заглушка «нет данных» — она гарантированно не null). */
    public static VulkanImage materials() {
        if (materialTex != null) return materialTex;
        if (emptyTex == null) emptyTex = uploadBytes(SPEC_NO_DATA, 1, 1);
        return emptyTex;
    }

    /** Смена паков / F3+T — атлас пересобран, UV спрайтов уехали: карты недействительны. */
    public static synchronized void invalidate() {
        if (normalTex != null) { retired.add(normalTex); normalTex = null; }
        if (materialTex != null) { retired.add(materialTex); materialTex = null; }
        if (!retired.isEmpty()) retireIn = 3;   // три кадра — в полёте их уже не читает никто
        ready = false;
        failed = false;
        spritesN = 0; spritesS = 0;             // ⚠️ иначе второй пак в логе «складывался» бы с первым
    }

    /** Зовётся КАЖДЫЙ кадр из RT-прохода: освобождает отставные текстуры, когда кадры их отпустили. */
    public static synchronized void frameTick() {
        if (retireIn < 0) return;
        if (--retireIn > 0) return;
        for (VulkanImage img : retired) img.free();
        retired.clear();
        retireIn = -1;
    }

    /** Ленивая сборка (зовётся каждый кадр из записи дескрипторов, пока не выйдет). */
    public static void init() {
        if (ready || failed) return;
        if (!Initializer.CONFIG.rtPbr) return;   // ручка выключена — паки не читаем вообще
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            ResourceManager rm = mc.getResourceManager();
            if (rm == null) return;
            var abs = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (!(abs instanceof TextureAtlas atlas)) return;

            // Готов ли атлас? Та же проба, что в RtMaterialMap: если известный спрайт ещё «missing»,
            // атлас не сшит — выходим и попробуем в следующем кадре.
            Identifier probeId = Identifier.withDefaultNamespace("block/stone");
            TextureAtlasSprite probe = atlas.getSprite(probeId);
            if (probe == null || !probeId.equals(probe.contents().name())) return;

            // Размер атласа — из РЕАЛЬНО захваченного образа (пропорция UV даёт ±1 тексель на паках).
            int mapW = Math.round(probe.contents().width() / (probe.getU1() - probe.getU0()));
            int mapH = Math.round(probe.contents().height() / (probe.getV1() - probe.getV0()));
            var atlasImg = RtSnapshot.atlasImage();
            if (atlasImg != null) { mapW = atlasImg.width; mapH = atlasImg.height; }
            if (mapW < 256 || mapH < 256 || mapW > 8192 || mapH > 8192) {
                Initializer.LOGGER.warn("[RT] PBR: implausible atlas size {}x{} - material packs skipped", mapW, mapH);
                failed = true;
                return;
            }
            int div = Math.max(mapW, mapH) > 4096 ? 2 : 1;   // см. «ПАМЯТЬ» в шапке
            int pbrW = Math.max(mapW / div, 256), pbrH = Math.max(mapH / div, 256);

            // Карты пака: <спрайт> -> {нормаль, спека}. Один проход по индексу паков.
            Map<Identifier, Identifier[]> found = scan(rm);
            if (found.isEmpty()) {
                // Паки есть, PBR в них нет. Запоминаем это как «пробовали» (failed), иначе каждый
                // кадр заново обходили бы весь индекс ресурсов.
                failed = true;
                Initializer.LOGGER.info("[RT] PBR: no _n/_s maps found in resource packs - using built-in materials only");
                return;
            }

            // ⚠️ КАРТЫ КЛЕИМ СРАЗУ В ПРЯМУЮ ПАМЯТЬ, без массива-посредника: на атласе 8192 это
            // 64 МБ на карту, и вторая такая же копия под загрузку удвоила бы пик (на слабой машине
            // это уже не «много», а отказ). Пик держим минимальным: буферы освобождаются СРАЗУ после
            // загрузки в VRAM, в finally — то есть и в ветке «карт не нашлось» тоже.
            ByteBuffer nrm = MemoryUtil.memAlloc(pbrW * pbrH * 4);
            ByteBuffer mat = MemoryUtil.memAlloc(pbrW * pbrH * 4);
            try {
                fillNoData(nrm, mat, pbrW, pbrH);

                for (Map.Entry<Identifier, Identifier[]> e : found.entrySet()) {
                    Identifier spriteId = e.getKey();
                    TextureAtlasSprite sprite = atlas.getSprite(spriteId);
                    if (sprite == null || !spriteId.equals(sprite.contents().name())) continue;   // в атласе его нет
                    NativeImage nImg = read(rm, e.getValue()[0]);
                    NativeImage sImg = read(rm, e.getValue()[1]);
                    if (nImg == null && sImg == null) continue;
                    try {
                        int x0 = Math.round(sprite.getU0() * pbrW);
                        int y0 = Math.round(sprite.getV0() * pbrH);
                        int dw = Math.max(1, Math.round((sprite.getU1() - sprite.getU0()) * pbrW));
                        int dh = Math.max(1, Math.round((sprite.getV1() - sprite.getV0()) * pbrH));
                        int sw = Math.max(1, sprite.contents().width());
                        int sh = Math.max(1, sprite.contents().height());
                        if (nImg != null && blitNormal(nImg, sw, sh, nrm, pbrW, pbrH, x0, y0, dw, dh)) spritesN++;
                        if (sImg != null && blitSpecular(sImg, sw, sh, mat, pbrW, pbrH, x0, y0, dw, dh)) spritesS++;
                    } finally {
                        if (nImg != null) nImg.close();
                        if (sImg != null) sImg.close();
                    }
                }

                if (spritesN == 0 && spritesS == 0) {
                    failed = true;   // карты есть, но ни один их спрайт в блочный атлас не попал (пак для сущностей?)
                    Initializer.LOGGER.info("[RT] PBR: maps found in packs but none of their sprites are in the block atlas");
                    return;
                }
                long mb = (long) pbrW * pbrH * 4L * 2L / (1024 * 1024);
                normalTex = upload(nrm, pbrW, pbrH);
                materialTex = upload(mat, pbrW, pbrH);
                ready = true;
                Initializer.LOGGER.info("[RT] PBR maps {}x{} (atlas {}x{}, /{}): {} normal, {} specular sprites, ~{} MB VRAM",
                        pbrW, pbrH, mapW, mapH, div, spritesN, spritesS, mb);
            } finally {
                MemoryUtil.memFree(nrm);
                MemoryUtil.memFree(mat);
            }
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] PBR maps build failed: ", t);
        }
    }

    /**
     * ПОИСК КАРТ ПАКА. Обходим ресурсы с суффиксами _n/_s по ВСЕМУ индексу паков (файлы и архивы,
     * все неймспейсы) и складываем пары «спрайт → файлы карт». Файл textures/block/stone_n.png даёт
     * спрайт block/stone: именно это имя у него в атласе (проверяем у самого атласа). Работает и для
     * НЕ-блочных текстур (item/…, модовых), которые тоже лежат в блочном атласе.
     * ⚠️ Ресурсы паков читаем как ФАЙЛЫ, а не через приватное поле атласа: так видны и паки-архивы,
     * и модовые неймспейсы, и обход не зависит от того, что атлас уже успел сшить.
     */
    private static Map<Identifier, Identifier[]> scan(ResourceManager rm) {
        Map<Identifier, Identifier[]> out = new HashMap<>();
        Map<Identifier, Resource> hits = rm.listResources("textures", id -> {
            String p = id.getPath();
            return p.endsWith("_n.png") || p.endsWith("_s.png");
        });
        for (Identifier file : hits.keySet()) {
            String p = file.getPath();                       // textures/block/stone_n.png
            if (!p.startsWith("textures/") || p.length() <= "textures/".length() + 6) continue;
            boolean normal = p.endsWith("_n.png");
            String sprite = p.substring("textures/".length(), p.length() - 6);   // срезаем _n.png / _s.png
            if (sprite.isEmpty()) continue;
            Identifier spriteId = Identifier.fromNamespaceAndPath(file.getNamespace(), sprite);
            Identifier[] slot = out.computeIfAbsent(spriteId, k -> new Identifier[2]);
            if (normal) slot[0] = file; else slot[1] = file;
        }
        return out;
    }

    /** Прочитать карту пака (или null, если её нет/битая). */
    private static NativeImage read(ResourceManager rm, Identifier loc) {
        if (loc == null) return null;
        Optional<Resource> res = rm.getResource(loc);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open()) {
            return NativeImage.read(in);
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[RT] PBR: cannot read {}: {}", loc, t.toString());
            return null;
        }
    }

    /**
     * КАДР КАРТЫ. Анимированные PBR-карты (у воды/огня они бывают) лежат стопкой кадров по вертикали,
     * как в атласе MC: берём ПЕРВЫЙ кадр. Анимационные метаданные при этом не читаем — их отсутствие
     * ничего не ломает, а кадр вычисляем из размеров: если карта кратна спрайту по ширине, а по высоте
     * укладывается в целое число таких же квадратов, то это стопка.
     */
    private static int[] frame(NativeImage img, int sw, int sh) {
        int w = img.getWidth(), h = img.getHeight();
        int fw = w, fh = h;
        if (sw > 0 && sh > 0 && w % sw == 0 && h % sh == 0) {
            int k = w / sw;                       // во сколько раз карта крупнее спрайта
            int rows = h / sh;
            if (k > 0 && rows % k == 0) { fw = k * sw; fh = k * sh; }   // первый кадр стопки
        }
        return new int[]{fw, fh};
    }

    private static void fillNoData(ByteBuffer nrm, ByteBuffer mat, int w, int h) {
        for (int i = 0; i < w * h; i++) {
            int o = i * 4;
            nrm.put(o, SPEC_FLAT[0]).put(o + 1, SPEC_FLAT[1]).put(o + 2, SPEC_FLAT[2]).put(o + 3, SPEC_FLAT[3]);
            mat.put(o, SPEC_NO_DATA[0]).put(o + 1, SPEC_NO_DATA[1])
               .put(o + 2, SPEC_NO_DATA[2]).put(o + 3, SPEC_NO_DATA[3]);
        }
    }

    /** Заглушка 1x1 из массива байт: собрать прямой буфер, загрузить, освободить. */
    private static VulkanImage uploadBytes(byte[] bytes, int w, int h) {
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
        buf.put(bytes);
        // ⚠️ Позиция обязана быть НУЛЕВОЙ: загрузка берёт адрес как memAddress(buf), а тот
        // прибавляет к базе текущую позицию — со сдвинутой позицией читалась бы память ЗА буфером.
        buf.flip();
        try {
            return upload(buf, w, h);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /**
     * НОРМАЛЬ: усредняем ВЕКТОРЫ, а не байты (иначе выпуклая поверхность «сплющивается»), Z считаем
     * из xy, как велит labPBR, и кладём в нашу карту ЕДИНИЧНЫЙ вектор (x,y,z), закодированный как
     * (v*0.5+0.5). Плоская точка даёт ровно (128,128,255) — то же, что фон, поэтому «нет данных» и
     * «плоская поверхность» для шейдера неразличимы и не требуют ветвлений.
     * ⚠️ NativeImage.getPixel = ARGB (в 1.21.11 это ARGB.fromABGR(getPixelABGR), проверено по
     * исходнику), поэтому R = p>>16, G = p>>8, B = p. Перепутать каналы — значит развернуть рельеф.
     */
    private static boolean blitNormal(NativeImage src, int sw, int sh, ByteBuffer dst, int dstW, int dstH,
                                      int dx0, int dy0, int dw, int dh) {
        int[] fr = frame(src, sw, sh);
        int srcW = Math.min(fr[0], src.getWidth()), srcH = Math.min(fr[1], src.getHeight());
        if (srcW < 1 || srcH < 1) return false;
        int painted = 0;
        for (int dy = 0; dy < dh; dy++) {
            int ty = dy0 + dy;
            if (ty < 0 || ty >= dstH) continue;
            int sy0 = dy * srcH / dh, sy1 = Math.max(sy0 + 1, (dy + 1) * srcH / dh);
            for (int dx = 0; dx < dw; dx++) {
                int tx = dx0 + dx;
                if (tx < 0 || tx >= dstW) continue;
                int sx0 = dx * srcW / dw, sx1 = Math.max(sx0 + 1, (dx + 1) * srcW / dw);
                float ax = 0f, ay = 0f, az = 0f;
                int n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int p = src.getPixel(sx, sy);
                        // ⚠️ ПРОПУСКАЕМ ТОЛЬКО ПУСТОЙ ПИКСЕЛЬ (альфа 0 И чёрный): в labPBR альфа _n
                        // — это ВЫСОТА для POM, а не признак «нет данных», и ноль там законен
                        // (спецификация лишь советует художникам не ставить его без нужды).
                        // Отбрасывать всё с альфой меньше 8 значило бы терять рельеф на ровном месте.
                        if (((p >>> 24) & 0xFF) == 0 && (p & 0xFFFFFF) == 0) continue;
                        float nx = ((p >> 16) & 0xFF) / 127.5f - 1f;
                        float ny = ((p >> 8) & 0xFF) / 127.5f - 1f;
                        float z2 = 1f - nx * nx - ny * ny;          // labPBR: Z восстанавливаем, B-канал = AO
                        ax += nx; ay += ny; az += z2 > 1e-4f ? (float) Math.sqrt(z2) : 0f;
                        n++;
                    }
                }
                if (n == 0) continue;
                float l = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                float nx, ny, nz;
                if (l < 1e-5f) { nx = 0f; ny = 0f; nz = 1f; }        // «в ноль» усреднилось -> плоско
                else { nx = ax / l; ny = ay / l; nz = az / l; }
                int o = (ty * dstW + tx) * 4;
                dst.put(o,     (byte) Math.round((nx * 0.5f + 0.5f) * 255f));
                dst.put(o + 1, (byte) Math.round((ny * 0.5f + 0.5f) * 255f));
                dst.put(o + 2, (byte) Math.round((nz * 0.5f + 0.5f) * 255f));
                dst.put(o + 3, (byte) 255);
                painted++;
            }
        }
        return painted > 0;
    }

    /**
     * СПЕКА. Каналы храним КАК В ПАКЕ (шейдер сам разберёт labPBR) — ни одна деталь не теряется.
     * ⚠️ ЗЕЛЁНЫЙ — ОСОБЫЙ: 230..254 это ИДЕНТИФИКАТОР металла, а не число. Среднее от золота (231) и
     * серебра (237) дало бы 234 = МЕДЬ, то есть пак «переспорил» бы сам себя. Поэтому в области
     * усреднения считаем металлы и берём БЛИЖАЙШИЙ металл, если металлов больше половины; иначе
     * усредняем только диэлектрическую часть (там F0 — обычное линейное число).
     */
    private static boolean blitSpecular(NativeImage src, int sw, int sh, ByteBuffer dst, int dstW, int dstH,
                                        int dx0, int dy0, int dw, int dh) {
        int[] fr = frame(src, sw, sh);
        int srcW = Math.min(fr[0], src.getWidth()), srcH = Math.min(fr[1], src.getHeight());
        if (srcW < 1 || srcH < 1) return false;
        int painted = 0;
        for (int dy = 0; dy < dh; dy++) {
            int ty = dy0 + dy;
            if (ty < 0 || ty >= dstH) continue;
            int sy0 = dy * srcH / dh, sy1 = Math.max(sy0 + 1, (dy + 1) * srcH / dh);
            for (int dx = 0; dx < dw; dx++) {
                int tx = dx0 + dx;
                if (tx < 0 || tx >= dstW) continue;
                int sx0 = dx * srcW / dw, sx1 = Math.max(sx0 + 1, (dx + 1) * srcW / dw);
                long sr = 0, sb = 0, sa = 0;                     // гладкость, пористость, эмиссия
                long f0sum = 0; int f0n = 0;
                int metalNear = -1, metalCnt = 0, n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int p = src.getPixel(sx, sy);
                        // ⚠️ ЗДЕСЬ НЕ ПРОПУСКАЕМ НИЧЕГО. Альфа _s — это ЭМИССИЯ, и 0 в ней значит
                        // «не светится» (законное значение): паки, которые держат свечение в альфе,
                        // залили бы ею всю карту, и «пропуск по альфе» стёр бы заодно гладкость и F0
                        // везде, кроме светящихся пикселей — блоки остались бы БЕЗ отражений.
                        int cr = (p >> 16) & 0xFF, cg = (p >> 8) & 0xFF, cb = p & 0xFF, ca = (p >>> 24) & 0xFF;
                        sr += cr; sb += cb; sa += ca;
                        if (cg >= 230) { metalCnt++; metalNear = cg; }
                        else { f0sum += cg; f0n++; }
                        n++;
                    }
                }
                if (n == 0) continue;
                int g = (metalCnt * 2 >= n && metalNear >= 0)
                        ? metalNear                                  // металл — берём как есть
                        : (f0n > 0 ? (int) Math.round((double) f0sum / f0n) : 0);
                int o = (ty * dstW + tx) * 4;
                dst.put(o,     (byte) Math.round((double) sr / n));
                dst.put(o + 1, (byte) g);
                dst.put(o + 2, (byte) Math.round((double) sb / n));
                dst.put(o + 3, (byte) Math.round((double) sa / n));
                painted++;
            }
        }
        return painted > 0;
    }

    /**
     * ⚠️ ФИЛЬТРАЦИЯ ЛИНЕЙНАЯ — и это принципиально: нормаль и шероховатость должны смешиваться между
     * текселями (ступеньки нормали читаются как грани). Края спрайтов при этом НЕ текут: шейдер
     * зажимает UV внутрь спрайта по полтекселя (triUV), поэтому выборка у самой кромки попадает ровно
     * в крайний тексель. У карты материалов фильтр, наоборот, точечный: matId — это метка, её
     * смешивать нельзя (соседний материал «напылялся» бы на блок).
     */
    private static VulkanImage upload(ByteBuffer buf, int w, int h) {
        VulkanImage image = VulkanImage.builder(w, h)
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        // ⚠️ Освобождает буфер ЗОВУЩИЙ: он же владеет им и до, и после загрузки (см. init).
        image.uploadSubTextureAsync(0, 0, w, h, 0, 0, 0, 0, w, buf);
        return image;
    }
}
