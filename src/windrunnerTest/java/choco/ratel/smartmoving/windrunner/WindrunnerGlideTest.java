package choco.ratel.smartmoving.windrunner;

import net.minecraft.world.phys.Vec3;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Environment;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Input;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.State;

final class WindrunnerGlideTest {
    public static void main(String[] args) { run(); }

    static void run() {
        glideAndBank();
        inertiaAndRecovery();
        maneuverability();
        visualCorrections();
        glideBoosts();
        atmosphereAndDeployment();
        deploymentAttitude();
        updrafts();
        energyAndSlowDeployment();
        durability();
        impacts();
        windrunnerMoves();
        System.out.println("Windrunner glide, boost and movement scenarios passed.");
    }

    private static void glideAndBank() {
        Sim straight = trimmed();
        System.out.printf("Wingsuit trim: forward %.2f, sink %.2f blocks/s, ratio %.2f, pitch %.2f%n",
                straight.v.horizontalDistance() * 20, -straight.v.y * 20,
                straight.v.horizontalDistance() / -straight.v.y, straight.s.pitch);
        check(straight.v.horizontalDistance() > 0.65 && straight.v.horizontalDistance() < 0.95, "Wingsuit fits aircraft travel balance");
        check(straight.v.y < -0.12 && straight.v.y > -0.20, "Hands-off glide still spends altitude");
        double ratio = straight.v.horizontalDistance() / -straight.v.y;
        check(ratio > 4.5 && ratio < 5.5, "A clean glide covers about five blocks per block of altitude");
        Sim bank = straight.copy();
        double before = bank.p.y;
        for (int i = 0; i < 120; i++) { straight.tick(Input.NONE); bank.tick(new Input(0, 1)); }
        System.out.printf("Wingsuit turn: extra altitude %.2f, roll %.2f, speed %.2f%n",
                straight.p.y - bank.p.y, bank.s.roll, bank.v.length() * 20);
        check(bank.p.y < straight.p.y - 1, "Banking spends vertical support");
        check(bank.p.subtract(straight.p).horizontalDistance() > 5, "Bank curves the flight path");
        check(bank.p.y < before, "Sustained turn cannot climb for free");
        Sim dive = trimmed();
        for (int i = 0; i < 120; i++) dive.tick(new Input(1, 0));
        System.out.printf("Wingsuit dive: %.2f blocks/s, pitch %.2f%n", dive.v.length() * 20, dive.s.pitch);
        check(dive.v.length() > 0.9 && dive.v.length() < 2, "Dive accelerates within a bounded drag envelope");
    }

    private static void inertiaAndRecovery() {
        Sim sim = trimmed();
        sim.tick(new Input(-1, 1));
        check(sim.s.pitchRate < -0.5 && sim.s.roll < -1, "Pitch and roll respond on the first input tick");
        for (int i = 0; i < 4; i++) sim.tick(new Input(0, 1));
        float rate = sim.s.rollRate;
        sim.tick(new Input(0, -1));
        check(rate * sim.s.rollRate > 0, "Reversing bank first brakes the existing rotation");
        for (int i = 0; i < 4; i++) sim.tick(new Input(0, -1));
        check(sim.s.rollRate > 0, "Opposite bank reverses rotation within a quarter second");
        Sim stalled = new Sim(new Vec3(0, -0.1, 0.15));
        stalled.s.pitch = -50;
        double initial = stalled.p.y;
        int recovered = -1;
        for (int i = 0; i < 350; i++) {
            stalled.tick(Input.NONE);
            if (i > 20 && stalled.s.recovery == 0 && WindrunnerGlideModel.sample(stalled.v, stalled.s, stalled.air).stall() < 0.05) {
                recovered = i; break;
            }
        }
        System.out.printf("Wingsuit stall recovery: tick %d, altitude %.2f, distance %.2f%n",
                recovered, initial - stalled.p.y, stalled.p.horizontalDistance());
        check(recovered > 20 && recovered < 300, "Released stall recovers progressively");
        check(initial - stalled.p.y > 3 && stalled.p.horizontalDistance() > 5, "Recovery spends altitude and travel");
    }

