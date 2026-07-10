package dev.polybit.gitmc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.polybit.gitmc.block.BlockChangeTracker;
import dev.polybit.gitmc.block.BlockDelta;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Client entrypoint for GitMC. Renders the {@code /git status show|hide}
 * overlay: a translucent colored box over every block position tracked by
 * {@link BlockChangeTracker} since the last commit — green for newly placed
 * blocks, yellow for replaced blocks, red for removed blocks.
 */
public final class GitMCClient implements ClientModInitializer {

    /** How far outside the unit block the highlight box extends on each side, to avoid z-fighting. */
    private static final float INSET = 0.002f;

    private static final float[] COLOR_ADDED = {0.20f, 0.90f, 0.20f, 0.35f};
    private static final float[] COLOR_MODIFIED = {0.95f, 0.85f, 0.15f, 0.35f};
    private static final float[] COLOR_REMOVED = {0.95f, 0.15f, 0.15f, 0.35f};

    @Override
    public void onInitializeClient() {
        LevelRenderEvents.COLLECT_SUBMITS.register(GitMCClient::renderTrackedChanges);
    }

    private static void renderTrackedChanges(LevelRenderContext context) {
        BlockChangeTracker tracker = BlockChangeTracker.getInstance();
        if (!tracker.isOverlayVisible()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        Map<BlockPos, BlockDelta> deltas = tracker.snapshot(level.dimension());
        if (deltas.isEmpty()) {
            return;
        }

        Vec3 cameraPos = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();

        for (Map.Entry<BlockPos, BlockDelta> entry : deltas.entrySet()) {
            BlockPos pos = entry.getKey();
            float[] color = colorFor(entry.getValue().kind());

            poseStack.pushPose();
            poseStack.translate(
                pos.getX() - cameraPos.x,
                pos.getY() - cameraPos.y,
                pos.getZ() - cameraPos.z
            );
            collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox(),
                (pose, vertexConsumer) -> emitBox(pose, vertexConsumer, color));
            poseStack.popPose();
        }
    }

    /** Emits a unit cube (6 quads, 24 vertices) in the pose's local space, slightly inflated by {@link #INSET}. */
    private static void emitBox(PoseStack.Pose pose, VertexConsumer vc, float[] color) {
        float lo = -INSET;
        float hi = 1f + INSET;
        float r = color[0];
        float g = color[1];
        float b = color[2];
        float a = color[3];

        // RenderTypes.debugFilledBox() disables backface culling, so winding order doesn't matter here.
        quad(pose, vc, r, g, b, a, lo, lo, lo, hi, lo, lo, hi, lo, hi, lo, lo, hi); // -Y
        quad(pose, vc, r, g, b, a, lo, hi, lo, lo, hi, hi, hi, hi, hi, hi, hi, lo); // +Y
        quad(pose, vc, r, g, b, a, lo, lo, lo, lo, hi, lo, hi, hi, lo, hi, lo, lo); // -Z
        quad(pose, vc, r, g, b, a, lo, lo, hi, hi, lo, hi, hi, hi, hi, lo, hi, hi); // +Z
        quad(pose, vc, r, g, b, a, lo, lo, lo, lo, lo, hi, lo, hi, hi, lo, hi, lo); // -X
        quad(pose, vc, r, g, b, a, hi, lo, lo, hi, hi, lo, hi, hi, hi, hi, lo, hi); // +X
    }

    private static void quad(
        PoseStack.Pose pose, VertexConsumer vc, float r, float g, float b, float a,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        float x4, float y4, float z4
    ) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }

    private static float[] colorFor(BlockDelta.Kind kind) {
        return switch (kind) {
            case ADDED -> COLOR_ADDED;
            case MODIFIED -> COLOR_MODIFIED;
            case REMOVED -> COLOR_REMOVED;
        };
    }
}
