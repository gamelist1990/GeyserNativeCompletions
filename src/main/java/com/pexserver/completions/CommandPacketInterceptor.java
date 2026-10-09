package com.pexserver.completions;

import io.netty.channel.*;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import java.util.*;
import java.util.function.*;

/** Runs on the channel's event loop; no Bukkit API or completion callback is called here. */
public final class CommandPacketInterceptor extends ChannelOutboundHandlerAdapter {
    private final Supplier<Map<String, CompletionModel.Command>> models;
    private final Consumer<AvailableCommandsPacket> baselineReceived;
    private final Consumer<Throwable> failure;
    private final Set<AvailableCommandsPacket> generated = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    public CommandPacketInterceptor(Supplier<Map<String, CompletionModel.Command>> models,
                                    Consumer<AvailableCommandsPacket> baselineReceived, Consumer<Throwable> failure) {
        this.models = models; this.baselineReceived = baselineReceived; this.failure = failure;
    }
    public void markGenerated(AvailableCommandsPacket packet) { generated.add(packet); }
    public void unmarkGenerated(AvailableCommandsPacket packet) { generated.remove(packet); }
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof BedrockPacketWrapper wrapper && wrapper.getSenderSubClientId() == 0 &&
                wrapper.getTargetSubClientId() == 0 && wrapper.getPacketBuffer() == null &&
                wrapper.getPacket() instanceof AvailableCommandsPacket available) {
            boolean owned = generated.remove(available);
            try {
                if (!owned) baselineReceived.accept(PacketComposer.copy(available));
                AvailableCommandsPacket patched = PacketComposer.patch(available, models.get());
                wrapper.setPacket(PacketComposer.uniqueHardEnums(PacketComposer.clientCompatible(patched)));
            } catch (RuntimeException | LinkageError e) {
                failure.accept(e); // Leave the original packet intact and continue transmission.
            }
        }
        ctx.write(msg, promise);
    }
}
