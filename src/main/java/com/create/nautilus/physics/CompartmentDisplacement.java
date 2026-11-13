package com.create.nautilus.physics;

import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Call-local ownership of overlapping carrier displacement; never changes room topology or stock. */
final class CompartmentDisplacement {
    private static final double EPS = 1.0E-9D;
    private final ServerSubLevel carrier;
    private final Pose3d pose;
    private final List<Neighbour> neighbours = new ArrayList<>();

    static CompartmentDisplacement create(final ServerSubLevel carrier) {
        return new CompartmentDisplacement(carrier);
    }

    private CompartmentDisplacement(final ServerSubLevel carrier) {
        this.carrier = carrier;
        pose = new Pose3d(carrier.logicalPose());
        final var bounds = carrier.getPlot().getBoundingBox();
        if (bounds == null) return;
        final AABB world = transformedBounds(pose, new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX() + 1D, bounds.maxY() + 1D, bounds.maxZ() + 1D));
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(carrier.getLevel(), new BoundingBox3d(world))) {
            if (!(candidate instanceof ServerSubLevel other) || other == carrier || other.isRemoved()) continue;
            final var otherBounds = other.getPlot().getBoundingBox();
            if (otherBounds == null) continue;
            final Pose3d otherPose = new Pose3d(other.logicalPose());
            if (!world.intersects(transformedBounds(otherPose, new AABB(otherBounds.minX(), otherBounds.minY(),
                    otherBounds.minZ(), otherBounds.maxX() + 1D, otherBounds.maxY() + 1D, otherBounds.maxZ() + 1D)))) continue;
            neighbours.add(new Neighbour(other, otherPose,
                    NautilusData.levelData(other).existingCarrier(other.getUniqueId())));
        }
        neighbours.sort(Comparator.comparing(n -> n.carrier.getUniqueId()));
    }

    /** Null preserves the existing single-carrier path, including its summation and centroid conventions. */
    Domain domain(final CompartmentState room) {
        if (neighbours.isEmpty()) return null;
        final AABB roomBounds = transformedBounds(pose, room.bounds());
        final List<Neighbour> relevant = neighbours.stream().filter(n -> n.worldBounds.intersects(roomBounds)).toList();
        if (relevant.isEmpty()) return null;
        final List<Cell> cells = new ArrayList<>();
        boolean changed = false;
        double capacity = 0D;
        double envelope = 0D;
        for (final long packed : room.cells()) {
            final BlockPos pos = BlockPos.of(packed);
            List<Polyhedron> owned = List.of(Polyhedron.cube());
            for (final Neighbour other : relevant) {
                owned = other.subtract(room, pos, owned);
                if (owned.isEmpty()) break;
            }
            final double volume = owned.stream().mapToDouble(p -> p.moment().volume).sum();
            envelope += volume;
            if (volume < 1D - EPS) changed = true;
            if (volume <= EPS || !room.waterCells().contains(packed)) continue;
            cells.add(new Cell(pos, owned));
            capacity += volume;
        }
        return changed ? new Domain(cells, envelope, capacity, room.waterMb() / 1000D) : null;
    }

    /** A physical dry envelope cannot spend its saved water in another carrier's occupied space. */
    final class Domain {
        private final Map<Long, Cell> cells = new HashMap<>();
        final double capacity;
        final double dryVolume;
        final double surface;

        Domain(final List<Cell> geometry, final double envelope, final double capacity, final double waterVolume) {
            this.capacity = envelope;
            dryVolume = Math.max(0D, capacity - waterVolume);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (final Cell cell : geometry) {
                cells.put(cell.pos.asLong(), cell);
                for (final Polyhedron part : cell.parts) {
                    for (final List<Vector3d> face : part.faces) for (final Vector3d p : face) {
                        final double y = cell.worldY(p);
                        low = Math.min(low, y); high = Math.max(high, y);
                    }
                }
            }
            if (geometry.isEmpty()) surface = Double.POSITIVE_INFINITY;
            else if (waterVolume <= EPS) surface = low - 1D;
            else if (waterVolume >= capacity - EPS) surface = high + 1D;
            else {
                // Same monotone free-surface model and height precision as the existing solver,
                // applied to exclusive physical space. Whole voxels keep their analytic CDF.
                for (int i = 0; i < 24; i++) {
                    final double mid = (low + high) * .5D;
                    double below = 0D;
                    for (final Cell cell : geometry) below += cell.volumeBelow(mid);
                    if (below < waterVolume) low = mid; else high = mid;
                }
                surface = (low + high) * .5D;
            }
        }

        Contribution submergedDry(final BlockPos pos, final double outsideSurface) {
            final Cell cell = cells.get(pos.asLong());
            if (cell == null || outsideSurface <= surface || dryVolume <= EPS) return Contribution.EMPTY;
            final Moment submerged = cell.below(outsideSurface);
            final Moment flooded = cell.below(surface);
            final double volume = Math.max(0D, submerged.volume - flooded.volume);
            if (volume <= EPS) return Contribution.EMPTY;
            final Vector3d weighted = cell.displacementMoment(
                    new Vector3d(submerged.weighted).sub(flooded.weighted), volume, surface, outsideSurface)
                    .fma(volume, new Vector3d(pos.getX(), pos.getY(), pos.getZ()));
            return new Contribution(volume, weighted);
        }
    }

    record Contribution(double volume, Vector3d weightedCentre) {
        private static final Contribution EMPTY = new Contribution(0D, new Vector3d());
    }

    private final class Cell {
        final BlockPos pos;
        final List<Polyhedron> parts;
        final Vector3d worldOrigin;
        final Vector3d up;
        final CompartmentWaterGeometry.ProjectedInterval wholeCell;

        Cell(final BlockPos pos, final List<Polyhedron> parts) {
            this.pos = pos; this.parts = parts;
            wholeCell = parts.size() == 1 && parts.getFirst() == Polyhedron.cube()
                    ? CompartmentWaterGeometry.projectedCell(carrier, pos) : null;
            worldOrigin = pose.transformPosition(new Vector3d(pos.getX(), pos.getY(), pos.getZ()));
            up = new Vector3d(pose.transformNormal(new Vector3d(1, 0, 0)).y,
                    pose.transformNormal(new Vector3d(0, 1, 0)).y,
                    pose.transformNormal(new Vector3d(0, 0, 1)).y);
        }
        double worldY(final Vector3d p) { return worldOrigin.y + up.dot(p); }
        double volumeBelow(final double height) {
            if (wholeCell != null) return wholeCell.fractionBelow(height);
            final Plane plane = new Plane(up, height - worldOrigin.y);
            double volume = 0D;
            for (final Polyhedron part : parts) volume += part.volumeBelow(plane);
            return volume;
        }
        Vector3d displacementMoment(final Vector3d ownedMoment, final double volume,
                                    final double lower, final double upper) {
            // BuoyancyPhysics discretizes an uncut voxel at its centre, even for a partial
            // waterline. Keep that convention continuously as another carrier starts to overlap;
            // only the centroid shift caused by the removed foreign space changes its moment.
            if (wholeCell != null) return new Vector3d(.5D).mul(volume);
            final Moment fullUpper = fullBelow(upper), fullLower = fullBelow(lower);
            final double fullVolume = fullUpper.volume - fullLower.volume;
            if (fullVolume <= EPS) return ownedMoment;
            final Vector3d originalCentroid = new Vector3d(fullUpper.weighted).sub(fullLower.weighted).div(fullVolume);
            return ownedMoment.fma(volume, new Vector3d(.5D).sub(originalCentroid));
        }
        Moment fullBelow(final double height) {
            if (height == Double.POSITIVE_INFINITY) return Polyhedron.cube().moment();
            if (height == Double.NEGATIVE_INFINITY) return Moment.EMPTY;
            final Polyhedron cut = Polyhedron.cube().clip(new Plane(up, height - worldOrigin.y));
            return cut == null ? Moment.EMPTY : cut.moment();
        }
        Moment below(final double height) {
            if (height == Double.POSITIVE_INFINITY) return Moment.sum(parts);
            if (height == Double.NEGATIVE_INFINITY) return Moment.EMPTY;
            final Plane plane = new Plane(up, height - worldOrigin.y);
            double volume = 0D;
            final Vector3d weighted = new Vector3d();
            for (final Polyhedron part : parts) {
                final Polyhedron cut = part.clip(plane);
                if (cut == null) continue;
                final Moment moment = cut.moment(); volume += moment.volume; weighted.add(moment.weighted);
            }
            return new Moment(volume, weighted);
        }
    }

    private final class Neighbour {
        final ServerSubLevel carrier;
        final Pose3d otherPose;
        final CarrierState state;
        final AABB worldBounds;
        final Map<Long, Occupant> occupants = new HashMap<>();
        final Vector3d[] axes;

        Neighbour(final ServerSubLevel carrier, final Pose3d otherPose, final CarrierState state) {
            this.carrier = carrier; this.otherPose = otherPose; this.state = state;
            final var bounds = carrier.getPlot().getBoundingBox();
            worldBounds = transformedBounds(otherPose, new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX() + 1D, bounds.maxY() + 1D, bounds.maxZ() + 1D));
            final Vector3d dx = otherPose.transformNormalInverse(pose.transformNormal(new Vector3d(1, 0, 0)));
            final Vector3d dy = otherPose.transformNormalInverse(pose.transformNormal(new Vector3d(0, 1, 0)));
            final Vector3d dz = otherPose.transformNormalInverse(pose.transformNormal(new Vector3d(0, 0, 1)));
            axes = new Vector3d[]{new Vector3d(dx.x, dy.x, dz.x), new Vector3d(dx.y, dy.y, dz.y),
                    new Vector3d(dx.z, dy.z, dz.z)};
        }

        List<Polyhedron> subtract(final CompartmentState room, final BlockPos pos, List<Polyhedron> parts) {
            final Vector3d origin = otherPose.transformPositionInverse(
                    pose.transformPosition(new Vector3d(pos.getX(), pos.getY(), pos.getZ())));
            final Vector3d low = new Vector3d(origin), high = new Vector3d(origin);
            for (int i = 0; i < 3; i++) {
                final Vector3d a = axes[i];
                low.setComponent(i, origin.get(i) + Math.min(0, a.x) + Math.min(0, a.y) + Math.min(0, a.z));
                high.setComponent(i, origin.get(i) + Math.max(0, a.x) + Math.max(0, a.y) + Math.max(0, a.z));
            }
            // Bound traversal by the actual neighbouring plot, not by distant padded world bounds.
            final var bounds = carrier.getPlot().getBoundingBox();
            final Set<Long> covered = new LinkedHashSet<>();
            for (int x = Math.max(bounds.minX(), (int) Math.floor(low.x + EPS)); x <= Math.min(bounds.maxX(), (int) Math.ceil(high.x - EPS) - 1); x++) {
                for (int y = Math.max(bounds.minY(), (int) Math.floor(low.y + EPS)); y <= Math.min(bounds.maxY(), (int) Math.ceil(high.y - EPS) - 1); y++) {
                    for (int z = Math.max(bounds.minZ(), (int) Math.floor(low.z + EPS)); z <= Math.min(bounds.maxZ(), (int) Math.ceil(high.z - EPS) - 1); z++) {
                        final BlockPos otherPos = new BlockPos(x, y, z);
                        final Occupant occupant = occupants.computeIfAbsent(otherPos.asLong(), ignored -> occupant(otherPos));
                        if (!occupant.solid && (occupant.room == null || !precedes(occupant.room, room))) continue;
                        covered.add(otherPos.asLong());
                    }
                }
            }
            // Adjacent covered voxels have no boundary between them. Coalesce the small local
            // candidate set before clipping, so a rotated solid box is not cut into dozens of
            // artificial fragments at its internal grid planes on every physics substep.
            while (!covered.isEmpty()) {
                final BlockPos first = BlockPos.of(covered.iterator().next());
                final int x0 = first.getX(), y0 = first.getY(), z0 = first.getZ();
                int x1 = x0, y1 = y0, z1 = z0;
                while (covered.contains(BlockPos.asLong(x1 + 1, y0, z0))) x1++;
                while (coveredBox(covered, x0, x1, y1 + 1, y1 + 1, z0, z0)) y1++;
                while (coveredBox(covered, x0, x1, y0, y1, z1 + 1, z1 + 1)) z1++;
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                    covered.remove(BlockPos.asLong(x, y, z));
                }
                final Plane[] planes = {new Plane(axes[0], x1 + 1D - origin.x), new Plane(new Vector3d(axes[0]).negate(), origin.x - x0),
                        new Plane(axes[1], y1 + 1D - origin.y), new Plane(new Vector3d(axes[1]).negate(), origin.y - y0),
                        new Plane(axes[2], z1 + 1D - origin.z), new Plane(new Vector3d(axes[2]).negate(), origin.z - z0)};
                final List<Polyhedron> remaining = new ArrayList<>();
                for (final Polyhedron part : parts) part.subtract(planes, remaining);
                parts = remaining;
                if (parts.isEmpty()) return parts;
            }
            return parts;
        }

        private boolean coveredBox(final Set<Long> cells, final int x0, final int x1,
                                   final int y0, final int y1, final int z0, final int z1) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                if (!cells.contains(BlockPos.asLong(x, y, z))) return false;
            }
            return true;
        }

        private Occupant occupant(final BlockPos pos) {
            if (!carrier.getLevel().hasChunkAt(pos)) return new Occupant(false, null);
            final var block = carrier.getLevel().getBlockState(pos);
            final boolean solid = !block.getCollisionShape(carrier.getLevel(), pos).isEmpty();
            return new Occupant(solid, state == null ? null : state.compartmentAt(pos).orElse(null));
        }

        private boolean precedes(final CompartmentState otherRoom, final CompartmentState room) {
            final double otherSize = otherRoom.volumeBlocks() * scaleVolume(otherPose);
            final double size = room.volumeBlocks() * scaleVolume(pose);
            final int bySize = Double.compare(otherSize, size);
            if (bySize != 0) return bySize < 0;
            return carrier.getUniqueId().compareTo(CompartmentDisplacement.this.carrier.getUniqueId()) < 0;
        }
    }

    private record Occupant(boolean solid, CompartmentState room) { }
    private static double scaleVolume(final Pose3dc pose) {
        return Math.abs(pose.scale().x() * pose.scale().y() * pose.scale().z());
    }

    private static AABB transformedBounds(final Pose3dc pose, final AABB box) {
        final Vector3d low = new Vector3d(Double.POSITIVE_INFINITY), high = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int c = 0; c < 8; c++) {
            final Vector3d p = pose.transformPosition(new Vector3d((c & 1) == 0 ? box.minX : box.maxX,
                    (c & 2) == 0 ? box.minY : box.maxY, (c & 4) == 0 ? box.minZ : box.maxZ));
            low.min(p); high.max(p);
        }
        return new AABB(low.x, low.y, low.z, high.x, high.y, high.z);
    }

    /** All clipping coordinates are relative to one target voxel, avoiding large plot-coordinate cancellation. */
    private record Plane(Vector3d normal, double offset) {
        Plane {
            final double length = normal.length();
            normal = new Vector3d(normal).div(length); offset /= length;
        }
        double distance(final Vector3d point) { return normal.dot(point) - offset; }
        Plane opposite() { return new Plane(new Vector3d(normal).negate(), -offset); }
    }

    private record Moment(double volume, Vector3d weighted) {
        static final Moment EMPTY = new Moment(0D, new Vector3d());
        static Moment sum(final List<Polyhedron> parts) {
            double volume = 0D; final Vector3d weighted = new Vector3d();
            for (final Polyhedron part : parts) { final Moment m = part.moment(); volume += m.volume; weighted.add(m.weighted); }
            return new Moment(volume, weighted);
        }
    }

    private static final class Polyhedron {
        final List<List<Vector3d>> faces;
        private Moment cachedMoment;
        Polyhedron(final List<List<Vector3d>> faces) { this.faces = faces; }
        private static final Polyhedron UNIT = makeCube();
        static Polyhedron cube() {
            return UNIT;
        }
        private static Polyhedron makeCube() {
            final Vector3d[] p = new Vector3d[8];
            for (int i = 0; i < 8; i++) p[i] = new Vector3d(i & 1, (i >> 1) & 1, (i >> 2) & 1);
            return new Polyhedron(List.of(List.of(p[0], p[2], p[6], p[4]), List.of(p[1], p[5], p[7], p[3]),
                    List.of(p[0], p[4], p[5], p[1]), List.of(p[2], p[3], p[7], p[6]),
                    List.of(p[0], p[1], p[3], p[2]), List.of(p[4], p[6], p[7], p[5])));
        }
        void subtract(final Plane[] planes, final List<Polyhedron> outside) {
            Polyhedron inside = this;
            for (final Plane plane : planes) {
                final double[] range = inside.range(plane);
                if (range[1] <= EPS) continue;
                if (range[0] >= -EPS) { outside.add(inside); return; }
                final Polyhedron exposed = inside.clip(plane.opposite());
                if (exposed != null) outside.add(exposed);
                inside = inside.clip(plane);
                if (inside == null) return;
            }
        }
        double[] range(final Plane plane) {
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (final List<Vector3d> face : faces) for (final Vector3d p : face) {
                final double d = plane.distance(p); low = Math.min(low, d); high = Math.max(high, d);
            }
            return new double[]{low, high};
        }
        Polyhedron clip(final Plane plane) {
            final double[] range = range(plane);
            if (range[1] <= EPS) return this;
            if (range[0] >= -EPS) return null;
            final List<List<Vector3d>> clipped = new ArrayList<>(); final List<Vector3d> cap = new ArrayList<>();
            for (final List<Vector3d> face : faces) {
                final List<Vector3d> polygon = new ArrayList<>();
                Vector3d previous = face.getLast(); double pd = plane.distance(previous);
                for (final Vector3d point : face) {
                    final double d = plane.distance(point);
                    if ((pd <= 0D) != (d <= 0D)) {
                        final Vector3d intersection = new Vector3d(previous).lerp(point, pd / (pd - d));
                        polygon.add(intersection);
                        if (cap.stream().noneMatch(p -> p.distanceSquared(intersection) < EPS * EPS)) cap.add(intersection);
                    }
                    if (d <= 0D) polygon.add(point);
                    previous = point; pd = d;
                }
                if (polygon.size() >= 3) clipped.add(polygon);
            }
            if (cap.size() >= 3) {
                final Vector3d centre = new Vector3d(); cap.forEach(centre::add); centre.div(cap.size());
                final Vector3d u = new Vector3d(Math.abs(plane.normal.x) < .8D ? 1 : 0,
                        Math.abs(plane.normal.x) < .8D ? 0 : 1, 0).cross(plane.normal).normalize();
                final Vector3d v = new Vector3d(plane.normal).cross(u);
                cap.sort(Comparator.comparingDouble(p -> { final Vector3d delta = new Vector3d(p).sub(centre);
                    return Math.atan2(delta.dot(v), delta.dot(u)); }));
                clipped.add(cap);
            }
            if (clipped.size() < 4) return null;
            return new Polyhedron(clipped);
        }
        /** Volume-only bisection needs no cap sorting or first moments. The omitted cap lies
         * on the tetrahedral integration origin's plane and contributes exactly zero. */
        double volumeBelow(final Plane plane) {
            final double[] range = range(plane);
            if (range[1] <= EPS) return moment().volume;
            if (range[0] >= -EPS) return 0D;
            final Vector3d centre = new Vector3d(); int count = 0;
            for (final List<Vector3d> face : faces) for (final Vector3d p : face) { centre.add(p); count++; }
            centre.div(count);
            final Vector3d origin = new Vector3d(plane.normal).mul(plane.offset);
            double sixVolume = 0D;
            for (final List<Vector3d> face : faces) {
                final List<Vector3d> polygon = new ArrayList<>();
                Vector3d previous = face.getLast(); double pd = plane.distance(previous);
                for (final Vector3d point : face) {
                    final double d = plane.distance(point);
                    if ((pd <= 0D) != (d <= 0D)) polygon.add(new Vector3d(previous).lerp(point, pd / (pd - d)));
                    if (d <= 0D) polygon.add(point);
                    previous = point; pd = d;
                }
                if (polygon.size() < 3) continue;
                final Vector3d a = polygon.getFirst();
                for (int i = 1; i + 1 < polygon.size(); i++) {
                    final Vector3d b = polygon.get(i), c = polygon.get(i + 1);
                    final double nx = (b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);
                    final double ny = (b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);
                    final double nz = (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
                    final double outward = nx*(a.x-centre.x)+ny*(a.y-centre.y)+nz*(a.z-centre.z);
                    final double signed = nx*(a.x-origin.x)+ny*(a.y-origin.y)+nz*(a.z-origin.z);
                    sixVolume += outward >= 0D ? signed : -signed;
                }
            }
            return Math.max(0D, sixVolume / 6D);
        }
        Moment moment() {
            if (cachedMoment != null) return cachedMoment;
            if (this == UNIT) return cachedMoment = new Moment(1D, new Vector3d(.5D, .5D, .5D));
            final Vector3d centre = new Vector3d(); int count = 0;
            for (final List<Vector3d> face : faces) for (final Vector3d p : face) { centre.add(p); count++; }
            centre.div(count);
            double volume = 0D; final Vector3d weighted = new Vector3d();
            for (final List<Vector3d> face : faces) {
                final Vector3d a = face.getFirst();
                for (int i = 1; i + 1 < face.size(); i++) {
                    final Vector3d b = face.get(i), c = face.get(i + 1);
                    final double tetra = Math.abs(new Vector3d(a).sub(centre).dot(
                            new Vector3d(b).sub(centre).cross(new Vector3d(c).sub(centre)))) / 6D;
                    volume += tetra;
                    weighted.fma(tetra / 4D, new Vector3d(centre).add(a).add(b).add(c));
                }
            }
            return cachedMoment = new Moment(volume, weighted);
        }
    }
}
