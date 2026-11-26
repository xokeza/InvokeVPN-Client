package su.xokeza.invokevpn;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import com.caverock.androidsvg.SVG;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Art {
    private Art() {}
    private static final Pattern FLAG = Pattern.compile("[\\x{1F1E6}-\\x{1F1FF}]{2}");
    private static final LruCache<String, Bitmap> flags = new LruCache<>(48);
    private static final Map<String, String> countryNames = new HashMap<>();

    static {
        Locale russian = Locale.forLanguageTag("ru");
        for (String code : Locale.getISOCountries()) {
            Locale country = new Locale("", code);
            countryNames.put(country.getDisplayCountry(russian).toLowerCase(Locale.ROOT), code);
            countryNames.put(country.getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT), code);
        }
        countryNames.put("сша", "US");
        countryNames.put("великобритания", "GB");
        countryNames.put("оаэ", "AE");
        countryNames.put("нидерланды", "NL");
    }

    static String withoutFlag(String label) { return FLAG.matcher(label).replaceAll("").trim(); }

    static Drawable flag(Context context, String label) {
        var match = FLAG.matcher(label);
        String code;
        if (match.find()) {
            String pair = match.group();
            code = "" + (char) ('A' + pair.codePointAt(0) - 0x1F1E6)
                    + (char) ('A' + pair.codePointAt(2) - 0x1F1E6);
        } else {
            String country = label.replaceFirst("\\s+#?\\d+\\s*$", "").trim().toLowerCase(Locale.ROOT);
            code = countryNames.get(country);
        }
        if (code == null) return null;
        String path = "icons/flags/" + code.toLowerCase(Locale.ROOT) + ".svg";
        Bitmap image = flags.get(path);
        if (image == null) {
            try (var stream = context.getAssets().open(path)) {
                SVG svg = SVG.getFromInputStream(stream);
                image = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888);
                svg.renderToCanvas(new Canvas(image), new RectF(0, 0, image.getWidth(), image.getHeight()));
                flags.put(path, image);
            } catch (Exception error) {
                return null;
            }
        }
        BitmapDrawable result = new BitmapDrawable(context.getResources(), image);
        result.setFilterBitmap(true);
        return result;
    }

    static Drawable background(Palette palette) {
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                double angle = Math.toRadians(palette.values.optDouble("backgroundGradientRotationAngle", 32));
                float w = b.width(), h = b.height();
                float vx = (float) Math.cos(angle), vy = (float) Math.sin(angle);
                float scale = (Math.abs(vx) * w + Math.abs(vy) * h) / 2;
                if (scale <= 0) return;
                paint.setShader(new LinearGradient(b.centerX() - vx * scale, b.centerY() - vy * scale,
                        b.centerX() + vx * scale, b.centerY() + vy * scale, palette.background(), null, Shader.TileMode.CLAMP));
                canvas.drawRect(b, paint);
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    static String bytes(long value) {
        String[] unit = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        double size = value;
        int index = 0;
        while (size >= 1024 && index < unit.length - 1) { size /= 1024; index++; }
        return String.format(Locale.forLanguageTag("ru"), size >= 10 || index == 0 || size % 1 == 0 ? "%.0f %s" : "%.1f %s", size, unit[index]);
    }
}
