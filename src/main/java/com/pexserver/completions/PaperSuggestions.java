package com.pexserver.completions;

import com.mojang.brigadier.CommandDispatcher;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** Mojang-mapped Paper adapter; no commands are executed and no NMS types are packaged. */
final class PaperSuggestions {
    private final CommandDispatcher<Object> dispatcher;
    @SuppressWarnings("unchecked")
    PaperSuggestions(Server server) throws ReflectiveOperationException {
        Object minecraft = server.getClass().getMethod("getServer").invoke(server);
        Object commands = minecraft.getClass().getMethod("getCommands").invoke(minecraft);
        dispatcher = (CommandDispatcher<Object>) commands.getClass().getMethod("getDispatcher").invoke(commands);
    }
    NativeArgumentScan.Provider forPlayer(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object source = handle.getClass().getMethod("createCommandSourceStack").invoke(handle);
        return new JavaSuggestions<>(dispatcher, source);
    }
}
