package su.xokeza.invokevpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.widget.TextView;

final class PingIndicator extends TextView {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private boolean measuring;

    PingIndicator(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(getResources().getDisplayMetrics().density * 1.4f);
    }

    void measuring(boolean pending) {
        measuring = pending;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!measuring) {
            super.onDraw(canvas);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float phase = ValueAnimator.areAnimatorsEnabled() ? SystemClock.uptimeMillis() % 720 * .00872665f : 0f;
        float x = getWidth() - getPaddingRight() - 23 * density;
        float mid = getHeight() / 2f;
        stroke.setColor(getCurrentTextColor());
        wave.reset();
        for (int dp = 0; dp <= 21; dp++) {
            float y = mid + (float) Math.sin(dp * .42 - phase) * 2.4f * density;
            if (dp == 0) wave.moveTo(x, y);
            else wave.lineTo(x + dp * density, y);
        }
        canvas.drawPath(wave, stroke);
        if (ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation();
    }
}
