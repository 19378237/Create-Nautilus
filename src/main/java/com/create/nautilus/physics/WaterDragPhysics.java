package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Mass-scaled water velocity relaxation following Aeronautics' EndSeaPhysics pattern.
 *
 * <p>The former SI quadratic force was divided by Sable's small native masses, producing wildly
 * material-dependent acceleration. Aeronautics instead computes a bounded velocity change, maps
 * it into plot space, then multiplies by mass/inertia before adding it to {@link ForceGroups#DRAG}.
 * That makes the resulting deceleration independent of whether a hull uses normal or very heavy
 * block properties and prevents high-speed collision tunnelling.</p>
 */
public final class WaterDragPhysics {
    private static final double AERONAUTICS_LINEAR_DRAG = 3.5D;
    private static final double AERONAUTICS_ANGULAR_DRAG = 3.0D;
    private static final double MAX_RELAXATION_PER_SUBSTEP = 0.85D;
    /**
     * An open/solid carrier receives no Nautilus buoyancy.  It must therefore be allowed to gain
     * a clearly visible downward velocity under Sable's normal gravity.  Water drag only starts
     * removing the downward component after this speed, preventing both the former near-hover and
     * the old unbounded fall/collision-tunnelling behaviour.
     */
    private static final double OPEN_BODY_MAX_DOWNWARD_SPEED = 5.0D;
    /** Closed hulls may freely heave at low speed; damping acts only on the excess. */
    private static final double CLOSED_HULL_FREE_HEAVE_SPEED = 1.5D;

    private WaterDragPhysics() {
    }

    public static void tick(final ServerSubLevel carrier, final RigidBodyHandle body,
                            final CarrierState state, final double submersion,
                            final double timeStep) {
        if (submersion <= 0.0D || !(timeStep > 0.0D)) {
            state.setHasPreviousLinearVelocity(false);
            return;
        }

        final MassData massData = carrier.getMassTracker();
        final Vector3dc centerOfMass = massData.getCenterOfMass();
        if (centerOfMass == null || massData.getMass() <= 0.0D) {
            return;
        }

        final boolean hasClosedEnvelope = !state.compartments().isEmpty();
        final double baseLinearRelaxation = Math.clamp(timeStep
                * AERONAUTICS_LINEAR_DRAG * NautilusConfig.DRAG_COEFFICIENT
                * submersion, 0.0D, MAX_RELAXATION_PER_SUBSTEP);
        // Added mass and heave damping model the large volume of water accelerated around a
        // closed hull.  Applying them to a solid block cluster was both physically unjustified
        // and strong enough to make it appear suspended despite receiving no levitation force.
        final double responseScale = hasClosedEnvelope
                ? 1.0D + NautilusConfig.ADDED_MASS_COEFFICIENT * submersion : 1.0D;
        final double horizontalRelaxation = Math.clamp(baseLinearRelaxation * responseScale,
                0.0D, MAX_RELAXATION_PER_SUBSTEP);
        final double closedHullVerticalRelaxation = Math.clamp(horizontalRelaxation
                        + timeStep * NautilusConfig.HEAVE_DAMPING_COEFFICIENT * submersion,
                0.0D, MAX_RELAXATION_PER_SUBSTEP);
        final double angularRelaxation = Math.clamp(timeStep
                * AERONAUTICS_ANGULAR_DRAG * NautilusConfig.DRAG_COEFFICIENT
                * submersion, 0.0D, MAX_RELAXATION_PER_SUBSTEP);

        final Vector3d worldVelocity = body.getLinearVelocity(new Vector3d());
        final Vector3d worldAngularVelocity = body.getAngularVelocity(new Vector3d());
        final double verticalVelocityChange;
        if (hasClosedEnvelope) {
            if (worldVelocity.y() < -CLOSED_HULL_FREE_HEAVE_SPEED) {
                verticalVelocityChange = (-CLOSED_HULL_FREE_HEAVE_SPEED - worldVelocity.y())
                        * closedHullVerticalRelaxation;
            } else if (worldVelocity.y() > CLOSED_HULL_FREE_HEAVE_SPEED) {
                verticalVelocityChange = (CLOSED_HULL_FREE_HEAVE_SPEED - worldVelocity.y())
                        * closedHullVerticalRelaxation;
            } else {
                // At low speed only gravity and hydrostatic lift determine the direction of
                // travel. The previous -v damping created an extremely small terminal velocity,
                // making carriers with very different net forces all look neutrally buoyant.
                verticalVelocityChange = 0.0D;
            }
        } else if (worldVelocity.y() < -OPEN_BODY_MAX_DOWNWARD_SPEED) {
            // Dampen only the speed in excess of the safety cap.  At rest and throughout normal
            // sinking this branch contributes no upward impulse, so it cannot balance gravity and
            // create a false equilibrium.
            verticalVelocityChange = (-OPEN_BODY_MAX_DOWNWARD_SPEED - worldVelocity.y())
                    * baseLinearRelaxation;
        } else if (worldVelocity.y() > 0.0D) {
            // Upward motion of an open structure is still resisted by water.
            verticalVelocityChange = -worldVelocity.y() * baseLinearRelaxation;
        } else {
            verticalVelocityChange = 0.0D;
        }
        final Vector3d desiredVelocityChange = new Vector3d(
                -worldVelocity.x() * horizontalRelaxation,
                verticalVelocityChange,
                -worldVelocity.z() * horizontalRelaxation);
        final Vector3d desiredAngularChange = new Vector3d(worldAngularVelocity)
                .mul(-angularRelaxation);

        final Vector3d localLinearImpulse = carrier.logicalPose()
                .transformNormalInverse(desiredVelocityChange)
                .mul(massData.getMass());
        final Vector3d localAngularImpulse = carrier.logicalPose()
                .transformNormalInverse(desiredAngularChange);
        massData.getInertiaTensor().transform(localAngularImpulse);

        final QueuedForceGroup drag = carrier.getOrCreateQueuedForceGroup(ForceGroups.DRAG.get());
        drag.recordPointForce(new Vector3d(centerOfMass), localLinearImpulse);
        drag.getForceTotal().applyLinearAndAngularImpulse(
                localLinearImpulse, localAngularImpulse);
    }
}
