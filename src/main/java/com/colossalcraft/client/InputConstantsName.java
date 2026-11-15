package com.colossalcraft.client;

import com.mojang.blaze3d.platform.InputConstants;

final class InputConstantsName {
    static String of(int code) { return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString(); }
}
