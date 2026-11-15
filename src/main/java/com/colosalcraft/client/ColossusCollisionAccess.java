package com.colosalcraft.client;

import com.colosalcraft.colossus.ColossusCollision;
import com.colosalcraft.colossus.ColossusEntity;

final class ColossusCollisionAccess {
    /** Server-side Colossus (integrated server) for dev automation. */
    static ColossusEntity first() {
        for (ColossusEntity c : ColossusCollision.LOADED) if (!c.level().isClientSide && !c.isRemoved()) return c;
        return null;
    }
}
