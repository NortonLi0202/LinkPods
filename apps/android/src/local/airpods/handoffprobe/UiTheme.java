package local.airpods.handoffprobe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/** Unified light/dark design tokens blending One UI geometry with clean minimalism. */
final class UiTheme {
    static final int CARD_RADIUS_DP = 26;
    static final int INNER_RADIUS_DP = 16;
    static final int PILL_RADIUS_DP = 999;

    final boolean dark;
    final float density;
    final int bg;
    final int sidebarBg;
    final int surface;
    final int surfaceSubtle;
    final int ink;
    final int muted;
    final int divider;
    final int accent;
    final int accentTint;
    final int ok;
    final int okTint;
    final int warn;
    final int warnTint;
    final int danger;
    final int dangerTint;
    final int ripple;

    private UiTheme(Context context) {
        dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        density = context.getResources().getDisplayMetrics().density;
        bg = dark ? 0xFF010101 : 0xFFF4F5F7;
        sidebarBg = dark ? 0xFF111114 : 0xFFEFEFF3;
        surface = dark ? 0xFF17171B : 0xFFFFFFFF;
        surfaceSubtle = dark ? 0xFF232329 : 0xFFECEDF1;
        ink = dark ? 0xFFF5F5F7 : 0xFF141418;
        muted = dark ? 0xFF9A9AA6 : 0xFF6B6B76;
        divider = dark ? 0xFF2B2B32 : 0xFFE5E6EB;
        accent = dark ? 0xFF3B9EFF : 0xFF0381FE;
        accentTint = dark ? 0x263B9EFF : 0x160381FE;
        ok = dark ? 0xFF32D74B : 0xFF1D8A3E;
        okTint = dark ? 0x2432D74B : 0x161D8A3E;
        warn = dark ? 0xFFFFD60A : 0xFFC56A00;
        warnTint = dark ? 0x24FFD60A : 0x18C56A00;
        danger = dark ? 0xFFFF453A : 0xFFD93025;
        dangerTint = dark ? 0x24FF453A : 0x16D93025;
        ripple = dark ? 0x26FFFFFF : 0x14000000;
    }

    static UiTheme of(Context context) {
        return new UiTheme(context);
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    GradientDrawable rounded(int fillColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    GradientDrawable outlined(int fillColor, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = rounded(fillColor, radiusDp);
        d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return d;
    }

    RippleDrawable rippleBg(int fillColor, float radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(ripple),
                rounded(fillColor, radiusDp),
                rounded(Color.WHITE, radiusDp));
    }

    void makeClickable(View view, int fillColor, float radiusDp, Runnable action) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(rippleBg(fillColor, radiusDp));
        view.setOnClickListener(v -> action.run());
    }
}
