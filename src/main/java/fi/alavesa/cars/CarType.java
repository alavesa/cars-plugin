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
    }
}
