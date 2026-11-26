package su.xokeza.invokevpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;

/** A retained stack of ordinary views. The same 0..1 coordinate drives push, pop and gesture scrubbing. */
final class PageNavigation extends FrameLayout {
    private final ArrayList<View> pages = new ArrayList<>();
    private final Paint dim = new Paint();
    private final SpringAnimation spring;
    private View rear, front;
    private float progress, gestureStart;
    private boolean gesturing;
    private final int pageHeight;
    private final Runnable settled;

    PageNavigation(Context context, int pageHeight, Runnable settled) {
        super(context);
        this.pageHeight = pageHeight;
        this.settled = settled;
        spring = new SpringAnimation(new FloatValueHolder(0f));
        spring.setSpring(springForce(1f));
        spring.setMinimumVisibleChange(.0005f);
        spring.addUpdateListener((a, value, velocity) -> apply(value));
        spring.addEndListener((a, canceled, value, velocity) -> { if (!canceled) finish(); });
    }

    static SpringForce springForce(float target) {
        return new SpringForce(target).setDampingRatio(.88f).setStiffness(325f);
    }

    View current() { return pages.isEmpty() ? null : pages.get(pages.size() - 1); }
    View previous() { return pages.size() < 2 ? null : pages.get(pages.size() - 2); }
    int depth() { return pages.size(); }
    View entry(int index) { return pages.get(index); }

    void push(View next, boolean animate) {
        settleNow();
        View old = current();
        pages.add(next);
        addView(next, new LayoutParams(-1, pageHeight));
        if (old == null) return;
        prepare(old, next, 0f);
        settleTo(1f, animate);
    }

    void popTo(View target) {
        int index = pages.indexOf(target);
        if (index < 0 || target == current()) return;
        prepareBack(target);
        while (pages.size() > index + 1) {
            View removed = pages.remove(pages.size() - 1);
            if (removed != front) removeView(removed);
        }
        gesturing = false;
        settleTo(0f, true);
    }

    private void prepareBack(View target) {
        if (rear == target && front == current()) {
            spring.cancel();
        } else {
            settleNow();
            prepare(target, current(), 1f);
        }
    }

    void beginBack() {
        if (previous() == null || !ValueAnimator.areAnimatorsEnabled()) return;
        prepareBack(previous());
        gestureStart = progress;
        gesturing = true;
    }

    void seekBack(float amount) {
        if (gesturing) apply(gestureStart * (1f - Math.max(0f, Math.min(1f, amount))));
    }

    void cancelBack() {
        if (!gesturing) return;
        gesturing = false;
        settleTo(1f, true);
    }

    private void prepare(View under, View top, float start) {
        rear = under;
        front = top;
        rear.setVisibility(VISIBLE);
        rear.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        apply(start);
    }

    private void settleTo(float goal, boolean animate) {
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || getWidth() == 0 || progress == goal) {
            finish();
            return;
        }
        spring.setStartValue(progress);
        spring.setStartVelocity(0f);
        spring.animateToFinalPosition(goal);
    }

    private void apply(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        float remaining = 1f - progress;
        front.setTranslationX(getWidth() * remaining);
        front.setPivotX(0f);
        front.setScaleX(1f - .015f * remaining);
        front.setScaleY(1f - .015f * remaining);
        front.setAlpha(1f - .07f * remaining);
        rear.setTranslationX(-getWidth() * .28f * progress);
        dim.setAlpha(Math.round(255 * .25f * progress));
        invalidate();
    }

    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        if (child != rear) return super.drawChild(canvas, child, time);
        int saved = canvas.save();
        // The two pages can contain transparent text rows over one shared glass panel.
        canvas.clipRect(0f, 0f, front.getTranslationX(), getHeight());
        boolean more = super.drawChild(canvas, child, time);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), dim);
        canvas.restoreToCount(saved);
        return more;
    }

    private void finish() {
        if (front == null) return;
        View keep = current();
        for (View layer : new View[]{rear, front}) {
            layer.setTranslationX(0f);
            layer.setScaleX(1f);
            layer.setScaleY(1f);
            layer.setAlpha(1f);
            if (!pages.contains(layer)) removeView(layer);
            else {
                layer.setVisibility(layer == keep ? VISIBLE : GONE);
                layer.setImportantForAccessibility(layer == keep ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        }
        front = rear = null;
        gesturing = false;
        invalidate();
        if (settled != null) settled.run();
    }

    private void settleNow() {
        spring.cancel();
        finish();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (front != null) apply(progress);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return front != null || super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        return front != null || super.onTouchEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        settleNow();
        super.onDetachedFromWindow();
    }
}
