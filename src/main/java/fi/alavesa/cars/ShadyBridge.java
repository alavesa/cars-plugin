package fi.alavesa.cars;

import org.bukkit.Location;

/**
 * Soft link to the Terminal plugin's shady app, by reflection so Cars never hard-depends on Terminal.
 * When a marked delivery barrel is winched/forklifted, its shady registration + pending deliveries are
 * carried on the cargo box and relocated to wherever the barrel is put back down.
 */
final class ShadyBridge {

    private ShadyBridge() { }

    /** The shady-barrel key registered at this block location, or null if Terminal isn't present / no barrel. */
    static String barrelKeyAt(Location loc) {
        try {
            Class<?> tp = Class.forName("fi.alavesa.terminal.TerminalPlugin");
            Object inst = tp.getMethod("instance").invoke(null);
            if (inst == null) return null;
            return (String) tp.getMethod("shadyBarrelKeyAt", Location.class).invoke(inst, loc);
        } catch (Throwable t) { return null; }
    }

    /** Move a marked barrel's shady registration to a new location. No-op (false) if Terminal isn't present. */
    static boolean relocate(String oldKey, Location to) {
        try {
            Class<?> tp = Class.forName("fi.alavesa.terminal.TerminalPlugin");
            Object inst = tp.getMethod("instance").invoke(null);
            if (inst == null) return false;
            Object r = tp.getMethod("relocateShadyBarrel", String.class, Location.class).invoke(inst, oldKey, to);
            return r instanceof Boolean b && b;
        } catch (Throwable t) { return false; }
    }
}
