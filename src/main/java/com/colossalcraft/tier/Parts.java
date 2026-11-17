package com.colossalcraft.tier;

/**
 * Strength of the size-based parts (bearings, ball joints, motors). There are no materials: a part's strength comes from
 * its size. A 1x1 is the base; an n x n assembly is n^n times as strong (2x2 = 4x, 3x3 = 27x, 4x4 = 256x).
 */
public final class Parts {
    private Parts() {}
    /** Biggest assembly side. */
    public static final int MAX = 4;
    /** A 1x1 bearing: load it can hold straight on (N) and bending it can take (N*m). */
    public static final double BEARING_FORCE = 120_000, BEARING_MOMENT = 200_000;
    /** A 1x1 servo motor: torque (N*m), power (CJ/t), push over ground (N), top speed (RPM). */
    public static final double MOTOR_TORQUE = 150_000, MOTOR_POWER = 20, MOTOR_DRIVE = 90_000, MOTOR_RPM = 60;
    /** How much stronger an n x n assembly is than a 1x1. */
    public static double scale(int n) { return Math.pow(Math.max(1, n), Math.max(1, n)); }
}
