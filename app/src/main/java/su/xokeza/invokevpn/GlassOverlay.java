package su.xokeza.invokevpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.example.liquidglass.LiquidGlassView;

final class GlassOverlay {
    private final FrameLayout root;
    private final View backdrop;
    private final Runnable visibilityChanged;
    private PageNavigation pages;
    private Runnable refit;
    private FrameLayout scrim;
    private View dimmer;
    private LiquidGlassView panel;
    private ShadowFrame surface;
    private SpringAnimation reveal;
    private Runnable afterDismiss;
    private boolean closing;

    GlassOverlay(FrameLayout root, View backdrop, Runnable visibilityChanged) {
        this.root = root;
        this.backdrop = backdrop;
        this.visibilityChanged = visibilityChanged;
    }

    void show(View anchor, View content, String title, int tint, boolean form) {
        show(anchor, content, title, tint, form, false);
    }

    void showBelow(View anchor, View content, String title, int tint, boolean form) {
        show(anchor, content, title, tint, form, true);
    }

    private void show(View anchor, View content, String title, int tint, boolean form, boolean alignBelow) {
        Context context = root.getContext();
        ScrollView scroller = new ScrollView(context);
        scroller.setTag(title);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(content);
        if (anchor == null && pages != null && !closing) {
            pages.push(scroller, true);
            if (refit != null) refit.run();
            if (Build.VERSION.SDK_INT >= 28) surface.setAccessibilityPaneTitle(title);
            return;
        }
        remove();
        scrim = new FrameLayout(context);
        dimmer = new View(context);
        dimmer.setBackgroundColor(form ? 0x66000000 : 0x28000000);
        dimmer.setAlpha(ValueAnimator.areAnimatorsEnabled() ? 0f : 1f);
        scrim.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));
        scrim.setOnClickListener(view -> hide(null));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        panel = new LiquidGlassView(context);
        int radius = dp(24);
        panel.setCornerRadius(radius);
        panel.setGlassTint(tint, form ? .27f : .22f);
        panel.enableMenuOptics();
        panel.setBackdropSource(backdrop);
        surface = new ShadowFrame(context, panel, radius);
        if (Build.VERSION.SDK_INT >= 28) surface.setAccessibilityPaneTitle(title);
        pages = new PageNavigation(context, -2, () -> {
            if (refit != null && !closing) refit.run();
        });
        pages.push(scroller, false);
        surface.viewport.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        panel.moveMenuLightTo(surface.viewport);
        visibilityChanged.run();
        FrameLayout overlay = scrim;
        LiquidGlassView opening = panel;
        refit = () -> {
            if (panel == opening && overlay.getWidth() > 0) fit(overlay, opening, anchor, form, alignBelow);
        };
        overlay.post(() -> {
            if (panel != opening) return;
            fit(overlay, opening, anchor, form, alignBelow);
            overlay.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if (panel == opening && !closing && (r-l != oldR-oldL || b-t != oldB-oldT)) {
                    fit(overlay, opening, anchor, form, alignBelow);
                }
            });
            if (ValueAnimator.areAnimatorsEnabled()) {
                animateOpening(surface);
            }
            surface.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        });
    }

    private void fit(FrameLayout overlay, LiquidGlassView opening, View anchor, boolean form, boolean alignBelow) {
        int pad = dp(12);
        int availableWidth = Math.max(1, overlay.getWidth() - pad * 2);
        int width = Math.min(availableWidth, dp(form ? 410 : 294));
        int availableHeight = Math.max(1, overlay.getHeight() - pad * 2);
        if (alignBelow) width = Math.min(width, Math.max(1, anchor.getWidth() - dp(24)));
        int height = measureHeight(pages, width, availableHeight);
        int left = (overlay.getWidth() - width) / 2;
        int top = form ? Math.min(dp(92), Math.max(pad, (overlay.getHeight() - height) / 3))
                : (overlay.getHeight() - height) / 2;
        float pivotX = width / 2f, pivotY = 0f;
        if (anchor != null) {
            int[] a = new int[2], o = new int[2];
            anchor.getLocationInWindow(a);
            overlay.getLocationInWindow(o);
            int anchorLeft = a[0] - o[0];
            int anchorTop = a[1] - o[1];
            int end = anchorLeft + anchor.getWidth();
            int gap = dp(4);
            left = Math.max(pad, Math.min(alignBelow ? anchorLeft + dp(14) : end - width,
                    overlay.getWidth() - width - pad));
            top = Math.max(pad, anchorTop + anchor.getHeight() + gap);
            int below = overlay.getHeight() - pad - top;
            if (alignBelow && below >= Math.min(height, dp(form ? 156 : 112))) {
                height = measureHeight(pages, width, below);
            } else if (height > below) {
                if (alignBelow) {
                    int above = anchorTop - pad - gap;
                    if (above > below && above > 0) height = measureHeight(pages, width, Math.min(above, availableHeight));
                }
                top = Math.max(pad, anchorTop - height - gap);
                pivotY = height;
            }
            pivotX = alignBelow ? dp(18) : Math.max(0, Math.min(width, end - left - anchor.getWidth() / 2f));
        }
        int inset = surface.inset;
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(width + 2 * inset, height + 2 * inset,
                Gravity.TOP | Gravity.LEFT);
        bounds.leftMargin = left - inset;
        bounds.topMargin = Math.max(pad, top) - inset;
        surface.originX = pivotX;
        surface.originY = pivotY;
        if (surface.getParent() == null) overlay.addView(surface, bounds);
        else {
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if (old.leftMargin == bounds.leftMargin && old.topMargin == bounds.topMargin
                    && old.width == bounds.width && old.height == bounds.height) return;
            surface.setLayoutParams(bounds);
            // A move (including an IME resize) changes the source region of the lens.
            opening.invalidate();
        }
    }

    private final class ShadowFrame extends FrameLayout {
        final int inset = dp(30);
        private final LiquidGlassView glass;
        private final int radius;
        private final View shadow;
        private final FrameLayout viewport;
        private float originX, originY, revealScale = 1f;

        ShadowFrame(Context context, LiquidGlassView glass, int radius) {
            super(context);
            this.glass = glass;
            this.radius = radius;
            shadow = new View(context) {
                private final Path silhouette = new Path();
                private final Paint penumbra = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                    silhouette.rewind();
                    silhouette.addRoundRect(inset, inset, w - inset, h - inset, radius, radius, Path.Direction.CW);
                }

                @Override protected void onDraw(Canvas canvas) {
                    int saved = canvas.save();
                    canvas.clipOutPath(silhouette);
                    canvas.translate(0, dp(4));
                    for (int ring = 12; ring > 0; ring--) {
                        float spread = dp(2) * ring;
                        penumbra.setColor(((15 - ring) / 2) << 24);
                        canvas.drawRoundRect(inset - spread, inset - spread, getWidth() - inset + spread,
                                getHeight() - inset + spread, radius + spread, radius + spread, penumbra);
                    }
                    canvas.restoreToCount(saved);
                }
            };
            addView(shadow, new LayoutParams(-1, -1));
            viewport = new FrameLayout(context);
            viewport.setClickable(true);
            viewport.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    float left = originX * (1f - revealScale);
                    float top = originY * (1f - revealScale);
                    outline.setRoundRect(Math.round(left), Math.round(top),
                            Math.round(left + view.getWidth() * revealScale),
                            Math.round(top + view.getHeight() * revealScale), radius * revealScale);
                }
            });
            viewport.setClipToOutline(true);
            LayoutParams bounds = new LayoutParams(-1, -1);
            bounds.setMargins(inset, inset, inset, inset);
            addView(viewport, bounds);
            viewport.addView(glass, new LayoutParams(-1, -1));
            if (Build.VERSION.SDK_INT >= 31) glass.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        void reveal(float progress) {
            revealScale = .78f + .22f * progress;
            viewport.invalidateOutline();
            pages.setPivotX(originX);
            pages.setPivotY(originY);
            pages.setScaleX(revealScale);
            pages.setScaleY(revealScale);
            glass.transformMenuLight(revealScale, originX, originY);
            shadow.setPivotX(originX + inset);
            shadow.setPivotY(originY + inset);
            shadow.setScaleX(revealScale);
            shadow.setScaleY(revealScale);
            setAlpha(progress);
            dimmer.setAlpha(progress);
        }

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            return closing || super.onInterceptTouchEvent(event);
        }
    }

    private int measureHeight(View panel, int width, int height) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        return Math.min(height, panel.getMeasuredHeight());
    }

    private void animateOpening(ShadowFrame frame) {
        frame.reveal(0f);
        reveal = new SpringAnimation(new FloatValueHolder(0f));
        reveal.setSpring(PageNavigation.springForce(1f));
        reveal.setMinimumVisibleChange(.001f);
        reveal.addUpdateListener((animation, value, velocity) -> frame.reveal(Math.max(0f, Math.min(1f, value))));
        reveal.addEndListener((animation, canceled, value, velocity) -> {
            if (canceled || frame != surface) return;
            if (closing) finishDismissal();
        });
        reveal.start();
    }

    boolean isShowing() { return scrim != null; }
    PageNavigation navigation() { return pages; }

    boolean back() {
        if (pages != null && !closing && pages.previous() != null) {
            clearPanelFocus();
            View target = pages.previous();
            pages.popTo(target);
            if (Build.VERSION.SDK_INT >= 28) surface.setAccessibilityPaneTitle((String) target.getTag());
            return true;
        }
        return hide(null);
    }

    boolean hide(Runnable next) {
        if (scrim == null) return false;
        if (closing) return true;
        closing = true;
        clearPanelFocus();
        afterDismiss = next;
        if (!ValueAnimator.areAnimatorsEnabled() || reveal == null || surface.getParent() == null) finishDismissal();
        else {
            // A completed FloatValueHolder spring needs its resting value when started again.
            if (!reveal.isRunning()) reveal.setStartValue(surface.getAlpha());
            reveal.animateToFinalPosition(0f);
        }
        return true;
    }

    private void finishDismissal() {
        Runnable next = afterDismiss;
        remove();
        if (next != null) next.run();
    }

    private void clearPanelFocus() {
        View focus = surface.findFocus();
        if (focus != null) {
            root.getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private void remove() {
        if (reveal != null) reveal.cancel();
        reveal = null;
        afterDismiss = null;
        refit = null;
        pages = null;
        if (scrim != null) root.removeView(scrim);
        scrim = null;
        panel = null;
        dimmer = null;
        surface = null;
        closing = false;
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        visibilityChanged.run();
    }

    private int dp(float value) { return Math.round(value * root.getResources().getDisplayMetrics().density); }
}
