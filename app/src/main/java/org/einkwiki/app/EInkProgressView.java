package org.einkwiki.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** A determinate, non-animated progress indicator suitable for e-ink panels. */
public final class EInkProgressView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int progress;

    public EInkProgressView(Context context) {
        this(context, null);
    }

    public EInkProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public EInkProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint.setStyle(Paint.Style.FILL);
        setBackgroundColor(Color.WHITE);
    }

    public void setProgress(int value) {
        int clamped = Math.max(0, Math.min(100, value));
        if (clamped == progress) {
            return;
        }
        progress = clamped;
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        float inset = paint.getStrokeWidth() / 2f;
        canvas.drawRect(inset, inset, width - inset, height - inset, paint);

        if (progress > 0) {
            paint.setStyle(Paint.Style.FILL);
            float right = inset + (width - paint.getStrokeWidth()) * (progress / 100f);
            canvas.drawRect(inset, inset, right, height - inset, paint);
        }
    }
}
