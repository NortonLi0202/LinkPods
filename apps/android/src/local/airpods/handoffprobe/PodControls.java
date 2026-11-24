package local.airpods.handoffprobe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;

/** Native, dependency-free controls. State binding never calls a change listener. */
final class PodControls {
    interface Change { void changed(int value); }

    private static int blendColor(int from, int to, float ratio) {
        float t = Math.max(0f, Math.min(1f, ratio));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    abstract static class Control extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final boolean dark;
        final int ink, mutedInk, track, thumb, thumbBorder, accent, switchOn, shadowColor;
        final float density;
        float position, pressScale = 1f, downX, downY;
        boolean dragging;
        ValueAnimator animator;
        Change listener;

        Control(Context context, boolean dark) {
            super(context);
            this.dark = dark;
            density = getResources().getDisplayMetrics().density;
            ink = dark ? 0xFFF5F5F7 : 0xFF141418;
            mutedInk = dark ? 0xFF9A9AA6 : 0xFF6B6B76;
            // Light track is a touch deeper so the off switch stays visible on white cards.
            track = dark ? 0xFF2A2A31 : 0xFFE3E5EA;
            thumb = dark ? 0xFF3A3A44 : Color.WHITE;
            thumbBorder = dark ? 0x30FFFFFF : 0x16000000;
            accent = dark ? 0xFF3B9EFF : 0xFF0381FE;
            switchOn = dark ? 0xFF3B9EFF : 0xFF0381FE;
            shadowColor = dark ? 0x44000000 : 0x22000000;
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        float dp(float n) { return n * density; }

        void pill(Canvas c, float l, float t, float r, float b, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            rect.set(l, t, r, b);
            float radius = Math.min(r - l, b - t) / 2f;
            c.drawRoundRect(rect, radius, radius, paint);
        }

        void thumbSurface(Canvas c, float l, float t, float r, float b, int fillColor, int strokeColor, float strokeDp) {
            float w = (r - l) * pressScale;
            float h = (b - t) * pressScale;
            float cx = (l + r) / 2f;
            float cy = (t + b) / 2f;
            paint.setShadowLayer(dp(dragging ? 5f : 3f), 0, dp(1.5f), shadowColor);
            pill(c, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, fillColor);
            paint.clearShadowLayer();
            if (strokeColor != 0) {
                paint.setColor(strokeColor);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(strokeDp));
                float inset = dp(strokeDp) / 2f;
                rect.set(cx - w / 2f + inset, cy - h / 2f + inset, cx + w / 2f - inset, cy + h / 2f - inset);
                float radius = Math.min(rect.width(), rect.height()) / 2f;
                c.drawRoundRect(rect, radius, radius, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        void tickHaptic() {
            try {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            } catch (RuntimeException ignored) {}
        }

        void animateTo(float target) {
            if (animator != null) animator.cancel();
            if (!ValueAnimator.areAnimatorsEnabled() || !isLaidOut()) {
                position = target;
                pressScale = 1f;
                invalidate();
                return;
            }
            float initialScale = pressScale;
            animator = ValueAnimator.ofFloat(position, target);
            animator.setDuration(240);
            animator.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
            animator.addUpdateListener(a -> {
                position = (Float) a.getAnimatedValue();
                pressScale = 1f + (initialScale - 1f) * (1f - a.getAnimatedFraction());
                invalidate();
            });
            animator.start();
        }

        void bindPosition(float value) {
            if (!dragging && (animator == null || !animator.isRunning())) {
                position = value;
                invalidate();
            }
        }

        void focusRing(Canvas c) {
            if (isFocused()) {
                paint.setColor(accent);
                paint.setStrokeWidth(dp(2));
                paint.setStyle(Paint.Style.STROKE);
                rect.set(dp(2), dp(2), getWidth() - dp(2), getHeight() - dp(2));
                c.drawRoundRect(rect, dp(18), dp(18), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        boolean begin(MotionEvent e) {
            if (!isEnabled()) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (animator != null) animator.cancel();
                downX = e.getX();
                downY = e.getY();
                dragging = true;
                pressScale = 1.06f;
                setPressed(true);
                invalidate();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && dragging) {
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (dy > slop && dy > dx) {
                    cancelTouch();
                    return false;
                }
                if (dx > slop && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            return dragging;
        }

        void cancelTouch() {
            dragging = false;
            setPressed(false);
            animateTo(observedPosition());
        }

        abstract float observedPosition();

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override protected void onDetachedFromWindow() {
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }
    }

    static final class GlassSwitch extends Control {
        private Boolean checked;

        GlassSwitch(Context c, boolean dark, String label, Change change) {
            super(c, dark);
            listener = change;
            setContentDescription(label);
        }

        void bind(Boolean value, boolean enabled) {
            checked = value;
            setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.42f);
            if (!enabled && dragging) cancelTouch();
            setStateDescription(value == null ? "尚未读取" : value ? "已开启" : "已关闭");
            bindPosition(observedPosition());
        }

        @Override float observedPosition() { return Boolean.TRUE.equals(checked) ? 1f : 0f; }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(resolveSize((int) dp(64), ws), resolveSize((int) dp(48), hs));
        }

        @Override protected void onDraw(Canvas c) {
            float trackW = dp(54);
            float trackH = dp(30);
            float left = (getWidth() - trackW) / 2f;
            float top = (getHeight() - trackH) / 2f;
            float clamped = Math.max(0f, Math.min(1f, position));
            int trackColor = checked == null ? track : blendColor(track, switchOn, clamped);
            pill(c, left, top, left + trackW, top + trackH, trackColor);
            float knobH = dp(24);
            float knobW = dragging ? dp(28) : knobH;
            float travel = trackW - dp(6) - knobW;
            float x = left + dp(3) + travel * clamped;
            float y = top + dp(3);
            thumbSurface(c, x, y, x + knobW, y + knobH, Color.WHITE, dark ? 0x22000000 : 0x12000000, 0.5f);
            focusRing(c);
        }

        private void submit(boolean value) {
            if (!isEnabled() || checked == null) return;
            float releasePosition = position;
            if (value != checked) {
                tickHaptic();
                listener.changed(value ? 1 : 0);
            }
            // Confirmation remains the observed value. A pending label belongs to the row.
            position = releasePosition;
            animateTo(observedPosition());
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!begin(e)) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    position = Math.max(0f, Math.min(1f, observedPosition() + (e.getX() - downX) / dp(24)));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean tap = Math.abs(e.getX() - downX) < ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    dragging = false;
                    setPressed(false);
                    if (tap) performClick();
                    submit(tap ? !Boolean.TRUE.equals(checked) : position >= 0.5f);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelTouch();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean onKeyDown(int key, KeyEvent e) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { submit(false); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { submit(true); return true; }
            if (key == KeyEvent.KEYCODE_SPACE || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER) {
                submit(!Boolean.TRUE.equals(checked));
                return true;
            }
            return super.onKeyDown(key, e);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n) {
            super.onInitializeAccessibilityNodeInfo(n);
            n.setClassName("android.widget.Switch");
            n.setCheckable(checked != null);
            n.setChecked(Boolean.TRUE.equals(checked));
            n.setClickable(isEnabled());
            if (isEnabled()) n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK && isEnabled()) {
                submit(!Boolean.TRUE.equals(checked));
                return true;
            }
            return super.performAccessibilityAction(action, args);
        }
    }

    static final class NoiseSelector extends Control {
        private int[] values = {EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC};
        private String[] names = {"通透", "降噪"};
        private Integer observed;
        private boolean pending;
        private final Drawable anc, transparency, adaptive;

        NoiseSelector(Context c, boolean dark, Change change) {
            super(c, dark);
            listener = change;
            setContentDescription("噪声控制");
            anc = icon(c, "lp_noise_cancellation");
            transparency = icon(c, "lp_transparency");
            adaptive = icon(c, "lp_adaptive");
        }

        private Drawable icon(Context c, String name) {
            int id = getResources().getIdentifier(name, "drawable", c.getPackageName());
            Drawable d = id == 0 ? null : c.getDrawable(id);
            if (d != null) d = d.mutate();
            return d;
        }

        void resetCapabilities() {
            observed = null;
            pending = false;
        }

        void bind(Integer current, boolean enabled, boolean pending, boolean adaptiveSeen, boolean offSeen) {
            int count = 2 + (adaptiveSeen ? 1 : 0) + (offSeen ? 1 : 0);
            int[] next = new int[count];
            String[] labels = new String[count];
            int i = 0;
            if (offSeen) { next[i] = EarbudCommands.MODE_OFF; labels[i++] = "关闭"; }
            next[i] = EarbudCommands.MODE_TRANSPARENCY; labels[i++] = "通透";
            next[i] = EarbudCommands.MODE_ANC; labels[i++] = "降噪";
            if (adaptiveSeen) { next[i] = EarbudCommands.MODE_ADAPTIVE; labels[i] = "自适应"; }
            values = next;
            names = labels;
            observed = current;
            this.pending = pending;
            setEnabled(enabled);
            setAlpha(enabled ? 1f : (pending ? 0.72f : 0.45f));
            if (!enabled && dragging) cancelTouch();
            int index = observedIndex();
            String label = EarbudUiPolicy.listeningModeLabel(current, values, names);
            if (pending) label += "，请求等待耳机确认";
            setStateDescription(label);
            bindPosition(index >= 0 ? index : position);
            invalidate();
        }

        private int observedIndex() {
            for (int i = 0; i < values.length; i++) {
                if (observed != null && values[i] == observed) return i;
            }
            return -1;
        }

        @Override float observedPosition() {
            int index = observedIndex();
            return index >= 0 ? index : position;
        }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(resolveSize((int) dp(360), ws), resolveSize((int) dp(88), hs));
        }

        @Override protected void onDraw(Canvas c) {
            float capsuleH = dp(56);
            float width = getWidth();
            float segment = width / values.length;
            pill(c, 0, 0, width, capsuleH, track);
            int activeIdx = observedIndex();
            boolean hasThumb = (observed != null && activeIdx >= 0) || dragging;
            float thumbPos = Math.max(0f, Math.min(values.length - 1, position));
            if (hasThumb) {
                float x = thumbPos * segment;
                int stroke = pending ? accent : thumbBorder;
                float strokeW = pending ? 1.4f : 0.6f;
                thumbSurface(c, x + dp(4), dp(4), x + segment - dp(4), capsuleH - dp(4), thumb, stroke, strokeW);
            }
            for (int i = 0; i < values.length; i++) {
                float center = segment * (i + 0.5f);
                boolean selected = hasThumb && Math.abs(thumbPos - i) < 0.5f;
                int itemColor = selected ? accent : mutedInk;
                int textColor = selected ? ink : mutedInk;
                Drawable d = values[i] == EarbudCommands.MODE_ANC ? anc
                        : values[i] == EarbudCommands.MODE_TRANSPARENCY ? transparency
                        : values[i] == EarbudCommands.MODE_ADAPTIVE ? adaptive : null;
                if (d != null) {
                    d.setColorFilter(itemColor, PorterDuff.Mode.SRC_IN);
                    d.setBounds((int) (center - dp(18)), (int) dp(10), (int) (center + dp(18)), (int) dp(46));
                    d.draw(c);
                } else {
                    paint.setColor(itemColor);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.8f));
                    c.drawCircle(center, dp(28), dp(10), paint);
                    c.drawLine(center - dp(7), dp(35), center + dp(7), dp(21), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                paint.setColor(textColor);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(dp(12));
                paint.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                c.drawText(names[i], center, dp(78), paint);
            }
            paint.setTypeface(Typeface.DEFAULT);
            focusRing(c);
        }

        private void submit(int index) {
            if (!isEnabled()) return;
            index = Math.max(0, Math.min(values.length - 1, index));
            float releasePosition = position;
            if (observed == null || values[index] != observed) {
                tickHaptic();
                listener.changed(values[index]);
            }
            position = releasePosition;
            animateTo(observedPosition());
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!begin(e)) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                position = Math.max(0f, Math.min(values.length - 1, e.getX() / Math.max(1f, getWidth()) * values.length - 0.5f));
                invalidate();
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                dragging = false;
                setPressed(false);
                performClick();
                submit(EarbudUiPolicy.nearestSegment(e.getX(), getWidth(), values.length));
            }
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelTouch();
            return true;
        }

        @Override public boolean onKeyDown(int key, KeyEvent e) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                submit(EarbudUiPolicy.arrowTarget(observedIndex(), values.length, key == KeyEvent.KEYCODE_DPAD_LEFT));
                return true;
            }
            return super.onKeyDown(key, e);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n) {
            super.onInitializeAccessibilityNodeInfo(n);
            n.setClassName("android.widget.SeekBar");
            int index = observedIndex();
            if (index >= 0) {
                n.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, values.length - 1, index));
            }
            if (isEnabled()) {
                for (int i = 0; i < names.length; i++) {
                    n.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000000 + i, "切换到" + names[i]));
                }
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action >= 0x01000000 && action < 0x01000000 + values.length && isEnabled()) {
                submit(action - 0x01000000);
                return true;
            }
            return super.performAccessibilityAction(action, args);
        }
    }

    static final class VolumeSlider extends Control {
        private int current, max = 1;

        VolumeSlider(Context c, boolean dark, Change change) {
            super(c, dark);
            listener = change;
            setContentDescription("此 Android 设备的媒体音量");
        }

        void bind(int value, int maximum, boolean enabled) {
            current = value;
            max = Math.max(1, maximum);
            setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.45f);
            setStateDescription(Math.round(100f * current / max) + "%");
            bindPosition(observedPosition());
        }

        @Override float observedPosition() { return (float) current / max; }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(resolveSize((int) dp(320), ws), resolveSize((int) dp(48), hs));
        }

        @Override protected void onDraw(Canvas c) {
            float pad = dp(16);
            float usable = Math.max(1f, getWidth() - pad * 2f);
            float x = pad + Math.max(0f, Math.min(1f, position)) * usable;
            float y = getHeight() / 2f;
            float halfTrack = dp(4);
            pill(c, pad, y - halfTrack, getWidth() - pad, y + halfTrack, track);
            if (x > pad + halfTrack) {
                pill(c, pad, y - halfTrack, x, y + halfTrack, accent);
            }
            float knobR = dp(12);
            thumbSurface(c, x - knobR, y - knobR, x + knobR, y + knobR, Color.WHITE, dark ? 0x24000000 : 0x1A000000, 0.7f);
            focusRing(c);
        }

        private void submit(int value) {
            if (!isEnabled()) return;
            listener.changed(Math.max(0, Math.min(max, value)));
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!begin(e)) return false;
            float pad = dp(16);
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE || e.getActionMasked() == MotionEvent.ACTION_UP) {
                position = Math.max(0f, Math.min(1f, (e.getX() - pad) / Math.max(1f, getWidth() - pad * 2f)));
                invalidate();
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                dragging = false;
                setPressed(false);
                performClick();
                submit(Math.round(position * max));
                animateTo(position);
            }
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelTouch();
            return true;
        }

        @Override public boolean onKeyDown(int key, KeyEvent e) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                submit(current + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
                return true;
            }
            return super.onKeyDown(key, e);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n) {
            super.onInitializeAccessibilityNodeInfo(n);
            n.setClassName("android.widget.SeekBar");
            n.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, max, current));
            if (isEnabled()) {
                n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (isEnabled()) {
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && args != null) {
                    submit(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    submit(current + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1));
                    return true;
                }
            }
            return super.performAccessibilityAction(action, args);
        }
    }
}
