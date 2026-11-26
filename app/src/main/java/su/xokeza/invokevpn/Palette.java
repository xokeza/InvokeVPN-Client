package su.xokeza.invokevpn;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;

final class Palette {
    final JSONObject values;

    Palette(JSONObject values) { this.values = values; }

    static Palette load(Context context, Store store) {
        String selection = store.preferences.getString("theme", "rose");
        if (selection.equals("system")) {
            int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            selection = mode == Configuration.UI_MODE_NIGHT_YES ? "graphite" : "rose";
        }
        try {
            if (selection.equals("custom")) return new Palette(new JSONObject(store.preferences.getString("theme_json", "{}")));
            return builtIn(context, selection);
        } catch (Exception ignored) {
            return new Palette(new JSONObject());
        }
    }

    static Palette builtIn(Context context, String name) throws Exception {
        try (var source = context.getAssets().open("themes/" + name + ".json")) {
            return new Palette(new JSONObject(Subscription.read(source)));
        }
    }

    static Palette fromJson(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        if (!object.has("backgroundColors") || !object.has("buttonColor")) {
            throw new IllegalArgumentException("Укажи backgroundColors и buttonColor в JSON темы");
        }
        Palette theme = new Palette(object);
        JSONArray colors = object.getJSONArray("backgroundColors");
        if (colors.length() < 2 || colors.length() > 8) throw new IllegalArgumentException("Нужно от двух до восьми цветов фона");
        for (int i = 0; i < colors.length(); i++) theme.parse(colors.getString(i));
        theme.parse(object.getString("buttonColor"));
        return theme;
    }

    int color(String key, String fallback) {
        try { return parse(values.optString(key, fallback)); }
        catch (IllegalArgumentException ignored) { return parse(fallback); }
    }

    int[] background() {
        JSONArray palette = values.optJSONArray("backgroundColors");
        if (palette == null || palette.length() < 2 || palette.length() > 8) return new int[]{0xff13090e, 0xff28111b, 0xff40202e};
        int[] colors = new int[palette.length()];
        try {
            for (int i = 0; i < colors.length; i++) colors[i] = parse(palette.optString(i));
        } catch (IllegalArgumentException error) {
            return new int[]{0xff13090e, 0xff28111b, 0xff40202e};
        }
        float intensity = (float) Math.max(0, Math.min(1, values.optDouble("backgroundGradientColorIntensity", 1)));
        for (int i = 1; i < colors.length; i++) colors[i] = blend(colors[0], colors[i], intensity);
        return colors;
    }

    int[] powerBackdrop(int fallback) {
        JSONArray colors = values.optJSONArray("elipseColors");
        if (colors == null || colors.length() < 2 || colors.length() > 8) return new int[]{fallback, fallback};
        int[] result = new int[colors.length()];
        for (int i = 0; i < result.length; i++) {
            try { result[i] = blend(fallback, parse(colors.getString(i)), .34f); }
            catch (Exception error) { result[i] = fallback; }
        }
        return result;
    }

    static int blend(int a, int b, float mix) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * mix),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * mix),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * mix));
    }

    private int parse(String source) {
        if (source.matches("#[a-fA-F0-9]{8}")) {
            return Color.parseColor("#" + source.substring(7, 9) + source.substring(1, 7));
        }
        if (source.matches("#[a-fA-F0-9]{6}")) return Color.parseColor(source);
        throw new IllegalArgumentException("Цвет должен быть в формате #RRGGBB или #RRGGBBAA");
    }
}
