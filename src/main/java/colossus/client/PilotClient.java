package colossus.client;

import colossus.pilot.PilotInput;
import colossus.pilot.SeatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends the rider's movement keys to the server while riding a Pilot Seat (20 Hz), and a stop when they get up. */
final class PilotClient {
    private static int lastSeat = -1;

    static void tick() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) { lastSeat = -1; return; }
        if (p.getVehicle() instanceof SeatEntity seat) {
            lastSeat = seat.getId();
            PacketDistributor.sendToServer(new PilotInput(seat.getId(), p.input.forwardImpulse, p.input.leftImpulse));
        } else if (lastSeat >= 0) {
            PacketDistributor.sendToServer(new PilotInput(lastSeat, 0, 0)); // the server drops it if we no longer ride
            lastSeat = -1;
        }
    }

    private PilotClient() {}
}
