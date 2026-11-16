package colossus.physics.api;

import colossus.physics.math.Pose;
import colossus.physics.math.Vec3;

/**
 * The only door between mod logic and a physics engine (blueprint: "Keep the engine behind a PhysicsBackend interface").
 * <p>
 * Implementations: {@code colossus.physics.jolt.JoltBackend} (primary, bundled jolt-jni natives) and
 * {@code colossus.physics.xpbd.XpbdBackend} (pure-Java fallback, always available).
 * <p>
 * Threading: a backend is owned by exactly one thread (the physics thread, see {@code PhysicsWorld}).
 * Nothing here may touch Minecraft objects.
 */
public interface PhysicsBackend extends AutoCloseable {

    /** Short id shown in logs and config ("jolt", "xpbd"). */
    String id();

    BodyHandle createBody(BodyDesc desc);

    void removeBody(BodyHandle body);

    ConstraintHandle addConstraint(ConstraintDesc desc);

    void removeConstraint(ConstraintHandle constraint);

    /** Static world geometry (Minecraft terrain). The backend queries it near moving bodies. */
    void setTerrain(TerrainQuery terrain);

    /** World gravity in m/s² (blueprint default (0, -9.81, 0), scaled per dimension). */
    void setGravity(Vec3 gravity);

    /** Advance the simulation by dt seconds (one physics tick, internally split into substeps). */
    void step(double dt);

    // ---- per-step inputs (cleared after every step) ----

    /** Force in newtons applied at a world-space point for the next step. */
    void applyForce(BodyHandle body, Vec3 force, Vec3 worldPoint);

    /** Torque in N·m (world frame) for the next step. */
    void applyTorque(BodyHandle body, Vec3 torque);

    /** Servo target for a motorised joint: angle in radians (hinge) or offset in metres (slider). */
    void setMotorTarget(ConstraintHandle constraint, double target, double maxForceOrTorque);

    /** Teleport a body (assembly, disassembly, admin commands). Velocities are zeroed. */
    void setPose(BodyHandle body, Pose pose);

    /** Replace mass properties after blocks were placed or broken on board. */
    void setMassProperties(BodyHandle body, MassProperties mass);

    void setContactListener(ContactListener listener);

    // ---- state reads ----

    BodyState bodyState(BodyHandle body);

    ConstraintState constraintState(ConstraintHandle constraint);

    @Override void close();
}