    private static void maneuverability() {
        Sim turn = trimmed();
        for (int i = 0; i < 10; i++) turn.tick(new Input(0, 1));
        System.out.printf("Wingsuit response: half-second bank %.2f%n", turn.s.roll);
        check(turn.s.roll < -40, "Half a second of steering reaches a decisive bank");
        for (int i = 10; i < 30; i++) turn.tick(new Input(0, 1));
        double heading = Math.toDegrees(Math.atan2(-turn.v.x, turn.v.z));
        System.out.printf("Wingsuit turn at 1.5s: path heading %.2f, nose %.2f%n", heading, turn.s.yaw);
        check(heading > 45, "Bank changes the actual flight path, not just the nose");
        for (int i = 0; i < 15; i++) turn.tick(Input.NONE);
        check(Math.abs(turn.s.roll) < 8, "Releasing steering levels the wings promptly");

        Sim pitch = trimmed();
        float initialPitch = pitch.s.pitch;
        for (int i = 0; i < 8; i++) pitch.tick(new Input(-1, 0));
        System.out.printf("Wingsuit pitch at 0.4s: change %.2f, sink %.2f%n", initialPitch - pitch.s.pitch, -pitch.v.y * 20);
        check(initialPitch - pitch.s.pitch > 15, "Pull-up has useful pitch response within 0.4 seconds");

        Sim pullout = new Sim(new Vec3(0, -0.9, 1.1));
        for (int i = 0; i < 20; i++) pullout.tick(new Input(-1, 0));
        System.out.printf("Wingsuit dive pull-out at 1s: vertical %.2f, pitch %.2f, recovery %.2f%n",
                pullout.v.y * 20, pullout.s.pitch, pullout.s.recovery);
        check(pullout.v.y > -0.2, "An energetic dive can pull out within one second");
        check(pullout.s.recovery < 0.25, "Ordinary pull-out remains inside the wider maneuver envelope");
    }

    private static void visualCorrections() {
        WindrunnerGlideVisuals view = new WindrunnerGlideVisuals();
        State state = new State();
        state.yaw = 178;
        view.reset(state, 0.8);
        view.beginTick(state);
        State before = state.copy();
        before.yaw = -178; before.pitch = 4; before.roll = -6;
        State corrected = before.copy();
        corrected.yaw = 179; corrected.pitch = 1; corrected.roll = -2;
        Vec3 beforePosition = new Vec3(1, 2, 3), correctedPosition = beforePosition.add(0.3, -0.2, 0.1);
        float[] yaw = new float[5], pitch = new float[5], roll = new float[5];
        for (int i = 0; i < 5; i++) {
            float partial = i / 4F;
            yaw[i] = view.yaw(before, partial); pitch[i] = view.pitch(before, partial); roll[i] = view.roll(before, partial);
        }
        view.correct(before, corrected, beforePosition.subtract(correctedPosition), 1.2);
        for (int i = 0; i < 5; i++) {
            float partial = i / 4F;
            check(Math.abs(net.minecraft.util.Mth.wrapDegrees(view.yaw(corrected, partial) - yaw[i])) < 0.001
                    && Math.abs(view.pitch(corrected, partial) - pitch[i]) < 0.001
                    && Math.abs(view.roll(corrected, partial) - roll[i]) < 0.001,
                    "A snapshot cannot jump the displayed attitude at any subframe, including wrapped yaw");
            Vec3 displayed = Vec3.ZERO.lerp(correctedPosition, partial).add(view.positionOffset(partial));
            check(displayed.distanceTo(Vec3.ZERO.lerp(beforePosition, partial)) < 1.0E-7,
                    "Position reconciliation preserves the displayed path between ticks");
        }
        for (int i = 0; i < 6; i++) view.beginTick(corrected);
        check(view.positionOffset(1).length() < 0.03
                        && Math.abs(view.pitch(corrected, 1) - corrected.pitch) < 0.25,
                "Small corrections converge promptly instead of leaving lasting camera lag");
        view.updateSpeed(1.6);
        check(view.airspeed(0) == 0.8 && view.airspeed(0.5F) > 0.8 && view.airspeed(1) < 1.6,
                "Speed FOV interpolates within a tick rather than pulsing at the physics rate");
        view.correct(corrected, state, new Vec3(20, 0, 0), 1);
        check(view.positionOffset(0).equals(Vec3.ZERO) && view.positionOffset(1).equals(Vec3.ZERO),
                "A large relocation resets camera offsets immediately");
    }

