package fi.alavesa.cars;

import org.bukkit.configuration.ConfigurationSection;

/**
 * One vehicle type, editable in-game and persisted to cars.yml - the same
 * config-driven pattern as the guns plugin.
 *
 * The car carries NO plugin-owned model any more. Instead each type names a
 * scoreboard TAG; a spawned car CLAIMS the nearest externally-spawned entity
 * carrying that tag (BetterModel, ModelEngine, an armour stand, an item
 * display - anything that is an entity with the tag) and teleports it along
 * each tick. Seats and the click hitbox come from config/commands, not a model
 * file.
 */
public final class CarType {

    public final String id;
    public String name;
    public String tag;          // scoreboard tag of the external model entity this car follows (e.g. "jeep")
    public double maxSpeed;     // blocks per second
    public double acceleration; // blocks per second, gained per second of throttle
    public double turnRate;     // degrees per tick at full steering
    public String sound;        // engine sound key
    public double seatYAdjust;  // per-type rider height tweak, live-editable
    public int cargoRows;       // 0 = no cargo hold; 1..6 = a storage GUI of that many rows (forklift/truck)
    public double maxHealth;    // hit points; the car takes damage when shot or punched
    /** Visible cargo boxes on the vehicle: one [x,y,z] offset each. Each shows as a plain barrel display
     *  that rides the car; edit with /car edit &lt;id&gt; cargo-box &lt;index&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;. */
    public java.util.List<double[]> cargoBoxes = new java.util.ArrayList<>();
    public double cargoBoxScale; // display scale of each cargo box
    public boolean drift;        // whether this car can drift (handbrake/sharp-turn slides); false = always full grip
    public boolean forklift;     // auto-picks up nearby barrels/crates onto its bed (no winch needed)
    // The Interaction click box for entering/hitting - set from config / commands (no model file any more).
    public double hitboxWidth = 1.9;
    public double hitboxHeight = 1.5;
    public double hitboxOffsetY = 0.0;
    // Right-clicking the car near this spot takes out the winch; set from config / commands.
    public boolean hasWinchSpot = false;
    public double winchX, winchY, winchZ;
    /** Seat positions in blocks, driver first - set with /car seat and persisted to cars.yml.
     *  Axes: +Z forward, +X across, +Y up, relative to the car's base. */
    public java.util.List<double[]> seatOffsets = new java.util.ArrayList<>();

    public CarType(String id) {
        this.id = id;
        this.name = id;
        this.tag = id;
        this.maxSpeed = 9.0;
        this.acceleration = 6.0;
        this.turnRate = 4.0;
        this.sound = "minecraft:entity.minecart.riding";
        this.cargoRows = 0;
        this.maxHealth = 100.0;
        this.cargoBoxScale = 0.6;
        this.drift = true;
        this.forklift = false;
        // sensible starting seats (driver + one passenger); tune with /car seat <id> ...
        this.seatOffsets.add(new double[]{0.35, 1.0, 0.3});
        this.seatOffsets.add(new double[]{-0.35, 1.0, 0.3});
    }

    public static CarType load(String id, ConfigurationSection section) {
        CarType type = new CarType(id);
        type.name = section.getString("name", type.name);
        // tag defaults to the old `model` value (minus a legacy "car_" prefix) for back-compat with
        // configs written before the tag-follow rework, else the id.
        String legacyModel = section.getString("model", null);
        String defaultTag = type.id;
        if (legacyModel != null) defaultTag = legacyModel.replaceFirst("(?i)^car_", "");
        type.tag = section.getString("tag", defaultTag);
        type.maxSpeed = section.getDouble("max-speed", type.maxSpeed);
        type.acceleration = section.getDouble("acceleration", type.acceleration);
        type.turnRate = section.getDouble("turn-rate", type.turnRate);
        type.sound = section.getString("sound", type.sound);
        type.seatYAdjust = section.getDouble("seat-y-adjust", 0);
        type.cargoRows = Math.max(0, Math.min(6, section.getInt("cargo-rows", 0)));
        type.maxHealth = Math.max(1.0, section.getDouble("max-health", type.maxHealth));
        type.cargoBoxScale = section.getDouble("cargo-box-scale", 0.6);
        type.drift = section.getBoolean("drift", true);
        type.forklift = section.getBoolean("forklift", false);
        type.hitboxWidth = section.getDouble("hitbox-width", type.hitboxWidth);
        type.hitboxHeight = section.getDouble("hitbox-height", type.hitboxHeight);
        type.hitboxOffsetY = section.getDouble("hitbox-offset-y", type.hitboxOffsetY);
        type.hasWinchSpot = section.getBoolean("winch", false);
        type.winchX = section.getDouble("winch-x", 0);
        type.winchY = section.getDouble("winch-y", 0);
        type.winchZ = section.getDouble("winch-z", 0);
        type.cargoBoxes = new java.util.ArrayList<>();
        for (String pos : section.getStringList("cargo-boxes")) {
            double[] p = parseTriple(pos);
            if (p != null) type.cargoBoxes.add(p);
        }
        // Seats: a list of "x y z" lines, driver first. Old configs stored `seats` as an int count and
        // took the layout from the .bbmodel; those have no list here, so we fall back to the defaults.
        java.util.List<double[]> seats = new java.util.ArrayList<>();
        for (String pos : section.getStringList("seats")) {
            double[] p = parseTriple(pos);
            if (p != null) seats.add(p);
        }
        if (!seats.isEmpty()) type.seatOffsets = seats;   // else keep the constructor defaults
        return type;
    }

    /** Parse "x y z" (space- or comma-separated) into a 3-double array, or null if it isn't three numbers. */
    private static double[] parseTriple(String raw) {
        String[] p = raw.trim().split("[ ,]+");
        if (p.length != 3) return null;
        try {
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void save(ConfigurationSection section) {
        section.set("name", name);
        section.set("tag", tag);
        section.set("max-speed", maxSpeed);
        section.set("acceleration", acceleration);
        section.set("turn-rate", turnRate);
        section.set("sound", sound);
        section.set("seat-y-adjust", seatYAdjust);
        section.set("cargo-rows", cargoRows);
        section.set("max-health", maxHealth);
        section.set("cargo-box-scale", cargoBoxScale);
        section.set("drift", drift);
        section.set("forklift", forklift);
        section.set("hitbox-width", hitboxWidth);
        section.set("hitbox-height", hitboxHeight);
        section.set("hitbox-offset-y", hitboxOffsetY);
        section.set("winch", hasWinchSpot);
        section.set("winch-x", winchX);
        section.set("winch-y", winchY);
        section.set("winch-z", winchZ);
        java.util.List<String> boxes = new java.util.ArrayList<>();
        for (double[] b : cargoBoxes) boxes.add(b[0] + " " + b[1] + " " + b[2]);
        section.set("cargo-boxes", boxes);
        java.util.List<String> seats = new java.util.ArrayList<>();
        for (double[] s : seatOffsets) seats.add(s[0] + " " + s[1] + " " + s[2]);
        section.set("seats", seats);
    }
}
