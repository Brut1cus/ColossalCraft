package com.colossalcraft.block;

/** Any block that stores energy (NBT key "Energy") a Colossus can draw from. Implemented by compat blocks. */
public interface ColossalBattery {
    long capacity();
    int output();
    String title();
}
