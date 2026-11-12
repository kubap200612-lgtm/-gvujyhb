package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Panel Rynek Analiza: zakładki "Przedmioty" i "Ustawienia".
 *
 * Przedmioty to jedna długa lista przewijana kółkiem myszy albo paskiem z prawej. Wiersz: ikona, nazwa, miniwykres,
 * średnia 3 najtańszych z ostatniej analizy, cena sprzedaży w BK (wpisuje ją analiza), pole ceny skupu i przycisk "Wykres",
 * który rozwija pod wierszem pełny wykres wszystkich analiz przedmiotu ze statystykami.
 *
 * Cenę skupu ustawia gracz: wpisana w pole trafia do BK chwilę po ostatnim wpisanym znaku (BUY_DEBOUNCE_MS), żeby przy
 * wpisywaniu "14000" do BK nie trafiły ceny pośrednie 1, 14, 140...; zamknięcie panelu zapisuje od razu.
 */
public class AnalizaScreen extends Screen {
    private enum Tab { ITEMS, SETTINGS }

    private interface RowBuilder { void build(int x, int y, int w); }
    private record SettingRow(String label, String shortLabel, RowBuilder builder) {
        SettingRow(String label, RowBuilder builder) { this(label, label, builder); }
    }

    /** Wiersz listy: przedmiot, jego widżety i położenie w całej (przewijanej) zawartości. */
    private static final class Row {
        final BkBridge.BkTarget t;
        TextFieldWidget field;
        ButtonWidget button;
        boolean open;
        int top, height;
        ItemStack icon;
        Row(BkBridge.BkTarget t) { this.t = t; }
    }

    /** Zwłoka między ostatnim wpisanym znakiem ceny skupu a zapisem do BK (ms). Pole nie jest final - ustawia je test. */
    static long BUY_DEBOUNCE_MS = 1000;
    /** Ile ostatnich skanów pokazuje wykres i liczą statystyki. */
    static final int CHART_SCANS = Integer.MAX_VALUE; // wszystkie analizy

    private static final int ROW_H = 24;
    private static final int SCROLL_STEP = 24;
    private static final int BLOCK_EXTRA = 88; // wysokość rozwiniętego bloku poza samym wykresem (nagłówek, osie, karty, linia szczegółów, odstęp)
    private static final int EMPTY_BLOCK_H = 56; // rozwinięty blok przedmiotu bez żadnego skanu

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int LIGHT = 0xFFDDDDDD;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    // wykres
    private static final int ACCENT = 0xFF58B8FF;
    private static final int MEAN = 0xFFE0A84A;
    private static final int UP = 0xFF5FD38D;
    private static final int DOWN = 0xFFE5534B;
    private static final int BLOCK_BG = 0xFF15171D;
    private static final int BLOCK_BORDER = 0xFF2E323D;
    private static final int PLOT_BG = 0xFF0E1015;
    private static final int CARD_BG = 0xFF1C1F27;
    private static final int CARD_BORDER = 0xFF2A2E39;
    private static final int GRID = 0x26FFFFFF;

