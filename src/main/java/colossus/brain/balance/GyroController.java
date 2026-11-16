package colossus.brain.balance;

import colossus.physics.PhysicsWorld;
import colossus.physics.api.BodyHandle;
import colossus.physics.api.BodyState;
import colossus.physics.api.PhysicsBackend;
import colossus.physics.math.Vec3;

import java.util.Map;

/** Runs one Colossus's Gyro Stabilizers on the physics thread, every physics step (not just every game tick). */
public final class GyroController implements PhysicsWorld.StepController {
    private final Map<BodyHandle, GyroStabilizer> gyros;

    public GyroController(Map<BodyHandle, GyroStabilizer> gyros) { this.gyros = Map.copyOf(gyros); }

    public Map<BodyHandle, GyroStabilizer> gyros() { return gyros; }

    @Override public void beforeStep(PhysicsBackend backend, double dt) {
        for (Map.Entry<BodyHandle, GyroStabilizer> e : gyros.entrySet()) {
            BodyState s = backend.bodyState(e.getKey());
            if (s == BodyState.MISSING) continue;
            Vec3 tau = e.getValue().update(s, dt);
            if (tau.lengthSq() > 0) backend.applyTorque(e.getKey(), tau);
        }
    }
}
