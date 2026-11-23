package dev.eyad.skytube.mixin;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.eyad.skytube.client.SkyTubeClient;
import dev.eyad.skytube.core.LocalChatCommand;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientSuggestionProvider.class)
abstract class ClientSuggestionProviderMixin {
    @Inject(method = "customSuggestion", at = @At("HEAD"), cancellable = true)
    private void skytube$keepChatDraftLocal(CommandContext<?> context,
            CallbackInfoReturnable<CompletableFuture<Suggestions>> callback) {
        SkyTubeClient mod = SkyTubeClient.get();
        if (mod != null && LocalChatCommand.matchesSuggestion(context.getInput(), mod.config().chatPrefix)) {
            callback.setReturnValue(Suggestions.empty());
        }
    }
}
