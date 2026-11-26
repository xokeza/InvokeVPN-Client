package su.xokeza.invokevpn;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.app.StatusBarManager;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String SUPPORT = "https://t.me/miptl";
    private static final int VPN_PERMISSION = 47;
    private static final int IMPORT_THEME = 48;
    private static final int IMPORT_SUBSCRIPTION = 49;
    private static final int NOTIFICATION_PERMISSION = 50;
    private static final int EXPORT_SETTINGS = 52;
    private static final int IMPORT_SETTINGS = 53;

    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> latency = new HashMap<>();
    private final Set<String> refreshing = new HashSet<>();
    private final Set<String> collapsed = new HashSet<>(), expanded = new HashSet<>();
    private static final String HOME_PAGE = "@home";
    private PageNavigation navigation, gestureNavigation;
    private SystemBack systemBack;
    private int revision;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged =
            (prefs, key) -> revision++;
    private final Locale russian = Locale.forLanguageTag("ru-RU");
    private final BroadcastReceiver tunnelEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (atHome()) render(); else revision++; }
    };
    private Store store;
    private ClientPreferences config;
    private Palette theme;
    private LinearLayout page;
    private boolean allCollapsed, openActionsCompleted;
    private GlassOverlay overlays;
    private AppUpdate pendingUpdate;
    private String shownUpdate;
    private boolean checkedForUpdate, resumed;
    private record Badge(String profileId, PingIndicator view, String display) {}
    private final Map<String, List<Badge>> badges = new HashMap<>();
    private final Set<String> probing = new HashSet<>();
    private final Map<String, LinearLayout> profileCards = new HashMap<>();
    private final Map<String, View> refreshButtons = new HashMap<>();
    private final ExecutorService pingWorkers = Executors.newFixedThreadPool(4);
    private String notice = "", permissionProfile;
    private int permissionNode;
    private TextView timerLabel;
    private Typeface regular, semibold;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        store = new Store(this);
        config = new ClientPreferences(store);
        if (bundle != null) {
            openActionsCompleted = bundle.getBoolean("startupComplete");
            shownUpdate = bundle.getString("shownUpdate");
        }
        store.preferences.registerOnSharedPreferenceChangeListener(preferencesChanged);
        regular = getResources().getFont(R.font.inter_regular);
        semibold = getResources().getFont(R.font.inter_semibold);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(tunnelEvents, new IntentFilter(TunnelService.STATE), RECEIVER_NOT_EXPORTED);
        else registerReceiver(tunnelEvents, new IntentFilter(TunnelService.STATE));
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        createNavigation(bundle);
        handleLink(getIntent());
        RefreshJob.schedule(this);
        handler.post(tick);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        ArrayList<String> routes = new ArrayList<>();
        int[] offsets = new int[navigation.depth()];
        for (int i = 0; i < offsets.length; i++) {
            Screen screen = (Screen) navigation.entry(i);
            routes.add(screen.route);
            offsets[i] = Math.max(screen.restoredOffset, screen.getScrollY());
        }
        out.putStringArrayList("pages", routes);
        out.putIntArray("pageOffsets", offsets);
        out.putBoolean("startupComplete", openActionsCompleted);
        out.putString("shownUpdate", shownUpdate);
        super.onSaveInstanceState(out);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLink(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        render();
        checkForAppUpdate();
        if (openActionsCompleted) return;
        openActionsCompleted = true;
        Subscription selectedOnOpen = selected();
        for (Subscription profile : profiles()) {
            RefreshJob.expiry(this, store, profile);
            if (!profile.source.isEmpty() && config.refreshOnOpen(profile)) {
                refresh(profile.source, profile, false);
            }
            boolean needsAutoPing = selectedOnOpen != null && selectedOnOpen.id.equals(profile.id)
                    && config.autoConnect(profile).equals("lowestdelay");
            if (store.preferences.getBoolean("ping_on_open", profile.flag("subscription-ping-onopen-enabled", false)) && !needsAutoPing) ping(profile, false);
        }
        if (selectedOnOpen != null && !selectedOnOpen.nodes.isEmpty() && TunnelService.status.equals("idle")) {
            switch (config.autoConnect(selectedOnOpen)) {
                case "first" -> { choose(selectedOnOpen, 0); requestVpn(selectedOnOpen); }
                case "lastused" -> requestVpn(selectedOnOpen);
                case "lowestdelay" -> ping(selectedOnOpen, true);
            }
        }
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    private void checkForAppUpdate() {
        if (checkedForUpdate) return;
        checkedForUpdate = true;
        network.execute(() -> {
            try {
                String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                AppUpdate update = AppUpdate.latest(installed);
                if (update == null) return;
                runOnUiThread(() -> {
                    if (isDestroyed() || update.version().equals(shownUpdate)) return;
                    pendingUpdate = update;
                    showAppUpdateIfReady();
                });
            } catch (Exception ignored) {
                // GitHub connectivity is independent of VPN and subscription startup.
            }
        });
    }

    private void showAppUpdateIfReady() {
        if (pendingUpdate == null || !resumed || !hasWindowFocus() || !atHome()
                || isFinishing() || overlays.isShowing()) return;
        AppUpdate update = pendingUpdate;
        pendingUpdate = null;
        shownUpdate = update.version();

        String title = "Доступно обновление";
        LinearLayout content = column();
        content.setPadding(dp(16), dp(16), dp(16), dp(12));
        TextView heading = label(title, 18, ink(), true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        content.addView(heading);
        TextView version = label("InvokeVPN " + update.version(), 14, quiet(), false);
        version.setPadding(0, dp(3), 0, dp(15));
        content.addView(version);

        TextView install = action("Установить", tone("buttonTextColor", "#2D0B18"), 14,
                () -> overlays.hide(() -> open(update.apkUrl())));
        install.setGravity(Gravity.CENTER);
        install.setMinHeight(dp(48));
        install.setBackground(interactive(accent(), 14));
        content.addView(install, new LinearLayout.LayoutParams(-1, -2));

        TextView later = action("Позже", ink(), 14, () -> overlays.hide(null));
        later.setGravity(Gravity.CENTER);
        later.setMinHeight(dp(48));
        later.setBackground(interactive(Color.TRANSPARENT, 14));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
        spacing.topMargin = dp(6);
        content.addView(later, spacing);
        showGlass(null, content, title, false);
    }

    @Override protected void onDestroy() {
        store.preferences.unregisterOnSharedPreferenceChangeListener(preferencesChanged);
        if (systemBack != null) systemBack.setEnabled(false);
        handler.removeCallbacks(tick);
        network.shutdownNow();
        pingWorkers.shutdownNow();
        unregisterReceiver(tunnelEvents);
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (timerLabel != null && TunnelService.since > 0) {
                long seconds = (SystemClock.elapsedRealtime() - TunnelService.since) / 1000;
                timerLabel.setText(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
            }
            showAppUpdateIfReady();
            handler.postDelayed(this, 1000);
        }
    };

    private void handleLink(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            importDialog(intent.getData().toString());
            setIntent(new Intent(this, MainActivity.class));
        }
    }

    private List<Subscription> profiles() {
        try {
            List<Subscription> profiles = store.all();
            profiles.sort(Comparator.comparing(profile -> !profile.flag("subscription-pin", false)));
            return profiles;
        } catch (IllegalStateException error) {
            notice = error.getMessage();
            return new ArrayList<>();
        }
    }

    private Subscription selected() {
        try { return store.selected(); }
        catch (IllegalStateException error) { return null; }
    }

    private int selectedIndex(Subscription profile) { return store.selectedIndex(profile); }

    private void choose(Subscription profile, int node) {
        store.preferences.edit().putString("selected", profile.id).putInt("node:" + profile.id, node).apply();
    }

    private final class Screen extends ScrollView {
        final String route;
        final LinearLayout body;
        int drawnRevision = -1;
        int restoredOffset;

        Screen(String route) {
            super(MainActivity.this);
            this.route = route;
            setFillViewport(true);
            setVerticalScrollBarEnabled(false);
            FrameLayout centered = new FrameLayout(MainActivity.this);
            addView(centered, new ScrollView.LayoutParams(-1, -2));
            body = column();
            int width = Math.min(getResources().getConfiguration().screenWidthDp, 680);
            int side = width < 380 ? 13 : 20;
            body.setPadding(dp(side), dp(5), dp(side), dp(28));
            centered.addView(body, new FrameLayout.LayoutParams(dp(width), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (restoredOffset > 0) {
                scrollTo(0, restoredOffset);
                restoredOffset = 0;
            }
        }
    }

    private void createNavigation(Bundle state) {
        FrameLayout root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, bottom);
            return insets;
        });
        navigation = new PageNavigation(this, -1, null);
        root.addView(navigation, new FrameLayout.LayoutParams(-1, -1));
        overlays = new GlassOverlay(root, navigation, this::updateSystemBack);
        if (Build.VERSION.SDK_INT >= 33) systemBack = new SystemBack(this);
        setContentView(root);
        root.requestApplyInsets();

        ArrayList<String> routes = state == null ? null : state.getStringArrayList("pages");
        if (routes == null || routes.isEmpty()) {
            routes = new ArrayList<>();
            routes.add(HOME_PAGE);
            if (state != null && state.getBoolean("settings")) {
                routes.add("");
                String parent = state.getString("appsParent", "");
                String route = state.getString("settingsPage", "");
                if ("Приложения".equals(route) && !parent.isEmpty()) routes.add(parent);
                if (!route.isEmpty()) routes.add(route);
            }
        }
        int[] offsets = state == null ? null : state.getIntArray("pageOffsets");
        for (int i = 0; i < routes.size(); i++) {
            Screen screen = new Screen(routes.get(i));
            if (offsets != null && i < offsets.length) screen.restoredOffset = offsets[i];
            navigation.push(screen, false);
        }
        render();
        updateSystemBack();
    }

    private boolean atHome() {
        return navigation != null && navigation.depth() == 1;
    }

    private void render() {
        if (navigation == null || isFinishing() || isDestroyed()) return;
        revision++;
        paintScreen((Screen) navigation.current());
    }

    private void paintScreen(Screen screen) {
        if (screen.drawnRevision == revision) return;
        theme = Palette.load(this, store);
        screen.setBackground(Art.background(theme));
        page = screen.body;
        page.removeAllViews();
        if (HOME_PAGE.equals(screen.route)) {
            badges.clear();
            profileCards.clear();
            refreshButtons.clear();
            timerLabel = null;
            drawHome();
        } else {
            drawSettings(screen.route);
        }
        screen.drawnRevision = revision;
        page = ((Screen) navigation.current()).body;
    }

    private void drawHome() {
        LinearLayout top = row();
        page.addView(top);
        top.addView(icon(R.drawable.ic_settings, "Настройки", topColor(), 48, () -> openSettingsPage("")));
        LinearLayout brand = row();
        brand.setGravity(Gravity.CENTER);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_invoke);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView name = label("InvokeVPN", 17, ink(), true);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(-2, -2);
        nameLayout.leftMargin = dp(5);
        brand.addView(name, nameLayout);
        top.addView(brand, flex());
        top.addView(anchoredIcon(R.drawable.ic_plus, "Добавить подписку", topColor(), 48, this::importMenu));

        Subscription current = selected();
        Node node = current == null || current.nodes.isEmpty() ? null : current.nodes.get(selectedIndex(current));
        boolean connected = TunnelService.status.equals("connected");
        boolean connecting = TunnelService.status.equals("connecting");

        LinearLayout controls = column();
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams controlLayout = new LinearLayout.LayoutParams(-1, -2);
        controlLayout.topMargin = dp(24);
        controlLayout.bottomMargin = dp(26);
        page.addView(controls, controlLayout);
        TextView status = label(connected ? "VPN подключён" : connecting ? "Подключаем VPN…" : "VPN отключён", 15,
                connected ? accent() : quiet(), true);
        status.setGravity(Gravity.CENTER);
        controls.addView(status, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout center = new FrameLayout(this);
        int powerSize = getResources().getConfiguration().screenWidthDp < 360 ? 136 : 148;
        LinearLayout.LayoutParams centerSize = new LinearLayout.LayoutParams(dp(powerSize + 38), dp(powerSize + 38));
        centerSize.topMargin = dp(13);
        centerSize.bottomMargin = dp(12);
        controls.addView(center, centerSize);
        int outer = tone("selectedServerRowColor", "#55283B");
        GradientDrawable halo = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                theme.powerBackdrop(connected ? Palette.blend(outer, accent(), .16f) : outer));
        halo.setShape(GradientDrawable.OVAL);
        center.setBackground(halo);
        ImageView power = glyph(R.drawable.ic_power, tone("powerIconColor", "#4B1D31"), (powerSize - 49) / 2);
        power.setBackground(interactive(accent(), 100));
        animatePress(power, .92f);
        power.setContentDescription(connected || connecting ? "Отключить VPN" : "Подключить VPN");
        power.setEnabled(!connecting);
        power.setOnClickListener(view -> {
            if (!TunnelService.status.equals("idle")) stopVpn();
            else if (current == null) importDialog("");
            else requestVpn(current);
        });
        FrameLayout.LayoutParams button = new FrameLayout.LayoutParams(dp(powerSize), dp(powerSize), Gravity.CENTER);
        center.addView(power, button);

        LinearLayout selection = row();
        selection.setGravity(Gravity.CENTER);
        String selectedName = connected ? TunnelService.connectedName : node != null ? node.title() : "";
        boolean hasFlag = !selectedName.isEmpty() && addFlag(selection, selectedName, 2);
        TextView chosen = label(selectedName.isEmpty() ? "Добавь подписку, чтобы выбрать узел"
                : hasFlag ? Art.withoutFlag(selectedName) : selectedName, 16, ink(), true);
        chosen.setGravity(Gravity.CENTER);
        selection.addView(chosen, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(selection, new LinearLayout.LayoutParams(-1, -2));
        if (connected) {
            timerLabel = label("00:00", 13, tone("buttonTimerColor", "#FFFFFF"), false);
            timerLabel.setFontFeatureSettings("tnum");
            timerLabel.setGravity(Gravity.CENTER);
            controls.addView(timerLabel, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!TunnelService.failure.isEmpty()) addNotice(TunnelService.failure);
        if (!notice.isEmpty()) addNotice(notice);

        List<Subscription> profiles = profiles();
        LinearLayout listTop = row();
        listTop.addView(label("Подписки", 18, ink(), true), flex());
        if (!profiles.isEmpty()) {
            listTop.addView(action(allCollapsed ? "Развернуть все" : "Скрыть все", quiet(), 13, () -> {
                allCollapsed = !allCollapsed;
                collapsed.clear();
                expanded.clear();
                if (!allCollapsed) for (Subscription p : profiles) expanded.add(p.id);
                render();
            }));
        }
        page.addView(listTop, new LinearLayout.LayoutParams(-1, dp(42)));
        if (profiles.isEmpty()) {
            LinearLayout empty = column();
            empty.setPadding(dp(17), dp(17), dp(17), dp(9));
            empty.setBackground(shape(tone("serverRowBackgroundColor", "#27131C"), 18));
            empty.addView(label("Подписок пока нет", 15, ink(), true));
            TextView copy = label("Вставь ссылку InvokeVPN или выбери JSON-файл.", 13, quiet(), false);
            copy.setPadding(0, dp(5), 0, dp(5));
            empty.addView(copy);
            empty.addView(action("Добавить подписку", accent(), 14, () -> importDialog("")));
            page.addView(empty);
        }
        for (Subscription profile : profiles) drawProfile(profile, current);
    }

    private void drawProfile(Subscription profile, Subscription selectedProfile) {
        LinearLayout card = profileCards.get(profile.id);
        if (card == null) {
            card = column();
            profileCards.put(profile.id, card);
            LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
            cardLayout.bottomMargin = dp(12);
            page.addView(card, cardLayout);
        } else {
            badges.values().forEach(views -> views.removeIf(badge -> badge.profileId().equals(profile.id)));
            badges.values().removeIf(List::isEmpty);
            card.removeAllViews();
        }
        card.setBackground(shape(tone("serverRowBackgroundColor", "#27131C"), 18));
        card.setClipToOutline(true);
        boolean closed = allCollapsed || collapsed.contains(profile.id) || (!expanded.contains(profile.id)
                && profile.flag("subscriptions-collapse", false));
        LinearLayout header = row();
        header.setPadding(dp(2), dp(7), dp(3), dp(7));
        header.setBackgroundColor(tone("subsHeaderColor", "#402131"));
        header.addView(icon(closed ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_down, closed ? "Развернуть" : "Свернуть", topColor(), 43, () -> {
            if (closed) { allCollapsed = false; collapsed.remove(profile.id); expanded.add(profile.id); }
            else { expanded.remove(profile.id); collapsed.add(profile.id); }
            animateLayout(profileCards.get(profile.id));
            drawProfile(profile, selected());
        }));
        LinearLayout title = column();
        TextView heading = label(profile.text("profile-title", "InvokeVPN"), 16, ink(), true);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.addView(heading);
        String time = new SimpleDateFormat("dd.MM HH:mm", russian).format(new Date(profile.refreshedAt));
        int width = getResources().getConfiguration().screenWidthDp;
        boolean compact = width < 490 || getResources().getConfiguration().fontScale > 1.1f
                || store.preferences.getBoolean("large_type", false);
        String interval = config.refreshOnSchedule(profile) ? "Автообновление - " + config.refreshHours(profile) + "ч" : "Автообновление выкл.";
        String update = profile.source.isEmpty() ? time + " · JSON" : time + (compact ? "\n" : " | ") + interval;
        TextView updated = label(update, 11, quiet(), false);
        updated.setPadding(0, dp(3), 0, 0);
        title.addView(updated);
        header.addView(title, flex());
        if (!profile.source.isEmpty()) {
            View refresh = icon(R.drawable.ic_refresh_cw, "Обновить подписку", topColor(), 42,
                    () -> refresh(profile.source, profile, true));
            refreshButtons.put(profile.source, refresh);
            header.addView(refresh);
            if (refreshing.contains(profile.source)) paintRefresh(refresh, true);
        }
        header.addView(icon(R.drawable.ic_gauge, "Проверить задержку", topColor(), 44, () -> ping(profile, false)));
        header.addView(anchoredIcon(R.drawable.ic_ellipsis, "Действия с подпиской", topColor(), 42, view -> actions(view, profile)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (closed) return;
        infoRow(card, profile);
        String announcement = profile.text("announce", "");
        if (!announcement.isEmpty()) {
            TextView line = label(announcement, 12, quiet(), false);
            line.setPadding(dp(15), dp(10), dp(15), dp(13));
            line.setLineSpacing(dp(2), 1f);
            String destination = profile.link("announce-url");
            if (!destination.isEmpty()) line.setOnClickListener(view -> open(destination));
            card.addView(line);
        }
        if (profile.expired() && profile.flag("sub-expire", true)) {
            card.addView(action("Срок подписки истёк · Продлить", accent(), 14, () -> renewal(profile)));
        }
        if (profile.nodes.isEmpty()) {
            TextView empty = label("Ни одного доступного узла", 13, quiet(), false);
            empty.setPadding(dp(15), dp(10), dp(15), dp(16));
            card.addView(empty);
        } else if (!profile.flag("hide-servers", false)) {
            List<Node> sorted = new ArrayList<>(profile.nodes);
            switch (config.sort(profile)) {
                case "ping" -> sorted.sort(Comparator.comparingLong(node -> {
                    long ms = latency.getOrDefault(node.link, -1L);
                    return ms < 0 ? Long.MAX_VALUE : ms;
                }));
                case "name" -> sorted.sort(Comparator.comparing(Node::title));
            }
            for (Node node : sorted) {
                int index = profile.nodes.indexOf(node);
                boolean selected = selectedProfile != null && selectedProfile.id.equals(profile.id) && index == selectedIndex(profile);
                drawNode(card, profile, node, index, selected);
            }
        }
    }

    private void infoRow(LinearLayout card, Subscription profile) {
        LinearLayout row = row();
        row.setPadding(dp(2), 0, dp(2), 0);
        row.setBackgroundColor(tone("subscriptionInfoBackgroundColor", "#29141E"));
        int infoColor = tone("subscriptionInfoTextColor", "#FFF5F8");
        row.addView(icon(R.drawable.ic_info, "Информация о подписке", quiet(), 40, () -> info(profile)));
        long total = profile.userinfo("total");
        long upload = profile.userinfo("upload"), download = profile.userinfo("download");
        long used = Long.MAX_VALUE - download < upload ? Long.MAX_VALUE : upload + download;
        String quota = !profile.hasUserinfo() ? "Трафик —" : total == 0 ? "Без лимита" : Art.bytes(used) + " / " + Art.bytes(total);
        TextView traffic = label(quota, 11, infoColor, true);
        traffic.setGravity(Gravity.CENTER);
        traffic.setPadding(dp(5), dp(5), dp(5), dp(5));
        FrameLayout quotaBar = new FrameLayout(this);
        int base = tone("subscriptionTrafficBackgroundColor", "#562B3D");
        Drawable background = shape(base, 20);
        if (total > 0 && used > 0) {
            ClipDrawable fill = new ClipDrawable(new ColorDrawable(Palette.blend(base, accent(), .45f)),
                    Gravity.START, ClipDrawable.HORIZONTAL);
            background = new LayerDrawable(new Drawable[]{background, fill});
            background.setLevel((int) Math.round(10_000 * Math.min(1.0, (double) used / total)));
        }
        quotaBar.setBackground(background);
        quotaBar.setClipToOutline(true);
        quotaBar.addView(traffic, new FrameLayout.LayoutParams(-1, -2));
        row.addView(quotaBar, flex());
        TextView expiry = label(expiryText(profile), 11, quiet(), false);
        expiry.setGravity(Gravity.CENTER);
        expiry.setPadding(dp(7), 0, dp(1), 0);
        row.addView(expiry, flex());
        row.addView(icon(R.drawable.ic_telegram, "Поддержка", tone("supportIconColor", "#FFADC7"), 43,
                () -> open(SUPPORT)));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void drawNode(LinearLayout card, Subscription profile, Node node, int index, boolean selected) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(50));
        int background = tone(selected ? "selectedServerRowColor" : "serverRowBackgroundColor", selected ? "#55283B" : "#27131C");
        row.setBackground(interactive(background, 0));
        View line = new View(this);
        line.setBackgroundColor(selected ? accent() : Color.TRANSPARENT);
        row.addView(line, new LinearLayout.LayoutParams(dp(3), dp(34)));
        LinearLayout text = column();
        boolean flag = addFlag(row, node.title(), 9);
        text.setPadding(dp(flag ? 8 : 12), dp(8), dp(4), dp(8));
        text.addView(label(flag ? Art.withoutFlag(node.title()) : node.title(), 14, ink(), true));
        text.addView(label(node.protocolLabel(), 11, quiet(), false));
        row.addView(text, flex());
        PingIndicator indicator = new PingIndicator(this);
        indicator.setTypeface(semibold);
        indicator.setTextSize(store.preferences.getBoolean("large_type", false) ? 14 : 12);
        indicator.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        indicator.setPadding(0, 0, dp(12), 0);
        indicator.setMinWidth(dp(42));
        Badge badge = new Badge(profile.id, indicator, config.pingDisplay(profile));
        badges.computeIfAbsent(node.link, ignored -> new ArrayList<>()).add(badge);
        updateBadge(node.link, badge);
        row.addView(indicator);
        row.setOnClickListener(view -> {
            if (view.isPressed()) view.setPressed(false);
            choose(profile, index);
            notice = "";
            render();
        });
        row.setOnLongClickListener(view -> {
            nodeActions(view, profile, node, index);
            return true;
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void drawSettings(String settingsPage) {
        LinearLayout top = row();
        top.addView(icon(R.drawable.ic_arrow_left, "Назад", topColor(), 48, this::backSettings));
        TextView title = label(settingsPage.isEmpty() ? "Настройки" : settingsPage, 20, ink(), true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, flex());
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(dp(48), 1));
        page.addView(top);

        switch (settingsPage) {
            case "Подключение" -> connectionSettings();
            case "Туннель" -> tunnelSettings();
            case "Маршрутизация" -> routingSettings();
            case "Приложения" -> appSettings();
            case "Провайдеры" -> providerSettings();
            case "Проверка задержки" -> pingSettings();
            case "Оформление" -> appearanceSettings();
            case "Копия настроек" -> backupSettings();
            case "Справка и диагностика" -> helpSettings();
            default -> {
                LinearLayout connection = section("VPN");
                settingsLink(connection, "Подключение", "Автоподключение, плитка и Kill Switch", R.drawable.ic_power);
                settingsLink(connection, "Туннель", "IP, MTU и параметры транспорта", R.drawable.ic_gauge);
                settingsLink(connection, "Маршрутизация", "Правила сайтов и сетей", R.drawable.ic_link);
                settingsLink(connection, "Приложения", appModeName(), R.drawable.ic_link);
                LinearLayout subscriptions = section("Подписки и серверы");
                settingsLink(subscriptions, "Провайдеры", "Обновление, сортировка и автоподключение", R.drawable.ic_refresh_cw);
                settingsLink(subscriptions, "Проверка задержки", "TCP, ICMP и HTTPS", R.drawable.ic_gauge);
                LinearLayout application = section("Приложение");
                settingsLink(application, "Оформление", "Темы и размер текста", R.drawable.ic_settings);
                settingsLink(application, "Копия настроек", "Перенос параметров в JSON", R.drawable.ic_file_json);
                settingsLink(application, "Справка и диагностика", "Состояние соединения и поддержка", R.drawable.ic_info);
            }
        }
    }

    private void connectionSettings() {
        LinearLayout auto = section("Запуск VPN");
        textPreference(auto, "Автоподключение", "auto_connect", new String[]{"Из подписки", "Выключено", "Последний узел", "Минимальный пинг", "Первый узел"},
                new String[]{"default", "off", "lastused", "lowestdelay", "first"}, "default");
        settingRow(auto, "Постоянный VPN и Kill Switch", "", this::showAlwaysOn);
        LinearLayout control = section("Быстрые настройки");
        settingRow(control, "Добавить плитку VPN", "Шторка", this::addQuickTile);
        toggle(control, "Подпись на плитке", "Показывать состояние VPN", "tile_subtitle", true);
    }

    private void tunnelSettings() {
        LinearLayout networkRows = section("Сеть");
        choicePreference(networkRows, "Сетевые адреса", new String[]{"IPv4 и IPv6", "Только IPv4"},
                config.ipv6Enabled() ? 0 : 1,
                index -> store.preferences.edit().putBoolean("ipv6", index == 0).apply());
        numberPreference(networkRows, "MTU", "tun_mtu", new int[]{1280, 1360, 1400, 1480, 1500}, 1400,
                new String[]{"1280", "1360", "1400", "1480", "1500"});
        settingRow(networkRows, "DNS туннеля", dnsName(), this::chooseDns);
        toggle(networkRows, "Локальный SOCKS5 и HTTP", "127.0.0.1 · порты 10808 и 10809", "local_proxy", false);
        LinearLayout transport = section("Xray");
        inheritedToggle(transport, "Распознавание доменов", "sniffing", "Влияет на правила маршрутизации");
        toggle(transport, "Фрагментирование TLS", "Если после включения пропал интернет, выключи и переподключись", "fragment", false);
        toggle(transport, "Mux", "Для VLESS без Vision", "mux", false);
        LinearLayout background = section("Фоновая работа");
        toggle(background, "Скорость в уведомлении", "Счётчики Xray · после переподключения", "notification_speed", false);
        toggle(background, "Не давать устройству засыпать", "При активном VPN · увеличивает расход батареи", "keep_awake", false);
        settingRow(background, "Оптимизация батареи", "Android",
                () -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
    }

    private void routingSettings() {
        LinearLayout presets = section("Правила сайтов и сетей");
        toggle(presets, "Применять правила", "Для следующего подключения", "routing_enabled", true);
        toggle(presets, "Российские сайты напрямую", ".ru, .su и список сервисов", "split", true);
        toggle(presets, "Локальная сеть напрямую", "Для роутеров и устройств в LAN", "lan", true);
        textPreference(presets, "Стратегия доменов", "routing_strategy",
                new String[]{"Сохранять домены", "IP, если нет совпадения", "IP при проверке правила"},
                new String[]{"AsIs", "IPIfNonMatch", "IPOnDemand"}, "AsIs");
        LinearLayout rules = section("Свои правила");
        addRoute(rules, "Через VPN", "routes_vpn");
        addRoute(rules, "Напрямую", "routes_direct");
        addRoute(rules, "Блокировать", "routes_block");
        settingsLink(section("Раздельное туннелирование"), "Приложения", appModeName(), R.drawable.ic_link);
    }

    private void providerSettings() {
        LinearLayout updates = section("Обновление");
        inheritedToggle(updates, "Автообновление", "auto_update", "Работает в фоне");
        numberPreference(updates, "Интервал", "update_interval", new int[]{0, 1, 2, 3, 6, 12, 24}, 0,
                new String[]{"Из подписки", "1 час", "2 часа", "3 часа", "6 часов", "12 часов", "24 часа"});
        toggle(updates, "Уведомлять об обновлении", "После успешного обновления подписки", "notify_refresh", false);
        inheritedToggle(updates, "Обновлять при открытии", "open_update", "При запуске приложения");
        inheritedToggle(updates, "Пинг при открытии", "ping_on_open", "Для узлов подписки");
        toggle(updates, "Пинг после обновления", "При обновлении подписки в приложении", "ping_on_update", false);
        LinearLayout list = section("Серверы");
        textPreference(list, "Сортировка", "node_sort", new String[]{"Из подписки", "Как в подписке", "По имени", "По задержке"},
                new String[]{"default", "none", "name", "ping"}, "default");
        LinearLayout headers = section("Запрос к провайдеру");
        inheritedToggle(headers, "Отправлять HWID", "subscription_hwid", "Используется случайный идентификатор этого клиента");
        settingRow(headers, "User-Agent", "", anchor -> editSetting(anchor,
                "Заголовок для запроса подписки. Пустое поле возвращает стандартный заголовок InvokeVPN.",
                store.preferences.getString("subscription_agent", ""), "InvokeVPN/1.0 Android", false, value -> {
                    String agent = value.trim();
                    if (agent.length() > 180 || !agent.matches("[ -~]*")) throw new IllegalArgumentException("Допустимо до 180 символов ASCII без переносов");
                    var editor = store.preferences.edit();
                    if (agent.isEmpty()) editor.remove("subscription_agent");
                    else editor.putString("subscription_agent", agent);
                    editor.apply();
                }));
        settingRow(list, "Импорт подписки", "Ссылка / JSON", () -> importDialog(""));
    }

    private void pingSettings() {
        LinearLayout options = section("Проверка");
        settingRow(options, "Метод", methodLabel(store.preferences.getString("ping_method", "tcp")), this::choosePing);
        numberPreference(options, "Таймаут", "ping_timeout", new int[]{0, 1000, 3000, 5000, 10000}, 0,
                new String[]{"По методу", "1 сек", "3 сек", "5 сек", "10 сек"});
        settingRow(options, "Адрес HTTPS-проверки",
                Uri.parse(store.preferences.getString("ping_url", Subscription.DEFAULT_PING)).getHost(), this::choosePingUrl);
        textPreference(options, "Отображение", "ping_display", new String[]{"Из подписки", "Миллисекунды", "Шкала", "Шкала и цифры", "Точка"},
                new String[]{"default", "time", "icon", "both", "dot"}, "default");
    }

    private void appearanceSettings() {
        LinearLayout appearance = section("Тема и текст");
        settingRow(appearance, "Тема оформления", themeName(), this::chooseTheme);
        choicePreference(appearance, "Размер текста", new String[]{"Обычный", "Крупный"},
                store.preferences.getBoolean("large_type", false) ? 1 : 0,
                index -> store.preferences.edit().putBoolean("large_type", index == 1).apply());
        settingRow(appearance, "Импорт темы", "JSON", () -> pickFile(IMPORT_THEME));
        settingRow(appearance, "Экспорт темы", "JSON", this::saveTheme);
    }

    private void backupSettings() {
        LinearLayout transfer = section("JSON-файл");
        menuNote(transfer, "В копию входят параметры, темы и правила. Для переноса подписок используй их ссылки или JSON-файлы.");
        settingRow(transfer, "Сохранить настройки", "JSON", () -> {
            Intent file = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "invokevpn-settings.json");
            startActivityForResult(file, EXPORT_SETTINGS);
        });
        settingRow(transfer, "Загрузить настройки", "JSON", () -> pickFile(IMPORT_SETTINGS));
    }

    private void helpSettings() {
        LinearLayout help = section("Приложение");
        settingRow(help, "Состояние подключения", TunnelService.status.equals("connected") ? "Активно" : "Отключено", this::showDiagnostics);
        settingRow(help, "Поддержка", "Telegram", () -> open(SUPPORT));
    }

    private void settingsLink(LinearLayout group, String title, String subtitle, int resource) {
        LinearLayout item = row();
        item.setMinimumHeight(dp(63));
        item.setPadding(dp(12), dp(6), dp(9), dp(6));
        item.setBackground(interactive(tone("serverRowBackgroundColor", "#27131C"), 0));
        ImageView image = glyph(resource, accent(), 8);
        image.setBackground(shape(tone("subsHeaderColor", "#402131"), 10));
        item.addView(image, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout copy = column();
        copy.setPadding(dp(11), 0, dp(5), 0);
        copy.addView(label(title, 15, ink(), true));
        copy.addView(label(subtitle, 11, quiet(), false));
        item.addView(copy, flex());
        item.addView(glyph(R.drawable.ic_chevron_right, quiet(), 7), new LinearLayout.LayoutParams(dp(26), dp(30)));
        item.setOnClickListener(view -> openSettingsPage(title));
        group.addView(item, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openSettingsPage(String title) {
        if (atHome() && !notice.isEmpty()) {
            notice = "";
            revision++;
        }
        Screen next = new Screen(title);
        paintScreen(next);
        navigation.push(next, true);
        page = next.body;
        updateSystemBack();
    }

    private void backSettings() {
        returnTo((Screen) navigation.previous());
    }

    private void returnTo(Screen target) {
        if (target == null || target == navigation.current()) return;
        paintScreen(target);
        navigation.popTo(target);
        page = target.body;
        updateSystemBack();
    }

    private void choicePreference(LinearLayout group, String title, String[] options, int selected,
                                  java.util.function.IntConsumer apply) {
        String value = selected >= 0 ? options[selected] : "";
        settingRow(group, title, value, anchor -> chooseOption(anchor, options, selected, index -> {
            apply.accept(index);
            render();
        }));
    }

    private void textPreference(LinearLayout group, String title, String key, String[] options, String[] values, String fallback) {
        int selected = java.util.Arrays.asList(values).indexOf(store.preferences.getString(key, fallback));
        choicePreference(group, title, options, selected, index -> store.preferences.edit().putString(key, values[index]).apply());
    }

    private void numberPreference(LinearLayout group, String title, String key, int[] values, int fallback, String[] options) {
        int value = store.preferences.getInt(key, fallback);
        int selected = -1;
        for (int i = 0; i < values.length; i++) if (value == values[i]) selected = i;
        choicePreference(group, title, options, selected, index -> {
            if (values[index] == 0) store.preferences.edit().remove(key).apply();
            else store.preferences.edit().putInt(key, values[index]).apply();
        });
    }

    private void inheritedToggle(LinearLayout group, String title, String key, String description) {
        String[] options = {"Из подписки", "Включено", "Выключено"};
        int selected = store.preferences.contains(key) ? (store.preferences.getBoolean(key, false) ? 1 : 2) : 0;
        settingRow(group, title, options[selected], anchor -> {
            LinearLayout content = column();
            menuNote(content, description);
            for (int i = 0; i < options.length; i++) {
                int option = i;
                settingOption(content, options[i], option == selected ? R.drawable.ic_check : 0, () -> {
                    var update = store.preferences.edit();
                    if (option == 0) update.remove(key);
                    else update.putBoolean(key, option == 1);
                    update.apply();
                    render();
                });
            }
            showSetting(anchor, content);
        });
    }

    private String dnsName() {
        return switch (store.preferences.getString("vpn_dns", "auto")) {
            case "cloudflare" -> "Cloudflare";
            case "google" -> "Google";
            case "custom" -> "Свой DoH";
            default -> "Авто";
        };
    }

    private void chooseDns(View anchor) {
        String[] labels = {"Авто", "Cloudflare", "Google", "Свой DoH"};
        String[] choices = {"auto", "cloudflare", "google", "custom"};
        int index = java.util.Arrays.asList(choices).indexOf(store.preferences.getString("vpn_dns", "auto"));
        chooseOption(anchor, labels, index, selected -> {
            if (selected == 3) {
                editSetting(anchor, "Используется после переподключения.",
                        store.preferences.getString("vpn_dns_url", ""), "https://dns.example.com/dns-query", false, value -> {
                            String url = Subscription.https(value.trim()).toASCIIString();
                            if (Uri.parse(url).getFragment() != null) throw new IllegalArgumentException("Укажи HTTPS-адрес без фрагмента");
                            store.preferences.edit().putString("vpn_dns", "custom").putString("vpn_dns_url", url).apply();
                        });
            } else {
                store.preferences.edit().putString("vpn_dns", choices[selected]).apply();
                render();
            }
        });
    }

    private void addRoute(LinearLayout group, String title, String key) {
        String text = store.preferences.getString(key, "");
        settingRow(group, title, Integer.toString(text.isEmpty() ? 0 : text.split("\\R").length),
                anchor -> editSetting(anchor, "По одному домену, IP-адресу или CIDR на строку. Сначала применяются блокировки, затем правила VPN, затем прямые.",
                        text, "example.com\n192.0.2.0/24", true,
                        value -> store.preferences.edit().putString(key, RoutingRules.normalize(value)).apply()));
    }

    private void editSetting(View anchor, String description, String value, String hint, boolean multiline,
                             java.util.function.Consumer<String> save) {
        LinearLayout content = column();
        if (!description.isEmpty()) menuNote(content, description);
        EditText field = editField(value, hint);
        if (multiline) {
            field.setSingleLine(false);
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setTypeface(regular);
            field.setMinLines(3);
            field.setMaxLines(5);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        content.addView(field);
        settingOption(content, "Сохранить", R.drawable.ic_check, () -> {
            String edited = field.getText().toString();
            try {
                save.accept(edited);
                render();
            } catch (IllegalArgumentException error) {
                editSetting(anchor, error.getMessage(), edited, hint, multiline, save);
            }
        });
        showSetting(anchor, content, true);
    }

    private String appModeName() {
        return switch (store.preferences.getString("app_routing_mode", "preset")) {
            case "all" -> "Все через VPN";
            case "bypass" -> "Выбранные напрямую";
            case "only" -> "Только выбранные через VPN";
            default -> "Готовый список исключений";
        };
    }

    private void appSettings() {
        LinearLayout mode = section("Режим");
        textPreference(mode, "Маршрутизация приложений", "app_routing_mode",
                new String[]{"Готовый список исключений", "Все через VPN", "Выбранные напрямую", "Только выбранные через VPN"},
                new String[]{"preset", "all", "bypass", "only"}, "preset");
        Set<String> selected = AppRouting.packages(store);
        LinkedHashMap<String, String> installed = new LinkedHashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        var matches = getPackageManager().queryIntentActivities(launcher, 0);
        var order = java.text.Collator.getInstance(russian);
        matches.sort((a, b) -> order.compare(a.loadLabel(getPackageManager()).toString(), b.loadLabel(getPackageManager()).toString()));
        for (var match : matches) {
            String id = match.activityInfo.packageName;
            if (!id.equals(getPackageName())) installed.putIfAbsent(id, match.loadLabel(getPackageManager()).toString());
        }
        for (String id : selected) if (!id.equals(getPackageName())) installed.putIfAbsent(id, id);
        LinearLayout list = section("Выбранные приложения");
        settingRow(list, "Добавить по имени пакета", "", anchor -> editSetting(anchor, "Можно добавить приложение без значка в меню Android.", "", "org.example.app", false, value -> {
                    String id = value.trim();
                    if (!id.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*") || id.equals(getPackageName())) {
                        throw new IllegalArgumentException("Укажи корректное имя пакета");
                    }
                    var packages = AppRouting.packages(store);
                    packages.add(id);
                    store.preferences.edit().putStringSet("routing_apps", packages).apply();
                }));
        installed.forEach((id, name) -> {
            LinearLayout item = row();
            item.setPadding(dp(14), dp(6), dp(12), dp(6));
            LinearLayout copy = column();
            copy.addView(label(name, 14, ink(), true));
            copy.addView(label(id, 11, quiet(), false));
            item.addView(copy, flex());
            CheckBox box = new CheckBox(this);
            box.setContentDescription(name);
            box.setChecked(selected.contains(id));
            box.setButtonTintList(ColorStateList.valueOf(accent()));
            box.setOnCheckedChangeListener((control, checked) -> {
                var packages = AppRouting.packages(store);
                if (checked) packages.add(id);
                else packages.remove(id);
                store.preferences.edit().putStringSet("routing_apps", packages).apply();
            });
            item.addView(box, new LinearLayout.LayoutParams(dp(46), dp(42)));
            item.setOnClickListener(view -> box.toggle());
            list.addView(item);
        });
    }

    private void showAlwaysOn(View anchor) {
        LinearLayout content = column();
        menuNote(content, "В настройках VPN Android открой шестерёнку рядом с InvokeVPN. Включи «Постоянный VPN». Чтобы при обрыве туннеля Android блокировал трафик, включи «Блокировать подключения без VPN».");
        settingOption(content, "Открыть настройки VPN", R.drawable.ic_settings, () -> startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)));
        showSetting(anchor, content);
    }

    private void showDiagnostics(View anchor) {
        String status = TunnelService.status.equals("connected") ? "VPN подключён"
                : TunnelService.status.equals("connecting") ? "VPN подключается" : "VPN отключён";
        String message = status + "\nAndroid " + Build.VERSION.RELEASE + "\nПодписок: " + profiles().size()
                + "\nПинг: " + methodLabel(store.preferences.getString("ping_method", "tcp"))
                + "\nMTU: " + config.mtu() + "\nDNS: " + dnsName() + "\nПриложения: " + appModeName();
        if (!TunnelService.failure.isEmpty()) message += "\nПоследний запуск завершился ошибкой";
        String report = message;
        LinearLayout content = column();
        menuNote(content, report);
        settingOption(content, "Скопировать сведения", R.drawable.ic_copy, () -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                    android.content.ClipData.newPlainText("InvokeVPN", report));
        });
        settingOption(content, "Готово", R.drawable.ic_check, () -> {});
        showSetting(anchor, content);
    }

    private LinearLayout section(String title) {
        TextView label = label(title.toUpperCase(russian), 11, quiet(), true);
        label.setLetterSpacing(.1f);
        label.setPadding(dp(4), dp(21), 0, dp(8));
        page.addView(label);
        LinearLayout group = column();
        group.setBackground(shape(tone("serverRowBackgroundColor", "#27131C"), 15));
        group.setClipToOutline(true);
        page.addView(group);
        return group;
    }

    private void settingRow(LinearLayout group, String title, String value, Runnable action) {
        settingRow(group, title, value, row -> action.run());
    }

    private void settingRow(LinearLayout group, String title, String value, java.util.function.Consumer<View> action) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(51));
        row.setPadding(dp(14), 0, dp(6), 0);
        row.addView(label(title, 14, ink(), false), flex());
        TextView current = label(value, 13, quiet(), false);
        current.setMaxWidth(dp(Math.min(220, getResources().getConfiguration().screenWidthDp * .42f)));
        current.setGravity(Gravity.END);
        row.addView(current);
        ImageView chevron = glyph(R.drawable.ic_chevron_right, tone("serverRowChevronColor", "#FFADC7"), 9);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(34), dp(36)));
        row.setBackground(interactive(tone("serverRowBackgroundColor", "#27131C"), 0));
        row.setTag(title);
        row.setOnClickListener(action::accept);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggle(LinearLayout group, String title, String description, String key, boolean fallback) {
        LinearLayout row = row();
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(14), dp(6), dp(12), dp(6));
        LinearLayout copy = column();
        copy.addView(label(title, 14, ink(), false));
        copy.addView(label(description, 11, quiet(), false));
        row.addView(copy, flex());
        LiquidGlassSwitch control = new LiquidGlassSwitch(this, tone("settingsControlsTintColor", "#FF92B6"),
                tone("subscriptionTrafficBackgroundColor", "#562B3D"));
        control.setContentDescription(title);
        control.setChecked(store.preferences.getBoolean(key, fallback));
        control.setOnCheckedChangeListener((button, checked) -> {
            store.preferences.edit().putBoolean(key, checked).apply();
            if (checked && (key.equals("notify_refresh") || key.equals("notification_speed"))) requestNotificationPermission();
        });
        LinearLayout.LayoutParams switchBounds = new LinearLayout.LayoutParams(dp(68), dp(48));
        switchBounds.leftMargin = dp(10);
        row.addView(control, switchBounds);
        row.setOnClickListener(view -> control.performClick());
        group.addView(row);
    }

    private void addQuickTile(View anchor) {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager statusBar = getSystemService(StatusBarManager.class);
            statusBar.requestAddTileService(new ComponentName(this, VpnTile.class), "InvokeVPN",
                    Icon.createWithResource(this, R.drawable.ic_status_vpn), getMainExecutor(), result -> {
                        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            Toast.makeText(this, "Плитка InvokeVPN уже добавлена", Toast.LENGTH_SHORT).show();
                        }
                    });
            return;
        }
        LinearLayout content = column();
        menuNote(content, "Открой быстрые настройки Android, нажми «Изменить» и перетащи плитку InvokeVPN в активную область.");
        settingOption(content, "Готово", R.drawable.ic_check, () -> {});
        showSetting(anchor, content);
    }

    private String themeName() {
        return switch (store.preferences.getString("theme", "rose")) {
            case "graphite" -> "Графит";
            case "violet" -> "Фиолетовая";
            case "system" -> "Системная";
            case "custom" -> "Импортированная";
            default -> "Розовая";
        };
    }

    private void chooseTheme(View anchor) {
        String[] names = {"Розовая", "Графит", "Фиолетовая", "Системная", "Импортировать JSON"};
        String[] ids = {"rose", "graphite", "violet", "system"};
        int selected = java.util.Arrays.asList(ids).indexOf(store.preferences.getString("theme", "rose"));
        chooseOption(anchor, names, selected, index -> {
            if (index == ids.length) pickFile(IMPORT_THEME);
            else {
                store.preferences.edit().putString("theme", ids[index]).apply();
                render();
            }
        });
    }

    private String methodLabel(String key) {
        return switch (key) {
            case "tcp" -> "TCP";
            case "icmp" -> "ICMP";
            case "proxy" -> "GET через узел";
            case "head" -> "HEAD через узел";
            default -> "Из подписки";
        };
    }

    private void choosePing(View anchor) {
        String[] choices = {"tcp", "icmp", "proxy", "head", "default"};
        String[] names = new String[choices.length];
        for (int i = 0; i < choices.length; i++) names[i] = methodLabel(choices[i]);
        int selected = java.util.Arrays.asList(choices).indexOf(store.preferences.getString("ping_method", "tcp"));
        chooseOption(anchor, names, selected, index -> {
            store.preferences.edit().putString("ping_method", choices[index]).apply();
            render();
        });
    }

    private void choosePingUrl(View anchor) {
        editSetting(anchor, "", store.preferences.getString("ping_url", Subscription.DEFAULT_PING),
                "https://…", false, value -> {
                    String input = value.trim();
                    if (input.isEmpty()) store.preferences.edit().remove("ping_url").apply();
                    else store.preferences.edit().putString("ping_url", Subscription.https(input).toASCIIString()).apply();
                });
    }

    private void pickFile(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, request);
    }

    private void saveTheme() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "invokevpn-theme.json");
        startActivityForResult(intent, 51);
    }

    private void importMenu(View anchor) {
        String title = "Добавить подписку";
        LinearLayout content = menuContent(title);
        optionRow(content, "Вставить из буфера", R.drawable.ic_clipboard, ink(), () -> {
            var clipboard = getSystemService(android.content.ClipboardManager.class);
            var clip = clipboard.getPrimaryClip();
            String text = clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null
                    ? "" : clip.getItemAt(0).getText().toString();
            importDialog(text.length() <= 4096 ? text : "");
        });
        optionRow(content, "Ввести ссылку или ключ", R.drawable.ic_link, ink(), () -> importDialog(""));
        menuRow(content, "Открыть JSON", R.drawable.ic_file_json, () -> pickFile(IMPORT_SUBSCRIPTION));
        showGlass(anchor, content, title, false);
    }

    private EditText editField(String value, String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        field.setTypeface(regular);
        field.setTextColor(ink());
        field.setHintTextColor(quiet());
        field.setTextSize(14);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(shape(Palette.blend(tone("serverRowBackgroundColor", "#27131C"), Color.WHITE, .1f), 12));
        field.setHint(hint);
        field.setText(value);
        field.setSelection(value.length());
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, -2);
        box.setMargins(dp(7), dp(5), dp(7), dp(8));
        field.setLayoutParams(box);
        return field;
    }

    private void importDialog(String initial) {
        String title = "Добавить подписку";
        LinearLayout content = menuContent(title);
        EditText field = editField(initial, "invokevpn://sub=…");
        content.addView(field);
        menuRow(content, "Добавить", R.drawable.ic_plus, () -> {
            try {
                String source = Subscription.source(field.getText().toString());
                Subscription previous = profiles().stream().filter(p -> p.source.equals(source)).findFirst().orElse(null);
                refresh(source, previous, true);
            } catch (IllegalArgumentException error) {
                addResult("Нужна ссылка InvokeVPN, ключ или HTTPS-ссылка");
            }
        });
        menuRow(content, "Открыть JSON", R.drawable.ic_file_json, () -> pickFile(IMPORT_SUBSCRIPTION));
        showGlass(null, content, title, true);
    }

    private void refresh(String source, Subscription previous, boolean feedback) {
        if (!refreshing.add(source)) return;
        paintRefresh(refreshButtons.get(source), true);
        network.execute(() -> {
            try {
                Subscription profile = RefreshJob.refresh(this, store, source, previous);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    refreshing.remove(source);
                    if (feedback) Toast.makeText(this, previous == null ? "Подписка добавлена" : "Подписка обновлена", Toast.LENGTH_SHORT).show();
                    if (previous == null) {
                        choose(profile, 0);
                        returnTo((Screen) navigation.entry(0));
                        render();
                    } else {
                        if (atHome() && profileCards.containsKey(profile.id)) drawProfile(profile, selected());
                        if (store.preferences.getBoolean("ping_on_update", false)) ping(profile, false);
                    }
                    refreshNotification(profile);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    refreshing.remove(source);
                    paintRefresh(refreshButtons.get(source), false);
                    if (feedback) {
                        String message = error.getMessage() != null && error.getMessage().startsWith("Сервер подписки ответил HTTP ")
                                ? error.getMessage() : "Не удалось скачать подписку. Проверь сеть или повтори позже.";
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void paintRefresh(View control, boolean pending) {
        if (control == null) return;
        control.setEnabled(!pending);
        control.setAlpha(pending ? .52f : 1f);
        if (pending && ValueAnimator.areAnimatorsEnabled()) {
            View image = ((FrameLayout) control).getChildAt(0);
            image.animate().rotationBy(360).setDuration(460).start();
        }
    }

    private void refreshNotification(Subscription profile) {
        if (profile.flag("notification-subs-expire", false)) requestNotificationPermission();
        RefreshJob.expiry(this, store, profile);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !store.preferences.getBoolean("notifications_asked", false)) {
            store.preferences.edit().putBoolean("notifications_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private void addResult(String message) {
        if (!atHome()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        else notice = message;
        render();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        if (request == VPN_PERMISSION) {
            startVpn(permissionProfile, permissionNode);
            return;
        }
        if (data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT_SETTINGS || request == IMPORT_SETTINGS) {
            try {
                if (request == EXPORT_SETTINGS) {
                    try (var output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException("Файл недоступен");
                        output.write(SettingsBackup.exportFrom(store.preferences).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    addResult("Настройки сохранены");
                } else {
                    try (var input = getContentResolver().openInputStream(uri)) {
                        SettingsBackup.restoreTo(Subscription.read(input), store.preferences);
                    }
                    addResult("Настройки применены");
                }
            } catch (Exception error) {
                addResult("Не удалось " + (request == EXPORT_SETTINGS ? "сохранить" : "загрузить") + " настройки");
            }
        } else if (request == 51) {
            try (var output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException();
                output.write(theme.values.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                addResult("Тема сохранена");
            } catch (Exception error) { addResult("Не удалось экспортировать тему"); }
        } else if (request == IMPORT_THEME || request == IMPORT_SUBSCRIPTION) {
            try (var source = getContentResolver().openInputStream(uri)) {
                String json = Subscription.read(source);
                if (request == IMPORT_THEME) {
                    Palette imported = Palette.fromJson(json);
                    store.preferences.edit().putString("theme", "custom").putString("theme_json", imported.values.toString()).apply();
                    addResult("Тема применена");
                } else {
                    Subscription profile = Subscription.parse(json, "", UUID.randomUUID().toString(), new HashMap<>());
                    store.save(profile);
                    choose(profile, 0);
                    returnTo((Screen) navigation.entry(0));
                    refreshNotification(profile);
                    addResult("Подписка импортирована");
                }
            } catch (Exception error) {
                addResult(request == IMPORT_THEME ? "Не удалось прочитать JSON темы" : "Не удалось импортировать подписку");
            }
        }
    }

    private void requestVpn(Subscription profile) {
        if (profile.nodes.isEmpty()) { addResult("В подписке нет доступных узлов"); return; }
        if (profile.expired()) { renewal(profile); return; }
        if (profile.quotaUsed()) { addResult("Лимит трафика исчерпан"); return; }
        permissionProfile = profile.id;
        permissionNode = selectedIndex(profile);
        Intent permission = VpnService.prepare(this);
        if (permission == null) startVpn(permissionProfile, permissionNode);
        else startActivityForResult(permission, VPN_PERMISSION);
    }

    private void startVpn(String profile, int node) {
        if (profile == null) return;
        notice = "";
        startForegroundService(new Intent(this, TunnelService.class).setAction(TunnelService.CONNECT)
                .putExtra("profile", profile).putExtra("node", node));
        requestNotificationPermission();
    }

    private void stopVpn() {
        startService(new Intent(this, TunnelService.class).setAction(TunnelService.DISCONNECT));
    }

    private void ping(Subscription profile, boolean autoConnect) {
        List<Node> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Node node : profile.nodes) if (!probing.contains(node.link) && seen.add(node.link)) nodes.add(node);
        ping(profile, nodes, autoConnect, true);
    }

    private void ping(Subscription profile, List<Node> nodes, boolean autoConnect, boolean summary) {
        if (nodes.isEmpty()) return;
        String method = PingProbe.method(store, profile);
        String url = PingProbe.url(store, profile);
        int timeout = config.pingTimeout(method);
        Set<String> pending = new HashSet<>();
        for (Node node : nodes) {
            pending.add(node.link);
            probing.add(node.link);
            updateBadges(node.link);
            pingWorkers.execute(() -> {
                long elapsed = PingProbe.measure(store, node, method, url, timeout);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    probing.remove(node.link);
                    pending.remove(node.link);
                    latency.put(node.link, elapsed);
                    updateBadges(node.link);
                    if (!pending.isEmpty()) return;
                    List<Node> reachable = new ArrayList<>();
                    for (Node candidate : nodes) if (latency.getOrDefault(candidate.link, -1L) >= 0) reachable.add(candidate);
                    if (summary) Toast.makeText(this, "Ответили " + reachable.size() + " из " + nodes.size(), Toast.LENGTH_SHORT).show();
                    Subscription current = selected();
                    if (autoConnect && !reachable.isEmpty() && current != null && current.id.equals(profile.id)
                            && TunnelService.status.equals("idle")) {
                        reachable.sort(Comparator.comparingLong(item -> latency.get(item.link)));
                        choose(profile, profile.nodes.indexOf(reachable.get(0)));
                        requestVpn(profile);
                    }
                    if (!atHome()) revision++;
                    if (atHome() && profileCards.containsKey(profile.id) && config.sort(profile).equals("ping")) {
                        Subscription latest = profiles().stream().filter(item -> item.id.equals(profile.id)).findFirst().orElse(null);
                        if (latest != null) drawProfile(latest, selected());
                    }
                });
            });
        }
    }

    private void updateBadges(String key) {
        List<Badge> current = badges.get(key);
        if (current != null) for (Badge badge : current) updateBadge(key, badge);
    }

    private void updateBadge(String key, Badge badge) {
        Long ms = latency.get(key);
        boolean good = ms != null && ms >= 0;
        boolean pending = probing.contains(key);
        String value = ms == null ? "—" : good ? ms + " мс" : "Нет ответа";
        if (good) {
            String bars = ms < 300 ? "▂▄▆" : ms < 1000 ? "▂▄" : "▂";
            value = switch (badge.display()) {
                case "icon" -> bars;
                case "both" -> bars + "  " + value;
                case "dot" -> "●";
                default -> value;
            };
        }
        badge.view().setText(value);
        badge.view().setTextColor(good && !pending ? accent() : quiet());
        badge.view().setContentDescription(pending ? "Проверяем задержку" : good ? ms + " миллисекунд" : null);
        badge.view().measuring(pending);
    }

    private boolean addFlag(LinearLayout row, String name, int margin) {
        Drawable flag = Art.flag(this, name);
        if (flag == null) return false;
        ImageView view = new ImageView(this);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setBackground(shape(tone("subsHeaderColor", "#402131"), 3));
        view.setClipToOutline(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageDrawable(flag);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(dp(20), dp(15));
        bounds.leftMargin = dp(margin);
        bounds.rightMargin = dp(margin <= 2 ? 7 : 0);
        row.addView(view, bounds);
        return true;
    }

    private void animateLayout(View parent) {
        if (parent instanceof android.view.ViewGroup group && ValueAnimator.areAnimatorsEnabled()) {
            TransitionManager.beginDelayedTransition(group, new AutoTransition().setDuration(165));
        }
    }

    private void actions(View anchor, Subscription profile) {
        String title = profile.text("profile-title", "InvokeVPN");
        LinearLayout content = menuContent(title);
        if (!profile.source.isEmpty()) menuRow(content, "Обновить подписку", R.drawable.ic_refresh_cw, () -> refresh(profile.source, profile, true));
        menuRow(content, "Проверить задержку", R.drawable.ic_gauge, () -> ping(profile, false));
        optionRow(content, "Информация о подписке", R.drawable.ic_info, ink(), () -> info(profile));
        if (!profile.link("profile-web-page-url").isEmpty()) {
            menuRow(content, "Личный кабинет", R.drawable.ic_link, () -> open(profile.link("profile-web-page-url")));
        }
        if (profile.flag("hide-servers", false) && !profile.nodes.isEmpty()) {
            menuRow(content, "Подключить первый узел", R.drawable.ic_power, () -> { choose(profile, 0); requestVpn(profile); });
        }
        if (!profile.nodes.isEmpty() && mayCopy(profile)) {
            menuRow(content, "Скопировать ссылку узла", R.drawable.ic_copy, () -> copyNode(profile.nodes.get(selectedIndex(profile))));
        }
        optionRow(content, "Удалить подписку", R.drawable.ic_trash_2, tintDanger(), () -> confirmRemoval(profile));
        showGlass(anchor, content, title, false);
    }

    private void nodeActions(View anchor, Subscription profile, Node node, int index) {
        String title = Art.withoutFlag(node.title());
        LinearLayout content = menuContent(title);
        menuRow(content, "Обновить пинг", R.drawable.ic_gauge, () -> {
            if (!probing.contains(node.link)) ping(profile, java.util.Collections.singletonList(node), false, false);
        });
        menuRow(content, "Подключиться", R.drawable.ic_power, () -> {
            choose(profile, index);
            render();
            requestVpn(profile);
        });
        if (mayCopy(profile)) menuRow(content, "Скопировать ссылку узла", R.drawable.ic_copy, () -> copyNode(node));
        showGlass(anchor, content, title, false);
    }

    private boolean mayCopy(Subscription profile) {
        return !profile.flag("hide", false) && !profile.flag("hide-servers", false) && !profile.flag("hide-settings", false);
    }

    private void copyNode(Node node) {
        var clip = android.content.ClipData.newPlainText("InvokeVPN", node.link);
        var sensitive = new android.os.PersistableBundle();
        sensitive.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(sensitive);
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(this, "Ссылка узла скопирована", Toast.LENGTH_SHORT).show();
    }

    private void confirmRemoval(Subscription profile) {
        String title = "Удалить подписку?";
        LinearLayout content = menuContent(title);
        menuNote(content, "Профиль и его узлы будут удалены с этого устройства.");
        menuRow(content, "Удалить", R.drawable.ic_trash_2, tintDanger(), () -> {
            if (profile.id.equals(TunnelService.connectedProfile)) stopVpn();
            store.remove(profile.id);
            render();
        });
        optionRow(content, "Отмена", 0, ink(), () -> overlays.back());
        showGlass(null, content, title, false);
    }

    private void info(Subscription profile) {
        String title = profile.text("profile-title", "InvokeVPN");
        LinearLayout content = menuContent(title);
        String message = expiryText(profile) + "\nДоступных узлов: " + profile.nodes.size();
        if (!profile.source.isEmpty()) message += config.refreshOnSchedule(profile)
                ? "\nАвтообновление - " + config.refreshHours(profile) + "ч" : "\nАвтообновление выключено";
        menuNote(content, message);
        if (!profile.link("profile-web-page-url").isEmpty()) {
            menuRow(content, "Личный кабинет", R.drawable.ic_link, () -> open(profile.link("profile-web-page-url")));
        }
        optionRow(content, "Готово", R.drawable.ic_check, ink(), () -> overlays.back());
        showGlass(null, content, title, false);
    }

    private void showSetting(View anchor, LinearLayout content) {
        showSetting(anchor, content, false);
    }

    private void showSetting(View anchor, LinearLayout content, boolean form) {
        content.setPadding(dp(6), dp(8), dp(6), dp(8));
        overlays.showBelow(anchor, content, (String) anchor.getTag(), tone("subsHeaderColor", "#402131"), form);
    }

    private void showGlass(View anchor, LinearLayout content, String title, boolean form) {
        overlays.show(anchor, content, title, tone("subsHeaderColor", "#402131"), form);
    }

    private LinearLayout menuContent(String title) {
        LinearLayout content = column();
        content.setPadding(dp(6), dp(9), dp(6), dp(7));
        TextView heading = label(title, 13, quiet(), true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setPadding(dp(12), dp(4), dp(12), dp(7));
        if (overlays.isShowing()) {
            LinearLayout header = row();
            header.addView(icon(R.drawable.ic_arrow_left, "Назад", topColor(), 44, () -> overlays.back()));
            header.addView(heading, flex());
            content.addView(header);
        } else {
            content.addView(heading);
        }
        return content;
    }

    private void menuNote(LinearLayout content, String message) {
        TextView note = label(message, 14, ink(), false);
        note.setPadding(dp(12), dp(5), dp(12), dp(9));
        content.addView(note);
    }

    private void menuRow(LinearLayout parent, String caption, int resource, Runnable task) {
        menuRow(parent, caption, resource, ink(), task);
    }

    private void menuRow(LinearLayout parent, String caption, int resource, int color, Runnable task) {
        optionRow(parent, caption, resource, color, () -> overlays.hide(task));
    }

    private void settingOption(LinearLayout parent, String caption, int resource, Runnable task) {
        optionRow(parent, caption, resource, resource == R.drawable.ic_check ? accent() : ink(), () -> overlays.hide(task));
    }

    private void optionRow(LinearLayout parent, String caption, int resource, int color, Runnable task) {
        LinearLayout item = row();
        item.setMinimumHeight(dp(46));
        item.setPadding(dp(12), dp(5), dp(10), dp(5));
        item.setBackground(interactive(Color.TRANSPARENT, 15));
        animatePress(item, .97f);
        item.addView(label(caption, 14, color, true), flex());
        if (resource != 0) item.addView(glyph(resource, color, 2), new LinearLayout.LayoutParams(dp(22), dp(22)));
        item.setOnClickListener(view -> task.run());
        parent.addView(item, new LinearLayout.LayoutParams(-1, -2));
    }

    private int tintDanger() { return Palette.blend(ink(), 0xffff4d65, .57f); }

    private void chooseOption(View anchor, String[] choices, int checked, java.util.function.IntConsumer selection) {
        LinearLayout content = column();
        for (int i = 0; i < choices.length; i++) {
            int choice = i;
            settingOption(content, choices[i], i == checked ? R.drawable.ic_check : 0, () -> selection.accept(choice));
        }
        showSetting(anchor, content);
    }

    private String expiryText(Subscription profile) {
        long expiry = profile.userinfo("expire");
        if (!profile.hasUserinfo()) return "Срок —";
        if (expiry == 0) return "Бессрочно";
        if (expiry > Long.MAX_VALUE / 1000) return "Срок —";
        return "До " + new SimpleDateFormat("dd.MM.yyyy", russian).format(new Date(expiry * 1000));
    }

    private void renewal(Subscription profile) {
        String url = profile.link("sub-expire-button-link");
        if (url.isEmpty()) url = profile.link("profile-web-page-url");
        open(url.isEmpty() ? Subscription.HOME + "cabinet/" : url);
    }

    private void open(String url) {
        try {
            Subscription.https(url);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) { addResult("Не удалось открыть ссылку"); }
    }

    @Override public void onBackPressed() {
        if (overlays.back()) return;
        if (!atHome()) backSettings();
        else super.onBackPressed();
    }

    private void updateSystemBack() {
        if (systemBack != null) systemBack.setEnabled(overlays.isShowing() || navigation.depth() > 1);
    }

    // Keep API 33/34 callback types out of the class loaded on Android 8.
    private static final class SystemBack {
        private final MainActivity activity;
        private final android.window.OnBackInvokedCallback callback;
        private boolean enabled;

        SystemBack(MainActivity activity) {
            this.activity = activity;
            callback = Build.VERSION.SDK_INT >= 34 ? new GestureBack(activity) : activity::onBackPressed;
        }

        void setEnabled(boolean value) {
            if (enabled == value) return;
            enabled = value;
            var dispatcher = activity.getOnBackInvokedDispatcher();
            if (value) dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            else dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }

    private static final class GestureBack implements android.window.OnBackAnimationCallback {
        private final MainActivity activity;
        GestureBack(MainActivity activity) { this.activity = activity; }

        @Override public void onBackStarted(android.window.BackEvent event) {
            PageNavigation stack = activity.overlays.isShowing() ? activity.overlays.navigation() : activity.navigation;
            if (stack == null || stack.previous() == null) return;
            if (stack == activity.navigation) activity.paintScreen((Screen) stack.previous());
            activity.gestureNavigation = stack;
            stack.beginBack();
        }

        @Override public void onBackProgressed(android.window.BackEvent event) {
            if (activity.gestureNavigation != null) activity.gestureNavigation.seekBack(event.getProgress());
        }

        @Override public void onBackCancelled() {
            if (activity.gestureNavigation != null) activity.gestureNavigation.cancelBack();
            activity.gestureNavigation = null;
        }

        @Override public void onBackInvoked() {
            activity.gestureNavigation = null;
            activity.onBackPressed();
        }
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams flex() { return new LinearLayout.LayoutParams(0, -2, 1); }

    private TextView label(String value, float sp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp + (store.preferences.getBoolean("large_type", false) ? 2 : 0));
        text.setTextColor(color);
        text.setIncludeFontPadding(true);
        text.setTypeface(bold ? semibold : regular);
        return text;
    }

    private TextView action(String value, int color, int size, Runnable click) {
        TextView text = label(value, size, color, true);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMinHeight(dp(44));
        text.setPadding(dp(5), 0, dp(5), 0);
        text.setOnClickListener(view -> click.run());
        return text;
    }

    private View icon(int resource, String accessibility, int color, int size, Runnable action) {
        return anchoredIcon(resource, accessibility, color, size, view -> action.run());
    }

    private View anchoredIcon(int resource, String accessibility, int color, int size, View.OnClickListener action) {
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        wrapper.setContentDescription(accessibility);
        wrapper.setFocusable(true);
        wrapper.setBackground(interactive(Color.TRANSPARENT, 40));
        animatePress(wrapper, .90f);
        ImageView glyph = glyph(resource, color, 0);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        wrapper.addView(glyph, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        wrapper.setOnClickListener(action);
        return wrapper;
    }

    private ImageView glyph(int resource, int color, int paddingDp) {
        ImageView image = new ImageView(this);
        image.setImageResource(resource);
        image.setImageTintList(ColorStateList.valueOf(color));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = dp(paddingDp);
        image.setPadding(padding, padding, padding, padding);
        return image;
    }

    private void animatePress(View target, float pressed) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        StateListAnimator motion = new StateListAnimator();
        motion.addState(new int[]{android.R.attr.state_pressed}, scale(target, pressed, 95));
        motion.addState(new int[0], scale(target, 1f, 180));
        target.setStateListAnimator(motion);
    }

    private ObjectAnimator scale(View target, float size, int duration) {
        return ObjectAnimator.ofPropertyValuesHolder(target, PropertyValuesHolder.ofFloat(View.SCALE_X, size),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, size)).setDuration(duration);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable interactive(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x32ffffff), shape(color, radius), shape(Color.WHITE, radius));
    }

    private void addNotice(String value) {
        TextView notice = label(value, 13, quiet(), false);
        notice.setPadding(dp(5), dp(5), dp(5), dp(8));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(notice);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int tone(String key, String fallback) { return theme.color(key, fallback); }
    private int ink() { return tone("serverRowTitleTextColor", "#FFF5F8"); }
    private int quiet() { return tone("serverRowSubTitleTextColor", "#D1A5B4"); }
    private int accent() { return tone("buttonColor", "#FF92B6"); }
    private int topColor() { return tone("topBarButtonsColor", "#FFADC7"); }
}
