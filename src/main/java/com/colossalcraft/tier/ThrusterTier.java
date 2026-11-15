package com.colossalcraft.tier;

public enum ThrusterTier {
    STEAM(40_000, 10), BLAZE(150_000, 40), ARC(450_000, 120), ION(1_500_000, 350), GRAVITON(8_000_000, 1_500);
    public final double force; public final double power;
    ThrusterTier(double f, double p) { force = f; power = p; }
}
