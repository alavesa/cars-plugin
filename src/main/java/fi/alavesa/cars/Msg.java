package fi.alavesa.cars;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Actionbar bridge. When the Labra plugin is on the server, the speedometer
 * goes through its ActionBars hub so it STACKS above persistent HUD lines
 * (the NVG battery bar) instead of overwriting them - same soft-dependency
 * trick Guns uses. The speedometer rides the hub's TOP slot: a per-tick line
 * that always owns the text position, so "blocks/s" reads out ABOVE the
 * battery and never flickers against a gun message.
 *
 * Without Labra, a plain sendActionBar - checked once, not per tick. Note the
 * fallback can't sit "above" anything on its own (vanilla gives one line), so
 * it simply prints the speedometer; the ordering guarantee only holds through
 * the Labra hub, which is where these plugins actually live together.
 */
final class Msg {

    private static final boolean HUB;

    static {
        boolean found;
        try {
            Class.forName("fi.alavesa.labra.ActionBars");
            found = org.bukkit.Bukkit.getPluginManager().getPlugin("Labra") != null;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        HUB = found;
    }

    private Msg() { }

    /** A one-off message (mount/eject notices), same slot Guns messages use. */
    static void actionbar(Player player, Component text) {
        if (HUB) {
            fi.alavesa.labra.ActionBars.message(player, text);
        } else {
            player.sendActionBar(text);
        }
    }

    /** The speedometer: re-sent every tick, always the top line when Labra
     *  is present so it sits above the NVG battery and any other indicator. */
    static void speedometer(Player player, Component text) {
        if (HUB) {
            fi.alavesa.labra.ActionBars.top(player, text);
        } else {
            player.sendActionBar(text);
        }
    }
}
