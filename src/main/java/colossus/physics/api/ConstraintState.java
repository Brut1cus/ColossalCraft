package colossus.physics.api;

/**
 * Read-only state of a joint after the last step.
 *
 * @param position current joint coordinate: angle in radians (hinge/swivel) or offset in metres (slider/spring)
 * @param velocity rate of change of {@code position}
 * @param force    magnitude of the constraint force in N over the last step (feeds the strain meter)
 * @param torque   magnitude of the constraint torque in N·m over the last step
 * @param broken   the backend removed it because it exceeded {@code breakForce}
 */
public record ConstraintState(double position, double velocity, double force, double torque, boolean broken) {
    public static final ConstraintState MISSING = new ConstraintState(0, 0, 0, 0, true);
}
