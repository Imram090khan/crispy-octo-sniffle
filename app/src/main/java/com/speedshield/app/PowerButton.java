package com.speedshield.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** The big round on/off button, with a soft pulse while protection is on. */
class PowerButton extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private boolean on;
    private boolean busy;

    PowerButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Turn protection on or off");
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
    }

    void setState(boolean on, boolean busy) {
        if (this.on == on && this.busy == busy) return;
        this.on = on;
        this.busy = busy;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        invalidate();
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        setScaleX(pressed ? 0.95f : 1f);
        setScaleY(pressed ? 0.95f : 1f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(cx, cy);
        float radius = max * 0.66f;
        int main = on ? 0xFF22E58B : 0xFF5B6488;
        int second = on ? 0xFF14B8D4 : 0xFF2E3659;

        // Two rings that drift outwards and fade.
        ring.setStrokeWidth(max * 0.025f);
        for (int i = 0; i < 2; i++) {
            float t = on ? (SystemClock.uptimeMillis() % 2400 / 2400f + i * 0.5f) % 1f : 0.25f + i * 0.4f;
            ring.setColor(main);
            ring.setAlpha((int) (110 * (1 - t)));
            canvas.drawCircle(cx, cy, radius + (max - radius) * t, ring);
        }

        fill.setShader(new LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                main, second, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, fill);

        icon.setStrokeWidth(radius * 0.11f);
        float r = radius * 0.36f;
        arc.set(cx - r, cy - r, cx + r, cy + r);
        if (busy) {
            canvas.drawArc(arc, SystemClock.uptimeMillis() % 1000 * 0.36f, 270, false, icon);
        } else {
            canvas.drawArc(arc, -55, 290, false, icon);
            canvas.drawLine(cx, cy - r * 1.25f, cx, cy - r * 0.2f, icon);
        }

        if (on || busy) postInvalidateDelayed(32);
    }
}
