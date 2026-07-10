# Cars — config-driven vehicles for Paper

[![Reviewed by PatchPilots](https://img.shields.io/badge/Reviewed%20by-PatchPilots-8A2BE2)](https://github.com/alavesa/patchpilots)

Rideable, drivable vehicles with **real WASD driving** (Paper's player-input API — no
carrot-on-a-stick tricks), **multiple seats** (driver + passengers) and fully
**config-driven vehicle types** editable in-game — the same philosophy as
[guns-plugin](https://github.com/alavesa/guns-plugin). Ships with the **Facility
Jeep**. Part of the SCP facility family.

## Install

1. `Cars-x.y.z.jar` → server `plugins/`. Paper **1.21.3+** (player input API), Java 21.
2. Resource pack: the combined **scp_and_chemistry.zip** includes the jeep model.
   Without it, cars show as a giant minecart item. Which is also funny.

## Driving

- `/car spawn jeep` → **right-click the car**: first click takes the wheel, later
  clicks fill the passenger seats (driver + up to 3 passengers, per type).
- **W** throttle · **S** brake, then reverse · **A/D** steer (sharper with speed,
  mirrored in reverse) · **sneak** to get out
- Engine pitch rises with speed. The car rolls to a stop when abandoned, climbs
  slabs/steps, and takes fall gravity like anything else.
- Cars are unkillable and persist across restarts; `/car remove` scraps everything
  within 16 blocks (passengers step out unharmed).

## Vehicle types (`cars.yml`, editable in-game)

```
/car create <id>
/car edit <id> name|model|max-speed|acceleration|turn-rate|scale|sound|seats <value>
/car list
```

| Property | Meaning | Jeep default |
|---|---|---|
| `name` | display name | Facility Jeep |
| `model` | custom_model_data hook on the minecart item | car_jeep |
| `max-speed` | blocks/second | 9.0 |
| `acceleration` | b/s gained per second of throttle | 6.0 |
| `turn-rate` | degrees per tick at full grip | 4.0 |
| `scale` | model scale | 2.2 |
| `sound` | engine sound key | entity.minecart.riding |
| `seats` | driver + passengers (1–4) | 2 |

New types spawn with model hook `car_<id>` — add a case for it in
`assets/minecraft/items/minecart.json` and a Blockbench model under
`assets/cars/models/vehicle/`, or point `model` at `car_jeep` to reuse the jeep.
**Forward is +Z** in the model. The model's position on the car is tuned with
`offset-x/y/z` (display translation, in blocks — `offset-y 0.5` is the default ride
height).

## Seats straight from your Blockbench model

Name elements in your model and the plugin turns them into riding positions:

1. In Blockbench, name one element **`driverseat`** (that seat drives) and the
   others **`seat`**, **`seat2`**, **`seat3`**.
2. Export the model into the resource pack as usual — the `name` fields are ignored
   by the client — **and drop the same .json into `plugins/Cars/models/<model>.json`**
   (e.g. `models/car_jeep.json`).
3. `/car reload`, respawn the car. Each named element's center becomes a seat, scaled
   and rotated with the car; the seat count follows the model automatically.

The bundled jeep ships with named seats out of the box (driver front-right, three
passengers). If no model file exists, sensible default seat positions are used.
If riders sit slightly too high or low for your model, tune `seat-y-adjust` in
`config.yml` (default −0.45).

## How it works (nerd corner)

- A car is an invisible pig (physics: gravity, wall collision, step-height 1.1)
  carrying the model display, a mount hitbox and the driver; velocity comes from the
  plugin, steering state lives in the pig's own yaw.
- Passenger seats are free-standing invisible armor stands pulled along with
  **velocity, never teleports** — teleporting a seat would eject the passenger.
- Everything ticks statelessly by scoreboard tag and survives restarts; orphaned
  parts self-clean.

## Notes

- Driver input needs Paper 1.21.3+ (`PlayerInputEvent`). Older servers: passengers
  ride fine, the car just won't move.
- `seats` edits apply to newly spawned cars; existing cars keep their seat count.
