package com.colossalcraft.client;

import com.colossalcraft.colossus.ColossusCollision;
import com.colossalcraft.colossus.ColossusEntity;

final class ColossusCollisionAccess {
    /** Server-side Colossus (integrated server) for dev automation. */
    static ColossusEntity first() {
        for (ColossusEntity c : ColossusCollision.LOADED) if (!c.level().isClientSide && !c.isRemoved()) return c;
        return null;
    }
}