    private static void glideBoosts() {
        Sim glide = trimmed(), held = glide.copy(), tapped = glide.copy();
        held.tick(new Input(0, 0, true));
        glide.tick(Input.NONE);
        System.out.printf("Boost: extra speed %.2f blocks/s%n", (held.v.length() - glide.v.length()) * 20);
        check(held.v.length() > glide.v.length() + WindrunnerGlideModel.BOOST * 0.8, "A fresh dash press boosts along the nose");
        check(WindrunnerGlideModel.boosted(held.s), "The boost reports the tick it fired for wear and effects");
        Sim reference = held.copy();
        for (int i = 0; i < 40; i++) { held.tick(new Input(0, 0, true)); reference.tick(Input.NONE); }
        check(held.v.distanceTo(reference.v) < 1.0E-9, "Holding the dash key adds nothing after the first kick");

        tapped.tick(new Input(0, 0, true));
        tapped.tick(Input.NONE);
        tapped.tick(new Input(0, 0, true));
        check(!WindrunnerGlideModel.boosted(tapped.s), "A re-press inside the cooldown cannot chain boosts");
        tapped.tick(Input.NONE);
        for (int i = 0; i < WindrunnerGlideModel.BOOST_COOLDOWN; i++) tapped.tick(Input.NONE);
        tapped.tick(new Input(0, 0, true));
        check(WindrunnerGlideModel.boosted(tapped.s), "The boost returns once its short cooldown has run out");

        Sim pumped = trimmed(), unpowered = pumped.copy();
        for (int i = 0; i < 200; i++) {
            pumped.tick(new Input(0, 0, i % (WindrunnerGlideModel.BOOST_COOLDOWN + 1) == 0));
            unpowered.tick(Input.NONE);
        }
        System.out.printf("Timed boosts: speed %.2f blocks/s, altitude vs glide %+.2f%n",
                pumped.v.length() * 20, pumped.p.y - unpowered.p.y);
        check(pumped.p.y > unpowered.p.y + 10, "Well-timed boosts carry the robes beyond an unpowered glide");
        check(pumped.v.length() < 2, "Overspeed drag still bounds a boosted glide");

        State vacuumState = State.deploy(new Vec3(0, -0.2, 0.8), 0);
        Vec3 vacuum = WindrunnerGlideModel.tick(new Vec3(0, -0.2, 0.8), vacuumState, new Input(0, 0, true), Environment.VACUUM);
        check(vacuum.distanceTo(new Vec3(0, -0.2 - WindrunnerGlidePhysics.GRAVITY, 0.8)) < 1.0E-9,
                "Wind magic has nothing to push against in the End's vacuum");
    }

    private static void atmosphereAndDeployment() {
        Vec3 entry = new Vec3(0.4, -0.2, 0.6);
        State state = State.deploy(entry, 90);
        Vec3 vacuum = WindrunnerGlideModel.tick(entry, state, new Input(-1, 1), Environment.VACUUM);
        check(vacuum.distanceTo(entry.add(0, -WindrunnerGlidePhysics.GRAVITY, 0)) < 1.0E-9,
                "The End preserves momentum and gravity without aerodynamic control");
        Sim calm = trimmed(), windy = calm.copy();
        Vec3 wind = new Vec3(0.15, 0.04, -0.08);
        windy.air = new Environment(wind, 1); windy.v = windy.v.add(wind);
        for (int i = 0; i < 100; i++) { calm.tick(Input.NONE); windy.tick(Input.NONE); }
        check(calm.v.distanceTo(windy.v.subtract(wind)) < 1.0E-7, "Forces depend on airspeed, not ground speed");
        Sim fast = new Sim(new Vec3(0.8, -4, 0.5));
        for (int i = 0; i < 200; i++) fast.tick(new Input((i % 40 < 20) ? -1 : 1, 1, i % 30 < 10));
    }

