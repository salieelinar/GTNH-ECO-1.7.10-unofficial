package cn.dancingsnow.neoecoae.crafting.display.format;

import net.minecraft.client.resources.I18n;

/** Renders machine durations in wall-clock units instead of raw ticks (20 ticks = 1 second). */
public final class DurationFormatter {

    private static final String KEY = "gui.neoecoae.duration.";
    private static final int TICKS_PER_SECOND = 20;

    private DurationFormatter() {}

    /** Formats an elapsed or total duration, rounding down to whole seconds. */
    public static String format(int ticks) {
        return format(ticks, false);
    }

    /** Formats a remaining duration, rounding up so the display never understates the wait. */
    public static String formatRemaining(int ticks) {
        return format(ticks, true);
    }

    private static String format(int ticks, boolean roundUp) {
        // Sub-second machine cycles keep their exact 0.05-second precision; rounding them to 0 s hides short cycles.
        if (ticks > 0 && ticks < TICKS_PER_SECOND) return I18n.format(
            KEY + "sec",
            java.math.BigDecimal.valueOf(ticks * 5L, 2)
                .stripTrailingZeros()
                .toPlainString());
        long seconds = ticks <= 0 ? 0
            : (roundUp ? (ticks + TICKS_PER_SECOND - 1L) / TICKS_PER_SECOND : ticks / TICKS_PER_SECOND);
        if (seconds < 60) return I18n.format(KEY + "sec", seconds);
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (seconds < 3600)
            return rest == 0 ? I18n.format(KEY + "min_only", minutes) : I18n.format(KEY + "min", minutes, rest);
        long hours = seconds / 3600;
        minutes = seconds % 3600 / 60;
        rest = seconds % 60;
        if (rest > 0) return I18n.format(KEY + "hour_sec", hours, minutes, rest);
        return minutes == 0 ? I18n.format(KEY + "hour_only", hours) : I18n.format(KEY + "hour", hours, minutes);
    }
}