    private static final String[] SPEED_NAMES = {"Bezpieczna", "Szybka", "Maksymalna"};
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter F_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final DateTimeFormatter F_DATE = DateTimeFormatter.ofPattern("dd.MM").withZone(ZONE);
    private static final DateTimeFormatter F_FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZONE);
    private static final long STATUS_MS = 15_000L;
    /** Połowa odstępu między przyciskami < i > przy numerze strony ustawień. */
    private static final int PAGER_GAP = 80;

    private final AnalizaConfig cfg;
    private final List<BkBridge.BkTarget> all = new ArrayList<>();
    private List<BkBridge.BkTarget> filtered = new ArrayList<>();
    private String loadError = "";

    private Tab tab = Tab.ITEMS;
    private int settingsPage = 0;
    private String filter = "";
    private KeyBinding listening;
    private boolean bgDone;

    // układ panelu
    private int panelX, panelW;
    private int listTop, listBottom, viewportH, listL, listR;
    private int colName, colNameW, colSpark, colSparkW, colAvg, colPrice, colNumW, colBuy, colBuyW, colChart, colChartW;
    private boolean showIcons;
    private TextFieldWidget filterWidget;

    // lista
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expanded = new LinkedHashSet<>();
    private final Map<String, AnalizaConfig.ScanRecord> scanCache = new HashMap<>();
    private final Map<String, List<PriceHistory.Point>> pointCache = new HashMap<>();
    private int scroll, maxScroll, contentH;
    private int blockPlotH = 80, blockH = 80 + BLOCK_EXTRA;
    private boolean draggingBar;
    private int dragGrab;

    // ustawienia
    private final List<SettingRow> settingRows = new ArrayList<>();
    private int settingsPerPage = 6, settingsVx;

    // ręczna zmiana ceny skupu (zapis do BK po chwili od ostatniego znaku)
    private final Map<String, Long> pendingBuy = new LinkedHashMap<>();        // klucz przedmiotu -> kiedy zapisać
    private final Map<String, Double> pendingBuyValue = new LinkedHashMap<>(); // klucz przedmiotu -> nowa cena skupu
    private String status = "";
    private int statusColor = GRAY;
    private long statusAt;

    public AnalizaScreen(AnalizaConfig cfg) {
        super(Text.literal("Rynek Analiza"));
        this.cfg = cfg;
        try {
            all.addAll(BkBridge.targets("", cfg));
        } catch (Throwable t) {
            loadError = t.toString();
        }
        applyFilter();
    }

    private void applyFilter() {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        filtered = new ArrayList<>();
        for (BkBridge.BkTarget t : all) {
            if (f.isEmpty() || t.displayName().toLowerCase(Locale.ROOT).contains(f)) filtered.add(t);
        }
    }

    private BkBridge.BkTarget findTarget(String key) {
        for (BkBridge.BkTarget t : all) if (t.key().equals(key)) return t;
        return null;
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void switchTab(Tab t) {
        tab = t;
        listening = null;
        draggingBar = false;
        rebuild();
    }

    // ---------- budowanie widżetów ----------
    @Override
    protected void init() {
        panelW = Math.min(540, this.width - 8);
        panelX = (this.width - panelW) / 2;

        String[] names = {"Przedmioty", "Ustawienia"};
        Tab[] tabs = {Tab.ITEMS, Tab.SETTINGS};
        int closeW = panelW < 400 ? 56 : 70;
        int tabW = Math.min(100, (panelW - closeW - 4 - 4) / 2);
        for (int i = 0; i < 2; i++) {
            final Tab target = tabs[i];
            ButtonWidget b = ButtonWidget.builder(Text.literal(names[i]), x -> switchTab(target))
                    .dimensions(panelX + i * (tabW + 4), 22, tabW, 20).build();
            b.active = tab != target;
            addDrawableChild(b);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
                .dimensions(panelX + panelW - closeW, 22, closeW, 20).build());

        switch (tab) {
            case ITEMS -> initItems();
            case SETTINGS -> initSettings();
        }
    }

    // ----- zakładka Przedmioty -----
    private void initItems() {
        int fieldW = Math.max(90, Math.min(150, (int) (panelW * 0.28)));
        int startX = panelX + 52 + fieldW + 6;
        int startW = panelW < 400 ? 84 : 100;
        TextFieldWidget filterField = new TextFieldWidget(this.textRenderer, panelX + 52, 48, fieldW, 16, Text.empty());
        filterField.setMaxLength(40);
        filterField.setText(filter);
        filterField.setChangedListener(s -> {
            filter = s;
            applyFilter();
            scroll = 0;
            rebuild();
            // po przebudowie widżetów przywracamy fokus na polu wyszukiwania
            setFocused(filterWidget);
            filterWidget.setFocused(true);
        });
        filterWidget = addDrawableChild(filterField);

        String startLabel = filter.isBlank() ? "Start analizy" : "Start (filtr)";
        addDrawableChild(ButtonWidget.builder(Text.literal(startLabel), b -> {
            String f = filter.trim();
            close();
            RynekAnalizaMod.startAnalysis(f);
        }).dimensions(startX, 46, startW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> MarketAnalyzer.stop())
                .dimensions(startX + startW + 4, 46, 50, 20).build());

        // obszar listy
        listTop = 84;
        listBottom = this.height - 24;
        viewportH = Math.max(ROW_H, listBottom - listTop);
        listL = panelX + 2;
        listR = panelX + panelW - 8; // po prawej zostaje miejsce na pasek przewijania

        // kolumny liczone od prawej: przycisk Wykres, pole skupu, Sprzedaż, Śr. 3, miniwykres, nazwa
        showIcons = panelW >= 400;
        colNumW = Math.max(this.textRenderer.getWidth("000000$") + 4, (int) (panelW * 0.13));
        colChartW = Math.max(54, Math.min(72, (int) (panelW * 0.13)));
        colChart = listR - 2 - colChartW;
        colBuyW = Math.max(52, (int) (panelW * 0.15));
        colBuy = colChart - 6 - colBuyW;
        colPrice = colBuy - 6 - colNumW;
        colAvg = colPrice - 4 - colNumW;
        colSparkW = panelW >= 440 ? 58 : 0;
        colSpark = colAvg - 6 - colSparkW;
        colName = listL + 4 + (showIcons ? 20 : 0);
        colNameW = Math.max(40, (colSparkW > 0 ? colSpark : colAvg) - 6 - colName);

        blockPlotH = Math.max(40, Math.min(130, viewportH - ROW_H - BLOCK_EXTRA - 4));
        blockH = blockPlotH + BLOCK_EXTRA;

        rows.clear();
        scanCache.clear();
        pointCache.clear();
        for (BkBridge.BkTarget t : filtered) {
            Row r = new Row(t);
            r.open = expanded.contains(t.key());
            AnalizaConfig.ScanRecord sc = t.lastScan();
            if (sc != null) scanCache.put(t.key(), sc);
            r.field = addSelectableChild(buyField(t));
            r.button = addSelectableChild(chartButton(r));
            rows.add(r);
        }
        layoutRows();
        positionWidgets();
    }

    /** Pole ceny skupu (za sztukę) - wpisana wartość trafia do BK po BUY_DEBOUNCE_MS. Puste pole niczego nie zmienia. */
    private TextFieldWidget buyField(BkBridge.BkTarget t) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, colBuy, 0, colBuyW, 16, Text.empty());
        f.setMaxLength(12);
        f.setTextPredicate(s -> s.matches("\\d*[.,]?\\d*"));
        double now = t.currentBuyPrice();
        f.setText(now > 0 ? fmt(now) : "");
        // listener dopiero po setText, żeby samo otwarcie panelu niczego nie zapisywało
        f.setChangedListener(s -> {
            Double v = parse(s);
            if (v != null && v > 0) {
                pendingBuyValue.put(t.key(), v);
                pendingBuy.put(t.key(), System.currentTimeMillis() + BUY_DEBOUNCE_MS);
            } else {
                pendingBuyValue.remove(t.key());
                pendingBuy.remove(t.key());
            }
        });
        return f;
    }

    private ButtonWidget chartButton(Row r) {
        return ButtonWidget.builder(Text.literal(r.open ? "Ukryj" : "Wykres"), b -> toggleChart(r))
                .dimensions(colChart, 0, colChartW, 20).build();
    }

    /** Rozwija / zwija wykres pod wierszem. Po rozwinięciu przewija listę tak, żeby wiersz i jego wykres były widoczne. */
    private void toggleChart(Row r) {
        String key = r.t.key();
        boolean open = !expanded.contains(key);
        if (open) expanded.add(key); else expanded.remove(key);
        rebuild();
        if (open) {
            for (Row nr : rows) {
                if (!nr.t.key().equals(key)) continue;
                int bottom = nr.top + nr.height;
                if (bottom - scroll > viewportH) scroll = bottom - viewportH; // cały wykres w widoku (gdy wiersz + wykres nie mieszczą się razem, wiersz wyjedzie u góry)
                if (nr.top < scroll && nr.height <= viewportH) scroll = nr.top;
                if (nr.height - ROW_H <= viewportH) scroll = Math.min(scroll, nr.top + ROW_H); // nagłówek bloku (z przyciskiem "Ukryj") zawsze w widoku
                scroll = Math.max(0, Math.min(scroll, maxScroll));
                positionWidgets();
            }
        }
    }

    /** Przycisk "Ukryj" w nagłówku rozwiniętego bloku: {x, y, szerokość, wysokość} na ekranie (przy wierszu o danym numerze). */
    int[] closeRect(int rowIndex) {
        Row r = rows.get(rowIndex);
        int bt = listTop + r.top + ROW_H - scroll;
        int w = this.textRenderer.getWidth("Ukryj") + 10;
        return new int[]{listL + 2 + (listR - listL - 4) - 4 - w, bt + 2, w, 13};
    }

    /** Wysokości wierszy i całej zawartości listy; poprawia położenie przewinięcia. */
    private void layoutRows() {
        int y = 0;
        for (Row r : rows) {
            r.top = y;
            r.height = ROW_H + (r.open ? (points(r.t.key()).isEmpty() ? EMPTY_BLOCK_H : blockH) : 0);
            y += r.height;
        }
        contentH = y;
        maxScroll = Math.max(0, contentH - viewportH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** Ustawia widżety wierszy według przewinięcia; wiersze poza widokiem są ukryte (nie reagują na kliknięcia). */
    private void positionWidgets() {
        for (Row r : rows) {
            int sy = listTop + r.top - scroll;
            boolean vis = sy + ROW_H > listTop && sy < listBottom;
            place(r.field, sy + 4, vis);
            place(r.button, sy + 2, vis);
        }
    }

    private static void place(ClickableWidget w, int y, boolean visible) {
        w.setY(y);
        w.visible = visible;
        if (!visible && w.isFocused()) w.setFocused(false);
    }

    private void setScroll(int v) {
        scroll = Math.max(0, Math.min(v, maxScroll));
    }

    // ----- zapis ręcznie wpisanej ceny skupu -----
    /** Zapisuje do BK ceny skupu wpisane w polach (gdy minęła zwłoka albo force = true) i raz zatwierdza zmiany. */
    private void flushPending(boolean force) {
        if (pendingBuy.isEmpty()) return;
        long now = System.currentTimeMillis();
        int changed = 0, failed = 0;
        String single = null;
        for (Iterator<Map.Entry<String, Long>> it = pendingBuy.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (!force && e.getValue() > now) continue;
            it.remove();
            Double v = pendingBuyValue.remove(e.getKey());
            BkBridge.BkTarget t = findTarget(e.getKey());
            if (t == null || v == null) continue;
            double old = t.currentBuyPrice();
            if (old == v) continue;
            if (t.applyBuyPrice(v)) {
                changed++;
                single = t.displayName() + ": skup " + fmt(old) + "$ -> " + fmt(v) + "$";
            } else {
                failed++;
            }
        }
        if (changed > 0) BkBridge.commit();
        if (changed == 1 && failed == 0) setStatus("Zapisano w BK - " + single, GREEN);
        else if (changed > 0 || failed > 0) setStatus("Skup zapisany w BK: " + changed + " przedm." + (failed > 0 ? ", błąd zapisu: " + failed : ""), failed > 0 ? YELLOW : GREEN);
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
        statusAt = System.currentTimeMillis();
    }

    // ----- zakładka Ustawienia -----
    private void initSettings() {
        int vx = panelX + (int) (panelW * 0.56);
        int vw = (int) (panelW * 0.40);
        settingsVx = vx;
        settingRows.clear();
        settingRows.add(new SettingRow("Klawisz: otwórz panel", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.openKey)));
        settingRows.add(new SettingRow("Klawisz: start/stop analizy", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.toggleKey)));
        settingRows.add(new SettingRow("Szybkość analizy", this::addSpeedButton));
        settingRows.add(new SettingRow("Min. ofert do ceny (1-3)", "Min. ofert (1-3)", (x, y, w) -> addNumberField(x, y, w, cfg.minListings, true,
                v -> cfg.minListings = (int) Math.max(1, Math.min(3, Math.round(v))))));
        settingRows.add(new SettingRow("Podatek od sprzedaży (np. 0.97)", "Podatek (np. 0.97)", (x, y, w) -> addNumberField(x, y, w, cfg.taxFactor, false, v -> cfg.taxFactor = v)));
        settingRows.add(new SettingRow("Pauza na ostatniej stronie (ticki)", "Pauza na końcu (ticki)", (x, y, w) -> addNumberField(x, y, w, cfg.highlightPauseTicks, true, v -> cfg.highlightPauseTicks = (int) Math.round(v))));
        settingRows.add(new SettingRow("Podgląd na rynku (żółte oferty)", "Podgląd na rynku", (x, y, w) -> addToggle(x, y, w, cfg.showOverlay, v -> cfg.showOverlay = v)));
        settingRows.add(new SettingRow("Wznów BK po analizie", (x, y, w) -> addToggle(x, y, w, cfg.resumeBk, v -> cfg.resumeBk = v)));
        settingRows.add(new SettingRow("Ścisłe sprawdzanie komponentów", "Ścisłe komponenty", (x, y, w) -> addToggle(x, y, w, cfg.strictComponents, v -> cfg.strictComponents = v)));
        settingRows.add(new SettingRow("Zapisuj historię cen (wykresy)", (x, y, w) -> addToggle(x, y, w, cfg.historyEnabled, v -> cfg.historyEnabled = v)));
        settingRows.add(new SettingRow("Obserwuj rynek, gdy działa BK", (x, y, w) -> addToggle(x, y, w, cfg.passiveObserve, v -> cfg.passiveObserve = v)));
        settingRows.add(new SettingRow("Odstęp zapisów z obserwacji (s)", "Odstęp obserwacji (s)", (x, y, w) -> addNumberField(x, y, w, cfg.historyIntervalSec, true, v -> cfg.historyIntervalSec = (int) Math.round(v))));
        settingRows.add(new SettingRow("Wykresy: też obserwacje BK", (x, y, w) -> addToggle(x, y, w, cfg.chartObserved, v -> cfg.chartObserved = v)));

        int top = 52, step = 22;
        settingsPerPage = Math.max(3, (this.height - top - 58) / step);
        int pages = Math.max(1, (settingRows.size() + settingsPerPage - 1) / settingsPerPage);
        settingsPage = Math.min(Math.max(0, settingsPage), pages - 1);
        int from = settingsPage * settingsPerPage;
        int to = Math.min(settingRows.size(), from + settingsPerPage);
        for (int i = from; i < to; i++) settingRows.get(i).builder().build(vx, top + (i - from) * step, vw);
        if (pages > 1) addPager(this.height - 40, pages, () -> settingsPage > 0, () -> settingsPage < pages - 1, () -> settingsPage--, () -> settingsPage++);
    }

    private void addPager(int y, int pages, java.util.function.BooleanSupplier hasPrev, java.util.function.BooleanSupplier hasNext,
                          Runnable prevAction, Runnable nextAction) {
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { prevAction.run(); rebuild(); })
                .dimensions(panelX + panelW / 2 - PAGER_GAP - 24, y, 24, 20).build();
        prev.active = hasPrev.getAsBoolean();
        addDrawableChild(prev);
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { nextAction.run(); rebuild(); })
                .dimensions(panelX + panelW / 2 + PAGER_GAP, y, 24, 20).build();
        next.active = hasNext.getAsBoolean();
        addDrawableChild(next);
    }

    private void addSpeedButton(int x, int y, int w) {
        addDrawableChild(ButtonWidget.builder(Text.literal(SPEED_NAMES[Math.floorMod(cfg.speed, 3)]), b -> {
            cfg.speed = Math.floorMod(cfg.speed + 1, 3);
            cfg.applyToAnalyzer();
            b.setMessage(Text.literal(SPEED_NAMES[cfg.speed]));
        }).dimensions(x, y - 2, w, 20).build());
    }

    private void addKeyButton(int x, int y, int w, KeyBinding kb) {
        String label = listening == kb ? "> naciśnij klawisz <" : kb.getBoundKeyLocalizedText().getString();
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { listening = kb; rebuild(); })
                .dimensions(x, y - 2, w, 20).build());
    }

    private void addNumberField(int x, int y, int w, double value, boolean integer, Consumer<Double> setter) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.empty());
        f.setMaxLength(10);
        f.setTextPredicate(s -> s.matches(integer ? "\\d*" : "\\d*[.,]?\\d*"));
        f.setText(fmt(value));
        f.setChangedListener(s -> {
            Double v = parse(s);
            if (v != null) {
                setter.accept(v);
                cfg.applyToAnalyzer();
            }
        });
        addDrawableChild(f);
    }

    private void addToggle(int x, int y, int w, boolean value, Consumer<Boolean> setter) {
        boolean[] state = {value};
        addDrawableChild(ButtonWidget.builder(Text.literal(value ? "TAK" : "NIE"), b -> {
            state[0] = !state[0];
            setter.accept(state[0]);
            cfg.applyToAnalyzer();
            b.setMessage(Text.literal(state[0] ? "TAK" : "NIE"));
        }).dimensions(x, y - 2, w, 20).build());
    }

    // ---------- rysowanie ----------
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgDone) return; // własne tło, rysowane raz na klatkę (bez rozmycia)
        bgDone = true;
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        flushPending(false);
        bgDone = false;
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(panelX - 4, 18, panelX + panelW + 4, this.height - 6, 0xE0202020);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Rynek Analiza"), this.width / 2, 8, WHITE);

        switch (tab) {
            case ITEMS -> drawItems(ctx, mouseX, mouseY, delta);
            case SETTINGS -> drawSettingsLabels(ctx);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void text(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), x, y, color);
    }

    private void drawItems(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = this.textRenderer;
        text(ctx, "Szukaj:", panelX + 6, 52, GRAY);

        int hy = listTop - 13;
        text(ctx, "Przedmiot", colName, hy, YELLOW);
        if (colSparkW > 0) text(ctx, "Trend", colSpark, hy, YELLOW);
        text(ctx, "Śr. 3", colAvg, hy, YELLOW);
        text(ctx, tr.trimToWidth(colNumW >= tr.getWidth("Sprzedaż ($)") ? "Sprzedaż ($)" : "Sprzedaż", colBuy - colPrice - 2), colPrice, hy, YELLOW);
        text(ctx, "Skup ($)", colBuy, hy, YELLOW);

        if (!loadError.isEmpty()) {
            text(ctx, tr.trimToWidth("Nie mogę wczytać listy z BK-Rynek: " + loadError, panelW - 12), panelX + 6, listTop + 4, RED);
        } else if (rows.isEmpty()) {
            text(ctx, all.isEmpty() ? "Lista przedmiotów w BK-Rynek jest pusta." : "Brak przedmiotów pasujących do wyszukiwania.", panelX + 6, listTop + 4, GRAY);
        } else {
            drawList(ctx, mouseX, mouseY, delta);
        }

        String count = filtered.size() + (filtered.size() == all.size() ? "" : "/" + all.size()) + " przedm.";
        text(ctx, count, panelX + panelW - 6 - tr.getWidth(count), hy, GRAY);

        boolean showStatus = !status.isEmpty() && System.currentTimeMillis() - statusAt < STATUS_MS;
        String foot = showStatus ? status : firstThatFits(panelW - 12,
                "Analiza wpisuje sprzedaż = śr. 3 najtańszych x ilość sprzedaży z BK. Skup wpisujesz sam (pole albo BK).",
                "Sprzedaż = śr. 3 najtańszych x ilość sprzedaży. Skup wpisujesz sam.",
                "Sprzedaż = śr. 3 x ilość. Skup wpisujesz sam.",
                "Sprzedaż = śr. 3 x ilość");
        text(ctx, tr.trimToWidth(foot, panelW - 12), panelX + 6, this.height - 16, showStatus ? statusColor : GRAY);
    }

    private void drawList(DrawContext ctx, int mouseX, int mouseY, float delta) {
        positionWidgets();
        boolean mouseIn = mouseY >= listTop && mouseY < listBottom && mouseX >= panelX && mouseX < panelX + panelW;
        int mx = mouseIn ? mouseX : -10_000, my = mouseIn ? mouseY : -10_000;
        ctx.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        try {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                int sy = listTop + r.top - scroll;
                if (sy >= listBottom) break;
                if (sy + r.height <= listTop) continue;
                drawRow(ctx, r, i, sy, mx, my, delta);
                if (r.open) drawChartBlock(ctx, r, sy + ROW_H, mx, my);
            }
        } finally {
            ctx.disableScissor();
        }
        drawScrollbar(ctx);
    }

    private void drawRow(DrawContext ctx, Row r, int index, int sy, int mx, int my, float delta) {
        TextRenderer tr = this.textRenderer;
        ctx.fill(listL, sy, listR, sy + ROW_H, index % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
        if (r.open) ctx.fill(listL, sy, listL + 2, sy + r.height - 4, ACCENT);

        if (showIcons) {
            if (r.icon == null) r.icon = r.t.icon();
            if (!r.icon.isEmpty()) ctx.drawItem(r.icon, listL + 5, sy + 4);
        }
        text(ctx, fitName(r.t, colNameW), colName, sy + 8, WHITE);
        if (colSparkW > 0) drawSparkline(ctx, points(r.t.key()), colSpark, sy + 5, colSparkW, 14);

        AnalizaConfig.ScanRecord sc = scanCache.get(r.t.key());
        text(ctx, sc != null ? tr.trimToWidth(fmtR(sc.avg3) + "$", colNumW) : "-", colAvg, sy + 8, sc != null ? LIGHT : GRAY);
        double price = r.t.currentSellPrice();
        int cnt = r.t.sellCount();
        String ps = price > 0 ? fmtR(price) + "$" + (cnt > 1 ? "/" + cnt : "") : "-";
        if (price > 0 && cnt > 1 && tr.getWidth(ps) > colNumW) ps = fmtR(price) + "$";
        text(ctx, tr.trimToWidth(ps, colNumW), colPrice, sy + 8, price > 0 ? GREEN : GRAY);

        if (r.field.visible) r.field.render(ctx, mx, my, delta);
        if (r.button.visible) r.button.render(ctx, mx, my, delta);
    }

    /**
     * Nazwa w wierszu: gdy z dopiskiem (np. "Zaklęta książka [Regeneracji I]") się nie mieści, skracana jest nazwa,
     * a dopisek zostaje - to on odróżnia wpisy o tej samej nazwie.
     */
    String fitName(BkBridge.BkTarget t, int maxW) {
        TextRenderer tr = this.textRenderer;
        String full = t.displayName();
        if (tr.getWidth(full) <= maxW || t.label.isEmpty()) return tr.trimToWidth(full, maxW);
        int minName = tr.getWidth("Ab..");
        String label = t.label;
        int labelMax = maxW - minName - tr.getWidth(" []");
        if (labelMax < tr.getWidth("..ab")) return tr.trimToWidth(full, maxW);
        if (tr.getWidth(label) > labelMax) {
            // ucinamy dopisek od początku - różnica bywa na końcu (np. "Dodatkowe obrażenia 0%" / "15%")
            int i = 0;
            while (i < label.length() && tr.getWidth(".." + label.substring(i)) > labelMax) i++;
            label = ".." + label.substring(i);
        }
        String tag = " [" + label + "]";
        int left = maxW - tr.getWidth(tag);
        String base = t.baseName();
        String name = tr.getWidth(base) <= left ? base : tr.trimToWidth(base, left - tr.getWidth("..")) + "..";
        return name + tag;
    }

    /** Miniatura wykresu w wierszu: linia ostatnich skanów w skali własnej (od najniższej do najwyższej), ostatnia kropka podświetlona. */
    private void drawSparkline(DrawContext ctx, List<PriceHistory.Point> pts, int x, int y, int w, int h) {
        int n = pts.size();
        if (n < 2) {
            ctx.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0x33FFFFFF);
            return;
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (PriceHistory.Point p : pts) { lo = Math.min(lo, p.a3); hi = Math.max(hi, p.a3); }
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x + 2 + Math.round((float) (w - 4) * i / (n - 1));
            ys[i] = yOf(pts.get(i).a3, lo, hi, y + 2, y + h - 3);
        }
        for (int i = 1; i < n; i++) seg(ctx, xs[i - 1], ys[i - 1], xs[i], ys[i], ACCENT, 1);
        ctx.fill(xs[n - 1] - 1, ys[n - 1] - 1, xs[n - 1] + 2, ys[n - 1] + 2, WHITE);
    }

    private void drawScrollbar(DrawContext ctx) {
        if (maxScroll <= 0) return;
        int x = listR + 2, w = 4;
        ctx.fill(x, listTop, x + w, listBottom, 0x30FFFFFF);
        int hh = barHeight();
        int hy = barTop();
        ctx.fill(x, hy, x + w, hy + hh, draggingBar ? 0xFFDDDDDD : 0xFF9A9A9A);
    }

    private int barHeight() {
        return Math.max(16, (int) ((long) viewportH * viewportH / Math.max(1, contentH)));
    }

    private int barTop() {
        int travel = viewportH - barHeight();
        return listTop + (maxScroll <= 0 ? 0 : (int) ((long) travel * scroll / maxScroll));
    }

    private List<PriceHistory.Point> points(String key) {
        List<PriceHistory.Point> l = pointCache.get(key);
        if (l == null) {
            PriceHistory hist = RynekAnalizaMod.history();
            l = hist == null ? List.of() : hist.lastScans(key, cfg.chartObserved, CHART_SCANS);
            pointCache.put(key, l);
        }
        return l;
    }

    // ----- rozwinięty wykres -----
    private static void box(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        c.fill(x + 1, y, x + w - 1, y + h, border);
        c.fill(x, y + 1, x + w, y + h - 1, border);
        c.fill(x + 2, y + 1, x + w - 2, y + h - 1, fill);
        c.fill(x + 1, y + 2, x + w - 1, y + h - 2, fill);
    }

    private static int alpha(int argb, int a) {
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private void drawChartBlock(DrawContext ctx, Row r, int bt, int mouseX, int mouseY) {
        TextRenderer tr = this.textRenderer;
        List<PriceHistory.Point> pts = points(r.t.key());
        PriceHistory.Stats st = PriceHistory.stats(pts);
        int n = pts.size();

        int bx = listL + 2, bw = listR - listL - 4, bh = (n == 0 ? EMPTY_BLOCK_H : blockH) - 4;
        box(ctx, bx, bt, bw, bh, BLOCK_BG, BLOCK_BORDER);

        // przycisk "Ukryj" (wiersz bywa przewinięty poza widok, a blok nadal trzeba móc zwinąć)
        int rowIndex = rows.indexOf(r);
        int[] cr = closeRect(rowIndex);
        boolean overClose = mouseX >= cr[0] && mouseX < cr[0] + cr[2] && mouseY >= cr[1] && mouseY < cr[1] + cr[3];
        box(ctx, cr[0], cr[1], cr[2], cr[3], overClose ? 0xFF2C3140 : CARD_BG, CARD_BORDER);
        text(ctx, "Ukryj", cr[0] + 5, cr[1] + 3, overClose ? WHITE : GRAY);
        int closeSpace = cr[2] + 10;

        // nagłówek: nazwa (blok bywa przewinięty tak, że wiersz jest już niewidoczny), liczba skanów i legenda
        String head = n == 0 ? "skany: brak" : "skany: " + n;
        int headW = bw - 12 - closeSpace;
        int nameMax = Math.max(30, Math.min(headW - tr.getWidth("  |  " + head) - 4, Math.max(headW / 2, 110)));
        String title = tr.trimToWidth(r.t.displayName(), nameMax) + "  |  " + head;
        text(ctx, title, bx + 6, bt + 5, LIGHT);
        if (n > 0) {
            int free = headW - tr.getWidth(title) - 14;
            for (String[] lg : new String[][]{{"śr. 3 najtańszych", "średnia"}, {"śr. 3", "średnia"}}) {
                int need = 12 + 4 + tr.getWidth(lg[0]) + 12 + 12 + 4 + tr.getWidth(lg[1]);
                if (need > free) continue;
                int lx = bx + bw - 6 - closeSpace - tr.getWidth(lg[1]);
                text(ctx, lg[1], lx, bt + 5, GRAY);
                lx -= 16;
                for (int d = 0; d < 12; d += 6) ctx.fill(lx + d, bt + 9, lx + d + 3, bt + 10, MEAN);
                lx -= 12 + tr.getWidth(lg[0]);
                text(ctx, lg[0], lx, bt + 5, GRAY);
                lx -= 16;
                ctx.fill(lx, bt + 9, lx + 12, bt + 11, ACCENT);
                break;
            }
        }

        int plotTop = bt + 18, plotBottom = plotTop + blockPlotH;
        int cardsTop = plotBottom + 26, cardH = 24;

        // skala: od najniższej do najwyższej średniej, z zapasem u góry i u dołu
        double vMin = 0, vMax = 0;
        if (n > 0) {
            vMin = Double.MAX_VALUE;
            vMax = -Double.MAX_VALUE;
            for (PriceHistory.Point p : pts) { vMin = Math.min(vMin, p.a3); vMax = Math.max(vMax, p.a3); }
        }
        double range = vMax - vMin;
        double pad = range > 1e-9 ? range * 0.14 : Math.max(Math.abs(vMax) * 0.05, 0.01);
        double lo = Math.max(0, vMin - pad), hi = vMax + pad;
        double step = niceStep((hi - lo) / Math.max(2, Math.min(5, blockPlotH / 26)));
        List<Double> ticks = new ArrayList<>();
        if (n > 0) {
            for (double v = Math.ceil(lo / step - 1e-9) * step; v <= hi + 1e-9 && ticks.size() < 8; v += step) ticks.add(v);
        }
        int dec = decimals(step);
        int gutter = 8;
        for (double v : ticks) gutter = Math.max(gutter, tr.getWidth(fmtTick(v, dec)) + 8);

        int px0 = bx + 4 + gutter, px1 = bx + bw - 6, pw = px1 - px0;
        int top = plotTop + 14, bot = plotBottom - 6; // miejsce na podpisy nad najwyższym i pod najniższym punktem

        if (n == 0) {
            text(ctx, tr.trimToWidth("Brak skanów tego przedmiotu - uruchom analizę (Start / F8), a pojawi się tu wykres.", bw - 12), bx + 6, bt + 19, GRAY);
            text(ctx, fitParts(bw - 12, sellPart(r.t), buyPart(r.t)), bx + 6, bt + 33, GRAY);
            return;
        }
        ctx.fill(px0, plotTop, px1, plotBottom, PLOT_BG);

        // siatka i oś cen
        for (double v : ticks) {
            int ty = yOf(v, lo, hi, top, bot);
            if (ty < plotTop || ty > plotBottom - 1) continue;
            ctx.fill(px0, ty, px1, ty + 1, GRID);
            String lab = fmtTick(v, dec);
            text(ctx, lab, px0 - 4 - tr.getWidth(lab), ty - 4, GRAY);
        }

        int padX = 12, usable = Math.max(1, pw - 2 * padX);
        double spacing = n > 1 ? (double) usable / (n - 1) : pw;
        boolean dense = spacing < 6; // przy wielu analizach: bez prowadnic i kropek przy każdym punkcie, sama linia
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? px0 + pw / 2 : px0 + padX + Math.round((float) usable * i / (n - 1));
            ys[i] = yOf(pts.get(i).a3, lo, hi, top, bot);
            if (!dense) ctx.fill(xs[i], plotTop, xs[i] + 1, plotBottom, 0x0FFFFFFF); // delikatna pionowa prowadnica
        }

        // wypełnienie pod linią (cztery pasy o malejącej przezroczystości)
        if (n > 1) {
            for (int x = xs[0]; x <= xs[n - 1]; x++) {
                int seg = 1;
                while (seg < n - 1 && x > xs[seg]) seg++;
                int y = ys[seg - 1] + Math.round((float) (ys[seg] - ys[seg - 1]) * (x - xs[seg - 1]) / Math.max(1, xs[seg] - xs[seg - 1]));
                int h = plotBottom - y;
                if (h <= 0) continue;
                int b1 = y + Math.max(1, h / 4), b2 = y + Math.max(1, h / 2), b3 = y + Math.max(1, 3 * h / 4);
                ctx.fill(x, y, x + 1, b1, alpha(ACCENT, 0x48));
                ctx.fill(x, b1, x + 1, b2, alpha(ACCENT, 0x32));
                ctx.fill(x, b2, x + 1, b3, alpha(ACCENT, 0x20));
                ctx.fill(x, b3, x + 1, plotBottom, alpha(ACCENT, 0x10));
            }
        }

        // przerywana linia średniej
        int ym = yOf(st.mean, lo, hi, top, bot);
        for (int x = px0 + 2; x < px1 - 2; x += 6) ctx.fill(x, ym, Math.min(x + 3, px1 - 2), ym + 1, alpha(MEAN, 0xCC));

        // linia (2 px) i punkty
        for (int i = 1; i < n; i++) seg(ctx, xs[i - 1], ys[i - 1], xs[i], ys[i], ACCENT, 2);
        for (int i = 0; i < n; i++) if (!dense || i == n - 1) marker(ctx, xs[i], ys[i], i == n - 1);

        // podpisy: wartość przy punkcie, czas (i data przy zmianie dnia) pod wykresem; przy ciasnych odstępach co któryś, zawsze ostatni
        String[] vt = new String[n];
        int maxW = tr.getWidth("00:00");
        for (int i = 0; i < n; i++) {
            vt[i] = fmtR(pts.get(i).a3);
            maxW = Math.max(maxW, tr.getWidth(vt[i]));
        }
        int every = Math.max(1, (int) Math.ceil((maxW + 6) / spacing));
        // wartość ostatniego punktu ma pierwszeństwo; pozostałe podpisy, które by na nią (albo na siebie) wchodziły, są pomijane
        List<int[]> taken = new ArrayList<>();
        taken.add(valueRect(tr, vt[n - 1], xs[n - 1], ys[n - 1], px0, px1, plotTop));
        String prevDate = "";
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            if ((n - 1 - i) % every != 0) continue;
            ctx.fill(xs[i], plotBottom, xs[i] + 1, plotBottom + 2, GRAY);
            int[] vr = valueRect(tr, vt[i], xs[i], ys[i], px0, px1, plotTop);
            int vx = vr[0] + 3, vy = vr[1] + 2, vw = vr[2] - 6;
            if (last) {
                box(ctx, vx - 3, vy - 2, vw + 6, 12, ACCENT, ACCENT);
                text(ctx, vt[i], vx, vy, 0xFF0A1220);
            } else if (!overlapsAny(vr, taken)) {
                taken.add(vr);
                ctx.fill(vx - 2, vy - 1, vx + vw + 2, vy + 9, 0xB00E1015);
                text(ctx, vt[i], vx, vy, LIGHT);
            }
            Instant when = Instant.ofEpochMilli(pts.get(i).t);
            String tm = F_TIME.format(when), dt = F_DATE.format(when);
            text(ctx, tm, clamp(xs[i] - tr.getWidth(tm) / 2, bx + 2, bx + bw - tr.getWidth(tm) - 2), plotBottom + 3, LIGHT);
            if (!dt.equals(prevDate)) {
                text(ctx, dt, clamp(xs[i] - tr.getWidth(dt) / 2, bx + 2, bx + bw - tr.getWidth(dt) - 2), plotBottom + 13, GRAY);
                prevDate = dt;
            }
        }

        // karty ze statystykami
        int gap = 4, cardW = (bw - 8 - 3 * gap) / 4;
        String[] labels = {"Średnia", "Najniższa", "Najwyższa", "Ostatni"};
        double[] vals = {st.mean, st.min, st.max, st.last};
        int[] stripe = {MEAN, UP, DOWN, ACCENT};
        double chg = st.changePct();
        for (int k = 0; k < 4; k++) {
            int cx = bx + 4 + k * (cardW + gap);
            box(ctx, cx, cardsTop, cardW, cardH, CARD_BG, CARD_BORDER);
            ctx.fill(cx + 2, cardsTop + 1, cx + cardW - 2, cardsTop + 2, stripe[k]);
            text(ctx, tr.trimToWidth(labels[k], cardW - 6), cx + 4, cardsTop + 4, GRAY);
            text(ctx, tr.trimToWidth(fmtR(vals[k]) + "$", cardW - 6), cx + 4, cardsTop + 14, WHITE);
            if (k == 3 && !Double.isNaN(chg)) {
                String d = String.format(Locale.ROOT, "%+.1f%%", chg);
                int dw = tr.getWidth(d);
                if (tr.getWidth(labels[k]) + dw + 12 <= cardW) text(ctx, d, cx + cardW - 4 - dw, cardsTop + 4, chg >= 0 ? UP : DOWN);
            }
        }

        // linia szczegółów
        // linia szczegółów: najpierw ceny w BK i zysk (najważniejsze przy ustalaniu skupu), potem reszta - tyle, ile się zmieści
        List<String> parts = new ArrayList<>();
        parts.add(sellPart(r.t));
        parts.add(buyPart(r.t));
        String profit = profitPart(r.t);
        if (!profit.isEmpty()) parts.add(profit);
        parts.add("rozstęp " + fmtR(st.spread()) + "$");
        parts.add("najtańsza oferta " + fmtR(st.cheapest) + "$");
        parts.add("ostatni skan " + ago(System.currentTimeMillis() - st.lastT));
        String details = fitParts(bw - 12, parts.toArray(new String[0]));
        text(ctx, details, bx + 6, cardsTop + cardH + 4, GRAY);

        // najechanie myszą: najbliższy punkt i jego szczegóły
        if (mouseX >= px0 && mouseX < px1 && mouseY >= plotTop && mouseY < plotBottom) {
            int best = 0, bestDx = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                int dx = Math.abs(xs[i] - mouseX);
                if (dx < bestDx) { bestDx = dx; best = i; }
            }
            PriceHistory.Point p = pts.get(best);
            ctx.fill(xs[best], plotTop, xs[best] + 1, plotBottom, alpha(ACCENT, 0x99));
            marker(ctx, xs[best], ys[best], true);
            String tip1 = F_FULL.format(Instant.ofEpochMilli(p.t)) + (p.s == PriceHistory.SRC_ACTIVE ? "  (skan z analizy)" : "  (obserwacja BK)");
            String tip2 = "śr. 3: " + fmtR(p.a3) + "$ | najtańsza: " + fmtR(p.p1) + "$ | ofert: " + p.n;
            tip1 = tr.trimToWidth(tip1, pw - 16);
            tip2 = tr.trimToWidth(tip2, pw - 16);
            int tw = Math.max(tr.getWidth(tip1), tr.getWidth(tip2));
            int tx = clamp(xs[best] - tw / 2, px0 + 4, px1 - tw - 8);
            int ty = ys[best] - plotTop < 34 ? plotBottom - 28 : plotTop + 3; // nie zasłaniaj najechanego punktu
            box(ctx, tx - 3, ty - 2, tw + 8, 25, 0xFF0A0C10, alpha(ACCENT, 0xFF));
            text(ctx, tip1, tx + 1, ty + 1, WHITE);
            text(ctx, tip2, tx + 1, ty + 12, LIGHT);
        }
    }

    /** Prostokąt podpisu wartości nad punktem (z ramką): {x, y, szerokość, wysokość}. */
    private static int[] valueRect(TextRenderer tr, String v, int x, int y, int px0, int px1, int plotTop) {
        int vw = tr.getWidth(v);
        int vx = clamp(x - vw / 2, px0 + 3, px1 - vw - 3);
        int vy = y - 14 >= plotTop + 1 ? y - 14 : y + 7;
        return new int[]{vx - 3, vy - 2, vw + 6, 12};
    }

    private static boolean overlapsAny(int[] r, List<int[]> rs) {
        for (int[] o : rs) {
            if (r[0] < o[0] + o[2] + 1 && o[0] < r[0] + r[2] + 1 && r[1] < o[1] + o[3] && o[1] < r[1] + r[3]) return true;
        }
        return false;
    }

    /** Punkt wykresu: zaokrąglony znacznik z ciemnym obrysem; ostatni (lub wskazany) jest większy, biały, z poświatą. */
    private static void marker(DrawContext c, int x, int y, boolean big) {
        if (big) {
            int halo = alpha(ACCENT, 0x40);
            c.fill(x - 2, y - 4, x + 3, y + 5, halo);
            c.fill(x - 4, y - 2, x + 5, y + 3, halo);
            c.fill(x - 3, y - 3, x + 4, y + 4, halo);
            c.fill(x - 1, y - 3, x + 2, y + 4, WHITE);
            c.fill(x - 3, y - 1, x + 4, y + 2, WHITE);
            c.fill(x - 2, y - 2, x + 3, y + 3, WHITE);
            c.fill(x - 1, y - 1, x + 2, y + 2, ACCENT);
        } else {
            c.fill(x - 1, y - 2, x + 2, y + 3, 0xFF0E1015);
            c.fill(x - 2, y - 1, x + 3, y + 2, 0xFF0E1015);
            c.fill(x - 1, y - 1, x + 2, y + 2, ACCENT);
        }
    }

    private static int yOf(double v, double lo, double hi, int top, int bot) {
        if (hi - lo < 1e-9) return (top + bot) / 2;
        double f = (v - lo) / (hi - lo);
        f = Math.max(0, Math.min(1, f));
        return bot - (int) Math.round(f * (bot - top));
    }

    /** "Ładny" krok osi: 1, 2, 2.5, 5 albo 10 razy potęga dziesięciu. */
    static double niceStep(double raw) {
        if (!(raw > 0)) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / p;
        double n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return n * p;
    }

    /** Ile miejsc po przecinku potrzeba, żeby wypisać wielokrotności kroku bez zaokrągleń. */
    static int decimals(double step) {
        for (int d = 0; d <= 4; d++) {
            double s = step * Math.pow(10, d);
            if (Math.abs(s - Math.rint(s)) < 1e-6) return d;
        }
        return 4;
    }

    static String fmtTick(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String ago(long ms) {
        long m = Math.max(0, ms / 60_000L);
        if (m < 1) return "przed chwilą";
        if (m < 60) return m + " min temu";
        if (m < 48 * 60) return (m / 60) + " h temu";
        return (m / 1440) + " dni temu";
    }

    /** Linia między dwoma punktami o grubości thick: dla każdej kolumny pikseli jeden pionowy pasek (mało wywołań fill). */
    private static void seg(DrawContext c, int x0, int y0, int x1, int y1, int col, int thick) {
        if (x1 < x0) { int tx = x0; x0 = x1; x1 = tx; int ty = y0; y0 = y1; y1 = ty; }
        if (x1 == x0) {
            c.fill(x0, Math.min(y0, y1), x0 + 1, Math.max(y0, y1) + thick, col);
            return;
        }
        int prevY = y0;
        for (int x = x0; x <= x1; x++) {
            int y = y0 + Math.round((float) (y1 - y0) * (x - x0) / (x1 - x0));
            c.fill(x, Math.min(prevY, y), x + 1, Math.max(prevY, y) + thick, col);
            prevY = y;
        }
    }

    private void drawSettingsLabels(DrawContext ctx) {
        int top = 52, step = 22;
        int from = settingsPage * settingsPerPage;
        int to = Math.min(settingRows.size(), from + settingsPerPage);
        for (int i = from; i < to; i++) {
            int avail = settingsVx - (panelX + 8) - 6;
            SettingRow sr = settingRows.get(i);
            text(ctx, this.textRenderer.trimToWidth(firstThatFits(avail, sr.label(), sr.shortLabel()), avail), panelX + 8, top + (i - from) * step + 3, WHITE);
        }
        int pages = Math.max(1, (settingRows.size() + settingsPerPage - 1) / settingsPerPage);
        if (pages > 1) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ustawienia " + (settingsPage + 1) + "/" + pages),
                    panelX + panelW / 2, this.height - 35, WHITE);
        }
        text(ctx, this.textRenderer.trimToWidth(firstThatFits(panelW - 16,
                "Klawisz: Delete/Backspace = brak, Esc = anuluj. Komponenty: BK ich nie sprawdza - włącz tylko, gdy coś się myli.",
                "Klawisz: Delete/Backspace = brak, Esc = anuluj.",
                "Klawisz: Delete = brak, Esc = anuluj."), panelW - 12),
                panelX + 8, this.height - 16, GRAY);
    }

    // ---------- zdarzenia ----------
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            KeyBinding kb = listening;
            listening = null;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) kb.setBoundKey(InputUtil.UNKNOWN_KEY);
                else kb.setBoundKey(InputUtil.fromKeyCode(keyCode, scanCode));
                KeyBinding.updateKeysByCode();
                MinecraftClient.getInstance().options.write();
            }
            rebuild();
            return true;
        }
        boolean typing = getFocused() instanceof TextFieldWidget tf && tf.isFocused();
        if (!typing && RynekAnalizaMod.openKey != null && RynekAnalizaMod.openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (!typing && tab == Tab.ITEMS) {
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) { setScroll(scroll + viewportH - ROW_H); return true; }
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) { setScroll(scroll - viewportH + ROW_H); return true; }
            if (keyCode == GLFW.GLFW_KEY_HOME) { setScroll(0); return true; }
            if (keyCode == GLFW.GLFW_KEY_END) { setScroll(maxScroll); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listening != null) { // klik anuluje ustawianie klawisza
            listening = null;
            rebuild();
            return true;
        }
        if (tab != Tab.ITEMS) return super.mouseClicked(mouseX, mouseY, button);

        // pasek przewijania: kliknięcie w uchwyt zaczyna przeciąganie, w tor - przeskok
        if (button == 0 && maxScroll > 0 && mouseX >= listR && mouseX < panelX + panelW && mouseY >= listTop && mouseY < listBottom) {
            int hy = barTop(), hh = barHeight();
            if (mouseY >= hy && mouseY < hy + hh) {
                dragGrab = (int) mouseY - hy;
            } else {
                dragGrab = hh / 2;
                dragBarTo(mouseY);
            }
            draggingBar = true;
            return true;
        }
        // przycisk "Ukryj" w nagłówku rozwiniętego wykresu
        if (button == 0 && mouseY >= listTop && mouseY < listBottom) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (!r.open) continue;
                int[] cr = closeRect(i);
                if (mouseX >= cr[0] && mouseX < cr[0] + cr[2] && mouseY >= cr[1] && mouseY < cr[1] + cr[3]) {
                    toggleChart(r);
                    return true;
                }
            }
        }
        // wiersze przewinięte pod nagłówek są tylko przycięte, nie ukryte - klik poza obszarem listy nie może ich trafić
        if (mouseY < listTop || mouseY >= listBottom) {
            List<Boolean> was = new ArrayList<>();
            for (Row r : rows) { was.add(r.field.visible); was.add(r.button.visible); r.field.visible = false; r.button.visible = false; }
            try {
                return super.mouseClicked(mouseX, mouseY, button);
            } finally {
                int i = 0;
                for (Row r : rows) { r.field.visible = was.get(i++); r.button.visible = was.get(i++); }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void dragBarTo(double mouseY) {
        int travel = viewportH - barHeight();
        if (travel <= 0) return;
        double f = (mouseY - dragGrab - listTop) / travel;
        setScroll((int) Math.round(f * maxScroll));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingBar) {
            dragBarTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == Tab.ITEMS && maxScroll > 0) {
            setScroll(scroll - (int) Math.round(verticalAmount * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        flushPending(true);
        cfg.save();
        cfg.applyToAnalyzer();
    }

    private static String sellPart(BkBridge.BkTarget t) {
        double p = t.currentSellPrice();
        int c = t.sellCount();
        return "sprzedaż w BK " + (p > 0 ? fmtR(p) + "$" + (c > 1 ? " za " + c + " szt." : "") : "-");
    }

    private static String buyPart(BkBridge.BkTarget t) {
        double b = t.currentBuyPrice();
        return "skup w BK " + (b > 0 ? fmtR(b) + "$" : "-");
    }

    /** Zysk na sztuce: sprzedaż za sztukę x podatek (np. 0.97) - skup; pusty, gdy brakuje którejś ceny. */
    private String profitPart(BkBridge.BkTarget t) {
        double p = t.currentSellPrice(), b = t.currentBuyPrice();
        if (p <= 0 || b <= 0) return "";
        double z = p / Math.max(1, t.sellCount()) * cfg.taxFactor - b;
        return "zysk/szt. " + (z >= 0 ? "+" : "") + fmtR(z) + "$";
    }

    // ---------- liczby ----------
    private static Double parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "?";
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Jak fmt, ale od 100 w górę bez ułamków (do ciasnych linii pod wykresem). */
    static String fmtR(double v) {
        return Math.abs(v) >= 100 ? (Double.isNaN(v) || Double.isInfinite(v) ? "?" : String.valueOf(Math.round(v))) : fmt(v);
    }

    /** Pierwszy z wariantów tekstu (od najdłuższego), który mieści się w maxW; gdy żaden - ostatni. */
    private String firstThatFits(int maxW, String... variants) {
        for (String v : variants) if (this.textRenderer.getWidth(v) <= maxW) return v;
        return variants[variants.length - 1];
    }

    /** Składa części w jedną linię; części, które się już nie mieszczą w maxW, są pomijane (bez ucinania w pół słowa). */
    private String fitParts(int maxW, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String next = sb.length() == 0 ? p : sb + " | " + p;
            if (this.textRenderer.getWidth(next) > maxW) break;
            sb.setLength(0);
            sb.append(next);
        }
        return sb.length() == 0 && parts.length > 0 ? this.textRenderer.trimToWidth(parts[0], maxW) : sb.toString();
    }
}
