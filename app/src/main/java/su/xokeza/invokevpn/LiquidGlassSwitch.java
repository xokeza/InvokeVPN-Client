package su.xokeza.invokevpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.CompoundButton;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

final class LiquidGlassSwitch extends CompoundButton {
    private static final FloatPropertyCompat<LiquidGlassSwitch> POSITION = new FloatPropertyCompat<>("thumb") {
        @Override public float getValue(LiquidGlassSwitch view) { return view.position; }
        @Override public void setValue(LiquidGlassSwitch view, float value) {
            view.position = value;
            view.invalidate();
        }
    };
    private static final FloatPropertyCompat<LiquidGlassSwitch> GLASS = new FloatPropertyCompat<>("glass") {
        @Override public float getValue(LiquidGlassSwitch view) { return view.expansion; }
        @Override public void setValue(LiquidGlassSwitch view, float value) {
            view.expansion = value;
            view.invalidate();
        }
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF(), thumb = new RectF();
    private final SpringAnimation motion = new SpringAnimation(this, POSITION)
            .setSpring(new SpringForce().setDampingRatio(.68f).setStiffness(380f));
    private final SpringAnimation glass = new SpringAnimation(this, GLASS)
            .setSpring(new SpringForce().setDampingRatio(.55f).setStiffness(450f));
    private final float density;
    private final int active, idle, touchSlop;
    private float position, expansion, downX, downY, startPosition;
    private boolean dragging;

    LiquidGlassSwitch(Context context, int active, int idle) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.active = active;
        this.idle = idle;
        setButtonDrawable((android.graphics.drawable.Drawable) null);
        setBackground(null);
        setDefaultFocusHighlightEnabled(true);
        setFocusable(true);
        setClickable(true);
    }

    @Override public void setChecked(boolean checked) {
        boolean changed = isChecked() != checked;
        super.setChecked(checked);
        if (motion == null) position = checked ? 1 : 0;
        else if (changed || !motion.isRunning()) settleThumb();
    }

    private boolean canAnimate() {
        return isLaidOut() && isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled();
    }

    private void settleThumb() {
        float goal = isChecked() ? 1f : 0f;
        if (canAnimate() && position != goal) motion.animateToFinalPosition(goal);
        else if (!canAnimate()) {
            motion.cancel();
            position = goal;
            invalidate();
        }
    }

    private void expandGlass(float goal) {
        if (canAnimate()) glass.animateToFinalPosition(goal);
        else {
            glass.cancel();
            expansion = goal;
            invalidate();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                expandGlass(1f);
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx = event.getX() - downX;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(event.getY() - downY)) {
                    dragging = true;
                    startPosition = bounded(position);
                    motion.cancel();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    float travel = Math.max(1f, track.width() * .42f - 5f * density);
                    position = bounded(startPosition + (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -dx : dx) / travel);
                    invalidate();
                    return true;
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                expandGlass(0f);
                if (dragging) {
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) setChecked(position >= .5f);
                    else settleThumb();
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.Switch.class.getName(); }

    @Override protected void onDetachedFromWindow() {
        motion.cancel();
        glass.cancel();
        position = isChecked() ? 1f : 0f;
        expansion = 0f;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = Math.min(getWidth(), 64 * density);
        float h = Math.min(getHeight(), 36 * density);
        float x = (getWidth() - w) / 2;
        float y = (getHeight() - h) / 2;
        float progress = bounded(position);
        float lens = bounded(expansion);
        track.set(x + .5f * density, y + .5f * density, x + w - .5f * density, y + h - .5f * density);
        float radius = h / 2;
        int tint = Palette.blend(idle, active, progress);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, track.top, 0, track.bottom,
                Palette.blend(tint, Color.WHITE, .14f), Palette.blend(tint, Color.BLACK, .18f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(track, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(Palette.blend(tint, Color.WHITE, .24f));
        canvas.drawRoundRect(track, radius, radius, paint);

        float inset = 3f * density;
        float baseWidth = w * .58f;
        float spreadX = 8f * density * lens;
        float height = h - 2 * inset + 7f * density * lens;
        float offset = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1 - progress : progress;
        float left = x + inset + (w - 2 * inset - baseWidth) * offset - spreadX / 2;
        float top = y + (h - height) / 2;
        thumb.set(left, top, left + baseWidth + spreadX, top + height);
        float thumbRadius = height / 2;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x38000000);
        canvas.save();
        canvas.translate(0, 1.4f * density);
        canvas.drawRoundRect(thumb, thumbRadius, thumbRadius, paint);
        canvas.restore();
        int light = Palette.blend(0xfff8f6fb, Color.WHITE, progress);
        int shade = Palette.blend(0xffc9c3d0, Palette.blend(0xffede7f1, active, .16f), progress);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, thumb.top, 0, thumb.bottom,
                withOpacity(light, 1f - lens * .70f), withOpacity(shade, 1f - lens * .76f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(thumb, thumbRadius, thumbRadius, paint);
        if (lens > .01f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * density);
            paint.setShader(new LinearGradient(0, thumb.top, 0, thumb.bottom,
                    new int[]{withOpacity(Color.WHITE, .90f * lens), withOpacity(Color.WHITE, .12f * lens),
                            withOpacity(Palette.blend(tint, Color.WHITE, .58f), .72f * lens)},
                    new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(thumb, thumbRadius, thumbRadius, paint);
        }
        paint.setShader(null);
    }

    private static int withOpacity(int color, float opacity) { return (color & 0xffffff) | (Math.round(255 * opacity) << 24); }

    private static float bounded(float value) { return Math.max(0f, Math.min(1f, value)); }
}
