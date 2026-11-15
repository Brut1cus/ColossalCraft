package com.colosalcraft.tier;

/**
 * Material levels shared by joints (bearings) and motors.
 * Joint force capacity scales with cluster area (k blocks -> k * F),
 * bending-moment capacity scales with section modulus (k^1.5 -> n^3 for an n x n joint).
 */
public enum MaterialTier {
    //        name       jointForce N  jointMoment N*m  motorTorque N*m  motorPower CJ/t  driveForce N
    WOOD   ("wood",      20_000,       30_000,          20_000,           5,              15_000),
    STONE  ("stone",     50_000,       60_000,          50_000,          10,              35_000),
    IRON   ("iron",     120_000,      200_000,         150_000,          20,              90_000),
    GOLD   ("gold",     200_000,      350_000,         300_000,          40,             180_000),
    DIAMOND("diamond",  500_000,    1_000_000,       1_000_000,         100,             500_000);

    public final String id;
    public final double jointForce, jointMoment, motorTorque, motorPower, driveForce;

    MaterialTier(String id, double jf, double jm, double mt, double mp, double df) {
        this.id = id; this.jointForce = jf; this.jointMoment = jm; this.motorTorque = mt; this.motorPower = mp; this.driveForce = df;
    }

    public static double forceCap(MaterialTier t, int k) { return t.jointForce * k; }
    public static double momentCap(MaterialTier t, int k) { return t.jointMoment * Math.pow(k, 1.5); }
}
