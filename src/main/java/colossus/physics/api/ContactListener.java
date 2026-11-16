package colossus.physics.api;

import colossus.physics.math.Vec3;

/**
 * Receives contacts after each step. Used by foot contact sensing, ground pressure (demolition) and impact damage.
 * Called on the physics thread: implementations must only enqueue data.
 */
@FunctionalInterface
public interface ContactListener {
    /**
     * @param body        the body touching
     * @param other       the other body, or null for terrain
     * @param worldPoint  contact point
     * @param normal      unit normal pointing into {@code body}
     * @param normalForce average normal force over the step in N
     */
    void onContact(BodyHandle body, BodyHandle other, Vec3 worldPoint, Vec3 normal, double normalForce);

    ContactListener NONE = (b, o, p, n, f) -> {};
}