    private static void deploymentAttitude() {
        State stationary = State.deploy(Vec3.ZERO, 35);
        State walking = State.deploy(new Vec3(0, 0.4, 0.25), 35);
        State falling = State.deploy(new Vec3(0, -2, 0.25), 35);
        State fast = State.deploy(new Vec3(0, -0.1, 0.8), 35);
        check(stationary.pitch > walking.pitch && walking.pitch > fast.pitch,
                "More horizontal deployment momentum starts a progressively shallower glide");
        check(Math.abs(walking.pitch - falling.pitch) < 0.001,
                "Vertical entry speed does not override the momentum-based initial pitch");
        check(stationary.yaw == 35 && fast.yaw == 0,
                "Deployment heading uses horizontal momentum with look direction as the stationary fallback");
    }

    private static void updrafts() {
        Sim calm = trimmed(), thermal = calm.copy();
        thermal.air = new Environment(new Vec3(0, 0.18, 0), 1);
        for (int i = 0; i < 100; i++) {
            calm.tick(Input.NONE);
            thermal.tick(Input.NONE);
        }
        check(thermal.v.y > calm.v.y && thermal.p.y > calm.p.y + 5,
                "A wingsuit must ride a sustained lava or storm updraft instead of ignoring vertical wind");
    }

    private static void energyAndSlowDeployment() {
        Sim stopped = new Sim(Vec3.ZERO);
        stopped.tick(Input.NONE);
        check(stopped.v.horizontalDistance() < 0.005 && stopped.v.y < 0,
                "A stationary deployment must fall to develop airflow");
        Sim moving = new Sim(new Vec3(0.4, -0.3, 0.8));
        double previousEnergy = moving.v.lengthSqr() * 0.5;
        for (int i = 0; i < 500; i++) {
            moving.tick(new Input(i % 80 < 40 ? -1 : 1, i % 60 < 30 ? -1 : 1));
            double energy = moving.v.lengthSqr() * 0.5 + WindrunnerGlidePhysics.GRAVITY * moving.p.y;
            check(energy <= previousEnergy + 1.0E-8, "Controls and lift cannot generate mechanical energy in calm air");
            previousEnergy = energy;
        }
        Sim thin = trimmed();
        thin.air = new Environment(Vec3.ZERO, 0.5);
        for (int i = 0; i < 500; i++) thin.tick(Input.NONE);
        check(thin.v.length() > trimmed().v.length(), "Thin air requires more airspeed for the same wing");
    }

    private static void impacts() {
        check(WindrunnerGlidePhysics.impactDamage(new Vec3(0, -0.2, 0.25), new Vec3(0, -0.01, 0.25), true, false) == 0, "A slow, settled touchdown lands safely");
        check(WindrunnerGlidePhysics.impactDamage(new Vec3(0, -2, 0), Vec3.ZERO, true, false) > 20, "Late opening remains a hard impact even on a short final step");
        check(WindrunnerGlidePhysics.impactDamage(new Vec3(0, 0, 1), Vec3.ZERO, false, true) > 0, "Wall impacts use collision speed");
    }

    private static void durability() {
        check(WindrunnerGlideWear.DURABILITY == 240, "Robe durability stays distinct from the Elytra");
        check(WindrunnerGlideWear.deployment(0.8) == 1, "An ordinary opening flexes the membranes once");
        check(WindrunnerGlideWear.deployment(2) == 2, "A severe-speed opening adds stress wear");
        check(WindrunnerGlideWear.periodic(99, 0.8) == 0 && WindrunnerGlideWear.periodic(100, 0.8) == 1,
                "Ordinary loaded fabric wears once every five seconds");
        check(WindrunnerGlideWear.periodic(20, 1.3) == 1 && WindrunnerGlideWear.periodic(20, 1.8) == 2,
                "Sustained overspeed adds wear");
        check(WindrunnerGlideWear.BOOST == 1, "Each glide boost strains the membranes once");
        check(WindrunnerGlideWear.deepStall(0.6F, 0) && WindrunnerGlideWear.deepStall(0, 0.5F)
                        && !WindrunnerGlideWear.deepStall(0.2F, 0),
                "Deep stall wear remains latched through recovery");
    }

