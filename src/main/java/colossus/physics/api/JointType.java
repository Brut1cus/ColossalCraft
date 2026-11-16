package colossus.physics.api;

/** Constraint kinds, mapped one-to-one from the joint blocks in the blueprint. */
public enum JointType {
    /** Thrust Bearing: 1 rotational DOF with limits (knees, elbows, ankle pitch). */
    HINGE(1, 0),
    /** Swivel Bearing / Axle: 1 rotational DOF, unlimited (turrets, waist yaw, wheels). */
    SWIVEL(1, 0),
    /** Ball Bearing: 3 rotational DOF, cone + twist limits (hips, shoulders, neck). */
    BALL(3, 0),
    /** Universal Joint: 2 rotational DOF (ankles, wrists). */
    UNIVERSAL(2, 0),
    /** Piston / Slider Joint: 1 linear DOF with limits. */
    SLIDER(0, 1),
    /** Spring Damper: passive 1 linear DOF (suspension, foot pads). */
    SPRING_DAMPER(0, 1),
    /** Fixed Weld: 0 DOF. */
    FIXED(0, 0),
    /** Dock joint between a child Colossus and its parent Segment: fixed while docked, removed on release. */
    DOCK(0, 0);

    public final int rotationalDof, linearDof;

    JointType(int rot, int lin) { this.rotationalDof = rot; this.linearDof = lin; }

    public boolean motorisable() { return this == HINGE || this == SWIVEL || this == SLIDER; }
}
