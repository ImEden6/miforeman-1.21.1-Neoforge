package com.mervyn.miforeman.network;

import com.mervyn.miforeman.client.ClientAccess;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class MachineLinkSyncHandler {
    public static void handle(final MachineLinkSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> ClientAccess.handleMachineLinkSync(payload));
    }
}
