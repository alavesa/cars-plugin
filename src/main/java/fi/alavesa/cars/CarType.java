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
    }
}