    private static void windrunnerMoves() {
        Vec3 falling = new Vec3(0.2, -1.2, 0);
        double kick = WindrunnerMoves.doubleJumpSpeed(1 + WindrunnerArmor.BOOTS_JUMP, 0);
        Vec3 jumped = WindrunnerMoves.doubleJump(falling, new Vec3(0, 0, 1), kick);
        check(jumped.y == kick, "A double jump replaces any fall with a full kick");
        check(jumped.z > 0 && jumped.x == 0.2, "A double jump nudges toward input and keeps existing drift");
        check(WindrunnerMoves.doubleJump(new Vec3(0, 0.9, 0), Vec3.ZERO, kick).y == 0.9, "A double jump never slows a faster climb");
        check(Math.abs(WindrunnerMoves.doubleJumpSpeed(1.1, 0.1) - (WindrunnerMoves.DOUBLE_JUMP_SPEED * 1.1 + 0.1)) < 1.0E-9,
                "The boots scale the double jump and vanilla Jump Boost still adds on top");
        double vanilla = jumpHeight(0.42), booted = jumpHeight(0.42 * (1 + WindrunnerArmor.BOOTS_JUMP));
        System.out.printf("Boots jump: %.2f -> %.2f blocks, double jump %.2f blocks%n", vanilla, booted, jumpHeight(kick));
        check(Math.abs(booted - vanilla - 0.25) < 0.03, "The boots add about a quarter block to a standing jump");
        check(WindrunnerArmor.BOOTS_SAFE_FALL == 3, "The boots double vanilla's three-block safe fall");
        check(WindrunnerArmor.BOOTS_SAVE_HEALTH == 18, "The boots save a lethal fall from nine hearts or more");
        Vec3 dashJump = WindrunnerMoves.dashJump(new Vec3(1.2, 0.15, 0), 0.462);
        check(dashJump.x == 1.2 && dashJump.y == 0.462, "A jump out of a ground dash keeps the dash's momentum");

        Vec3 north = WindrunnerMoves.dashDirection(180, 0, 0);
        check(north.distanceTo(new Vec3(0, 0, -1)) < 1.0E-9, "An idle dash goes the way the player faces");
        check(WindrunnerMoves.wish(0, 0, 1).distanceTo(new Vec3(1, 0, 0)) < 1.0E-9, "Strafing left at yaw 0 heads east, as in vanilla");
        check(Math.abs(WindrunnerMoves.wish(0, 1, 1).length() - 1) < 1.0E-9, "Diagonal input is normalized");
        Vec3 dash = WindrunnerMoves.airDash(new Vec3(0.3, -0.8, 0), new Vec3(0, 0, 1));
        check(Math.abs(dash.z - WindrunnerMoves.DASH_SPEED) < 1.0E-9 && dash.y == WindrunnerMoves.DASH_LIFT,
                "A dash sets its speed and arrests the fall");
        check(Math.abs(dash.x - 0.3 * WindrunnerMoves.DASH_SIDE_KEEP) < 1.0E-9, "A dash sheds most sideways drift");
        check(WindrunnerMoves.airDash(new Vec3(0, 0, 1.4), new Vec3(0, 0, 1)).z == 1.4, "A dash never slows faster travel");
        Vec3 ground = WindrunnerMoves.groundDash(new Vec3(0.1, 0, 0.3), new Vec3(1, 0, 0));
        check(Math.abs(ground.x - WindrunnerMoves.DASH_SPEED) < 1.0E-9 && ground.y == WindrunnerMoves.GROUND_DASH_HOP
                        && Math.abs(ground.z - 0.3 * WindrunnerMoves.DASH_SIDE_KEEP) < 1.0E-9,
                "A ground dash is the air dash's burst launched as a low hop");
        check(jumpHeight(WindrunnerMoves.GROUND_DASH_HOP) < WindrunnerMoves.GROUND_DASH_ELEVATION,
                "A ground dash's own hop never reads as leaving its starting height");

        // Wall run: a dead band around 45 degrees between the along and up triggers.
        check(WindrunnerWallRunModel.classify(80) == WindrunnerWallRunModel.UP
                && WindrunnerWallRunModel.classify(30) == WindrunnerWallRunModel.ALONG, "Steep hits run up, glancing hits run along");
        for (int angle = 41; angle < 70; angle++) {
            check(WindrunnerWallRunModel.classify(angle) == WindrunnerWallRunModel.NONE, "No wall run at " + angle + " degrees");
        }
        check(WindrunnerWallRunModel.classify(-10) == WindrunnerWallRunModel.NONE, "Moving away from a wall never runs it");
        Vec3 east = new Vec3(1, 0, 0);
        Vec3 heading30 = new Vec3(Math.sin(Math.toRadians(30)), 0, Math.cos(Math.toRadians(30)));
        check(Math.abs(WindrunnerWallRunModel.incidence(heading30, east) - 30) < 1.0E-6, "Incidence is measured against the wall's face");
        double climbed = 0;
        int upTicks = 0;
        for (; WindrunnerWallRunModel.upSpeed(upTicks) > 0; upTicks++) climbed += WindrunnerWallRunModel.upSpeed(upTicks);
        System.out.printf("Wall run up: %.1f blocks over %d ticks%n", climbed, upTicks);
        check(climbed > 3.8 && climbed <= 4.3, "A run up tops out about four blocks up");
        Vec3 tangent = WindrunnerWallRunModel.tangent(heading30, east);
        check(tangent.distanceTo(new Vec3(0, 0, 1)) < 1.0E-9, "A glancing run follows the wall, not the heading");
        Vec3 run = WindrunnerWallRunModel.along(new Vec3(0.1, 0.3, 0.5), tangent, east);
        check(run.z == 0.5 && run.y == -WindrunnerWallRunModel.ALONG_SINK && run.x > 0,
                "A run along keeps faster momentum, sinks from the start and presses into the wall");
        check(WindrunnerWallRunModel.along(Vec3.ZERO, tangent, east).z == WindrunnerWallRunModel.ALONG_SPEED,
                "A slow entry is brought up to running speed");
        System.out.printf("Wall run along: sinks %.1f blocks over %.1f s%n",
                WindrunnerWallRunModel.ALONG_SINK * WindrunnerWallRunModel.ALONG_TICKS, WindrunnerWallRunModel.ALONG_TICKS / 20.0);
        Vec3 wallKick = WindrunnerWallRunModel.kick(new Vec3(0.08, -0.06, 0.4), east, 1.1);
        check(wallKick.x == -WindrunnerWallRunModel.KICK_AWAY && wallKick.z == 0.4
                        && Math.abs(wallKick.y - WindrunnerWallRunModel.KICK_LIFT * 1.1) < 1.0E-9,
                "A kick pushes off the wall, keeps the run's momentum and scales with jump strength");

        // The boots clamp after gravity and drag, so the cap is the terminal sink.
        double drop = 0, capped = 0;
        int reach = 0;
        for (int i = 0; i < 200; i++) {
            drop -= capped;
            capped = WindrunnerMoves.capFall(new Vec3(0, (capped - 0.08) * 0.98, 0)).y;
            if (capped == -WindrunnerMoves.FALL_CAP) { reach = i + 1; break; }
        }
        System.out.printf("Boots fall cap: %.0f blocks/s after %d ticks and %.1f blocks%n",
                WindrunnerMoves.FALL_CAP * 20, reach, drop);
        check(reach > 20 && reach < 30, "The boots' fall cap arrives after about a second of falling");
        check(WindrunnerMoves.capFall(new Vec3(0.3, -1, 0.2)).equals(new Vec3(0.3, -1, 0.2)), "The fall cap leaves slower falls alone");
    }

    /** Peak height of a vanilla jump launched at {@code speed}, under 0.08 gravity and 0.98 drag. */
    private static double jumpHeight(double speed) {
        double y = 0, peak = 0;
        for (int i = 0; i < 100 && (speed > 0 || y > 0); i++) {
            y += speed;
            speed = (speed - 0.08) * 0.98;
            peak = Math.max(peak, y);
        }
        return peak;
    }

    private static Sim trimmed() {
        Sim sim = new Sim(new Vec3(0, -0.25, 0.8));
        for (int i = 0; i < 900; i++) sim.tick(Input.NONE);
        sim.p = Vec3.ZERO;
        return sim;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Sim {
        State s;
        Vec3 v, p = Vec3.ZERO;
        Environment air = Environment.CALM;
        Sim(Vec3 entry) { v = entry; s = State.deploy(entry, 0); }
        Sim copy() { Sim c = new Sim(v); c.s = s.copy(); c.p = p; c.air = air; return c; }
        void tick(Input input) {
            v = WindrunnerGlideModel.tick(v, s, input, air);
            p = p.add(v);
            check(Double.isFinite(p.lengthSqr()) && v.length() < 8, "Flight stays finite through fast openings and reversals");
        }
    }
}
