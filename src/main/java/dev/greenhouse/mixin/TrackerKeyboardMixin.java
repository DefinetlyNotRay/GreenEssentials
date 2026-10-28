package dev.greenhouse.mixin;

import dev.greenhouse.TrackerHud;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class TrackerKeyboardMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void greenhouse$typeDate(long window, CharacterEvent input, CallbackInfo ci) {
        if (TrackerHud.typeDate(input)) ci.cancel();
    }
}
