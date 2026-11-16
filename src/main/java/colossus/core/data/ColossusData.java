package colossus.core.data;

import colossus.brain.gait.GaitPattern;
import colossus.kinetics.ComponentScaling;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Live data-pack values, replaced on every {@code /reload}. Read on the game thread. */
public final class ColossusData {
    public static final BlockPhysicsTable BLOCK_PHYSICS = new BlockPhysicsTable();
    public static final ComponentScaling SCALING = new ComponentScaling();
    public static final Map<String, GaitPattern> GAITS = new ConcurrentHashMap<>();

    private ColossusData() {}
}
