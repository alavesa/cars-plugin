package fi.alavesa.cars;

import org.bukkit.configuration.ConfigurationSection;

/**
 * One vehicle type, editable in-game and persisted to cars.yml - the same
 * config-driven pattern as the guns plugin.
 */
public final class CarType {

    public final String id;
    public String name;
    public String model;        // custom_model_data string on the minecart item
    public double maxSpeed;     // blocks per second
    public double acceleration; // blocks per second, gained per second of throttle
    public double turnRate;     // degrees per tick at full steering
    public double scale;        // display model scale
    public String sound;        // engine sound key
    public int seats;           // driver + passengers, 1..4
    public double offsetX;      // display model offset (blocks)
    public double offsetY;
    public double offsetZ;
    public double seatYAdjust;  // per-type rider height tweak, live-editable
    public int cargoRows;       // 0 = no cargo hold; 1..6 = a storage GUI of that many rows (forklift/truck)
    public double maxHealth;    // hit points; the car takes damage when shot or punched
    public String wreckModel;   // custom_model_data string swapped in when the car is destroyed
    /** Visible cargo boxes on the vehicle: one [x,y,z] offset each. Each shows as a barrel/crate display
     *  that rides the car; edit with /car edit &lt;id&gt; cargo-box &lt;index&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;. */
    public java.util.List<double[]> cargoBoxes = new java.util.ArrayList<>();
    public String cargoBoxModel; // "" = plain BARREL block display; otherwise a custom_model_data string (e.g. the 1079 crate)
    public double cargoBoxScale; // display scale of each cargo box
    /** Seat positions in model space, driver first - filled from the model
     *  file's "driverseat"/"seat*" elements when one exists (see CarRegistry). */
    public java.util.List<double[]> seatOffsets = java.util.List.of();

    public CarType(String id) {
        this.id = id;
        this.name = id;
        this.model = "car_" + id;
        this.maxSpeed = 9.0;
        this.acceleration = 6.0;
        this.turnRate = 4.0;
        this.scale = 2.2;
        this.sound = "minecraft:entity.minecart.riding";
        this.seats = 2;
        this.offsetX = 0;
        this.offsetY = 0.5;
        this.offsetZ = 0;
        this.cargoRows = 0;
        this.maxHealth = 100.0;
        this.wreckModel = this.model + "_wreck";
        this.cargoBoxModel = "";
        this.cargoBoxScale = 0.6;
    }

    public static CarType load(String id, ConfigurationSection section) {
        CarType type = new CarType(id);
        type.name = section.getString("name", type.name);
        type.model = section.getString("model", type.model);
        type.maxSpeed = section.getDouble("max-speed", type.maxSpeed);
        type.acceleration = section.getDouble("acceleration", type.acceleration);
        type.turnRate = section.getDouble("turn-rate", type.turnRate);
        type.scale = section.getDouble("scale", type.scale);
        type.sound = section.getString("sound", type.sound);
        type.seats = Math.max(1, Math.min(4, section.getInt("seats", type.seats)));
        type.offsetX = section.getDouble("offset-x", type.offsetX);
        type.offsetY = section.getDouble("offset-y", type.offsetY);
        type.offsetZ = section.getDouble("offset-z", type.offsetZ);
        type.seatYAdjust = section.getDouble("seat-y-adjust", 0);
        type.cargoRows = Math.max(0, Math.min(6, section.getInt("cargo-rows", 0)));
        type.maxHealth = Math.max(1.0, section.getDouble("max-health", type.maxHealth));
        type.wreckModel = section.getString("wreck-model", type.model + "_wreck");
        type.cargoBoxModel = section.getString("cargo-box-model", "");
        type.cargoBoxScale = section.getDouble("cargo-box-scale", 0.6);
        type.cargoBoxes = new java.util.ArrayList<>();
        for (String pos : section.getStringList("cargo-boxes")) {
            String[] p = pos.trim().split("[ ,]+");
            if (p.length == 3) try {
                type.cargoBoxes.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])});
            } catch (NumberFormatException ignored) { }
        }
        return type;
    }

    public void save(ConfigurationSection section) {
        section.set("name", name);
        section.set("model", model);
        section.set("max-speed", maxSpeed);
        section.set("acceleration", acceleration);
        section.set("turn-rate", turnRate);
        section.set("scale", scale);
        section.set("sound", sound);
        section.set("seats", seats);
        section.set("offset-x", offsetX);
        section.set("offset-y", offsetY);
        section.set("offset-z", offsetZ);
        section.set("seat-y-adjust", seatYAdjust);
        section.set("cargo-rows", cargoRows);
        section.set("max-health", maxHealth);
        section.set("wreck-model", wreckModel);
        section.set("cargo-box-model", cargoBoxModel);
        section.set("cargo-box-scale", cargoBoxScale);
        java.util.List<String> boxes = new java.util.ArrayList<>();
        for (double[] b : cargoBoxes) boxes.add(b[0] + " " + b[1] + " " + b[2]);
        section.set("cargo-boxes", boxes);
    }
}
