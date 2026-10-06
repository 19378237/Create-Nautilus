package com.create.nautilus.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Historical post-AST patch target retained only for binary/source compatibility.
 *
 * <p>It is intentionally not registered as a mixin. Iris' ProgramBuilder receives source after
 * both shader-pack preprocessing and AST transformation, so mutating it there cannot safely
 * preserve pack option branches. All adapter injection is owned by IrisCompositeRendererMixin at
 * the single pre-AST boundary.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder", remap = false)
public abstract class IrisProgramBuilderMixin {
}
