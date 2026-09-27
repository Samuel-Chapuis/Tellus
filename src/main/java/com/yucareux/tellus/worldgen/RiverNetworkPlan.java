package com.yucareux.tellus.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * An immutable, world-anchored river plan. Like Streams, it joins channel
 * sections before carving columns, and separates level reaches from falls.
 * Overture supplies the channel footprint instead of procedural meanders.
 *
 * Adjacent plans read the SAME two-sided section at their shared boundary.
 * Consequently neither chunk order nor a caller's sampling window determines
 * the elevation of a connection. All heights are inclusive block Y values.
 */
public final class RiverNetworkPlan {
   public static final int TILE_SIZE = 256;
   public static final int HALO = 16;
   public static final int VERSION = 1;
   private static final int NONE = Integer.MIN_VALUE;
   private static final int[] DX = {1, 0, -1, 0};
   private static final int[] DZ = {0, 1, 0, -1};
   private final int minX;
   private final int minZ;
   private final int size;
   private final int[] surface;
   private final int[] floor;
   private final int[] fallTop;
   private final int[] reach;
   private final List<RiverReach> reaches;

   private RiverNetworkPlan(int minX, int minZ, int size, int[] surface, int[] floor,
      int[] fallTop, int[] reach, List<RiverReach> reaches) {
      this.minX = minX;
      this.minZ = minZ;
      this.size = size;
      this.surface = surface;
      this.floor = floor;
      this.fallTop = fallTop;
      this.reach = reach;
      this.reaches = List.copyOf(reaches);
   }

   public record RiverReach(int id, int surface, int columns) {}
   public record Column(int surface, int floor, int fallTop, int reach) {
      public boolean waterfall() { return fallTop != NONE; }
      public int visualSurface() { return waterfall() ? fallTop : surface; }
   }

   public List<RiverReach> reaches() { return this.reaches; }

   public Column column(int x, int z) {
      int localX = x - this.minX;
      int localZ = z - this.minZ;
      if (localX < 0 || localZ < 0 || localX >= this.size || localZ >= this.size) return null;
      int i = localZ * this.size + localX;
      return this.surface[i] == NONE ? null : new Column(this.surface[i], this.floor[i], this.fallTop[i], this.reach[i]);
   }

   /** Arrays include a halo on every side; externalSurface is NONE except in lakes/ocean. */
   static RiverNetworkPlan build(int minX, int minZ, int size, int halo, int[] terrain,
      boolean[] water, boolean[] original, int[] externalSurface) {
      int side = size + 2 * halo;
      int length = Math.multiplyExact(side, side);
      if (size < 8 || halo < 8 || terrain.length != length || water.length != length
         || original.length != length || externalSurface.length != length) {
         throw new IllegalArgumentException("Invalid river planning grid");
      }
      return new Builder(minX, minZ, size, halo, terrain, water, original, externalSurface).build();
   }

   private record Route(int cell, long cost) {}
   private record Section(int[] cells, int level) {}

   private static final class Builder {
      final int minX, minZ, size, halo, side, area;
      final int[] terrain, external;
      final boolean[] water, original;
      final boolean[] axis;
      final int[] depth, parent, component, surface, boundary, axisDistance;
      final long[] distance;
      final List<Section> sections = new ArrayList<>();
      final int[] queue;

      Builder(int minX, int minZ, int size, int halo, int[] terrain, boolean[] water,
         boolean[] original, int[] external) {
         this.minX = minX; this.minZ = minZ; this.size = size; this.halo = halo;
         this.side = size + 2 * halo; this.area = size * size;
         this.terrain = terrain; this.water = water; this.original = original; this.external = external;
         this.axis = new boolean[area];
         this.depth = new int[area]; this.parent = new int[area]; this.component = new int[area];
         this.surface = new int[area]; this.boundary = new int[area]; this.distance = new long[area];
         this.queue = new int[area];
         this.axisDistance = new int[area];
         Arrays.fill(axisDistance, Integer.MAX_VALUE);
         Arrays.fill(component, -1); Arrays.fill(surface, NONE); Arrays.fill(boundary, NONE);
         Arrays.fill(parent, -1); Arrays.fill(distance, Long.MAX_VALUE);
      }

      int input(int cell) { return (cell / size + halo) * side + cell % size + halo; }
      int neighbor(int cell, int d) {
         int x = cell % size + DX[d], z = cell / size + DZ[d];
         return x < 0 || z < 0 || x >= size || z >= size ? -1 : z * size + x;
      }
      boolean wet(int cell) { return water[input(cell)]; }

      RiverNetworkPlan build() {
         computeDepth();
         for (int face = 0; face < 4; face++) boundarySections(face);
         // Named lakes and oceans are fixed hydraulic anchors, including those
         // inside a plan. No local river pass is allowed to lower them.
         for (int c = 0; c < area; c++) if (wet(c)) {
            int p = input(c), level = Integer.MAX_VALUE;
            for (int d = 0; d < 4; d++) {
               int h = external[p + DZ[d] * side + DX[d]];
               if (h != NONE) level = Math.min(level, h);
            }
            if (level != Integer.MAX_VALUE) {
               sections.add(new Section(new int[]{c}, level));
               boundary[c] = level;
            }
         }
         int id = 0;
         for (int c = 0; c < area; c++) if (wet(c) && component[c] < 0) {
            int count = floodComponent(c, id++);
            planComponent(Arrays.copyOf(queue, count));
         }
         return rasterize();
      }

      void computeDepth() {
         int head = 0, tail = 0;
         int[] distances = new int[water.length], pending = new int[water.length];
         Arrays.fill(distances, 6);
         // Include the halo so a bank just outside a tile still shapes its bed.
         for (int z = 1; z < side - 1; z++) for (int x = 1; x < side - 1; x++) {
            int p = z * side + x;
            if (!water[p]) continue;
            for (int d = 0; d < 4; d++) if (!water[p + DZ[d] * side + DX[d]]) {
               distances[p] = 1; pending[tail++] = p; break;
            }
         }
         while (head < tail) {
            int p = pending[head++];
            if (distances[p] >= 5) continue;
            for (int d = 0; d < 4; d++) {
               int x = p % side + DX[d], z = p / side + DZ[d];
               if (x < 0 || z < 0 || x >= side || z >= side) continue;
               int n = z * side + x;
               if (water[n] && distances[n] > distances[p] + 1) {
                  distances[n] = distances[p] + 1; pending[tail++] = n;
               }
            }
         }
         for (int c = 0; c < area; c++) depth[c] = distances[input(c)];
      }

      int faceCell(int face, int along) {
         return switch (face) {
            case 0 -> along * size;
            case 1 -> along * size + size - 1;
            case 2 -> along;
            default -> (size - 1) * size + along;
         };
      }

      boolean crosses(int face, int along) {
         int p = input(faceCell(face, along));
         int delta = switch (face) { case 0 -> -1; case 1 -> 1; case 2 -> -side; default -> side; };
         return water[p] && water[p + delta];
      }

      void boundarySections(int face) {
         for (int start = 0; start < size;) {
            if (!crosses(face, start)) { start++; continue; }
            int end = start + 1;
            while (end < size && crosses(face, end)) end++;
            // The seam is between two columns. Both plans sample [-4,+3]
            // relative to this world-space seam, never relative to their edge.
            int[] samples = new int[(end - start) * 8];
            int count = 0;
            int seam = switch (face) { case 0, 2 -> halo; default -> halo + size; };
            for (int a = start; a < end; a++) for (int offset = -4; offset < 4; offset++) {
               int p = face < 2 ? (a + halo) * side + seam + offset : (seam + offset) * side + a + halo;
               if (original[p] && water[p]) samples[count++] = terrain[p];
            }
            if (count == 0) for (int a = start; a < end; a++) for (int offset = -4; offset < 4; offset++) {
               int p = face < 2 ? (a + halo) * side + seam + offset : (seam + offset) * side + a + halo;
               if (water[p]) samples[count++] = terrain[p];
            }
            Arrays.sort(samples, 0, count);
            int level = quantize(samples[(count - 1) / 3]);
            int[] cells = new int[end - start];
            for (int a = start; a < end; a++) {
               int c = faceCell(face, a);
               cells[a - start] = c;
               boundary[c] = level;
               // Keep the immediate connector flat on both sides; a fall must
               // not depend on which side of a planning boundary renders it.
               int inward = switch (face) { case 0 -> 0; case 1 -> 2; case 2 -> 1; default -> 3; };
               int n = neighbor(c, inward);
               if (n >= 0 && wet(n)) boundary[n] = level;
            }
            sections.add(new Section(cells, level));
            start = end;
         }
         // Four plans meeting at a wet vertex must use the same vertex anchor.
         for (int along : new int[]{0, size - 1}) if (crosses(face, along)) {
            int c = faceCell(face, along);
            int vx = c % size == 0 ? halo : halo + size;
            int vz = c / size == 0 ? halo : halo + size;
            int[] samples = new int[64]; int count = 0;
            for (int z = vz - 4; z < vz + 4; z++) for (int x = vx - 4; x < vx + 4; x++) {
               int p = z * side + x;
               if (original[p] && water[p]) samples[count++] = terrain[p];
            }
            if (count > 0) {
               Arrays.sort(samples, 0, count);
               int level = quantize(samples[(count - 1) / 3]);
               int cx = c % size, cz = c / size;
               for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                  int n = (cz == 0 ? dz : cz - dz) * size + (cx == 0 ? dx : cx - dx);
                  if (wet(n)) boundary[n] = level;
               }
            }
         }
      }

      int floodComponent(int seed, int id) {
         int head = 0, tail = 0;
         queue[tail++] = seed; component[seed] = id;
         while (head < tail) {
            int c = queue[head++];
            for (int d = 0; d < 4; d++) {
               int n = neighbor(c, d);
               if (n >= 0 && wet(n) && component[n] < 0) {
                  component[n] = id; queue[tail++] = n;
               }
            }
         }
         return tail;
      }

      void planComponent(int[] cells) {
         int id = component[cells[0]];
         List<Section> ports = sections.stream().filter(s -> component[s.cells()[0]] == id).toList();
         Section outlet = ports.stream().min(Comparator.comparingInt(Section::level)
            .thenComparingInt(s -> s.cells()[0])).orElse(null);
         PriorityQueue<Route> heap = new PriorityQueue<>(Comparator.comparingLong(Route::cost).thenComparingInt(Route::cell));
         if (outlet == null) {
            int lowest = cells[0];
            for (int c : cells) if (original[input(c)] && terrain[input(c)] < terrain[input(lowest)]) lowest = c;
            outlet = new Section(new int[]{lowest}, quantize(localHeight(lowest)));
         }
         for (int c : outlet.cells()) {
            surface[c] = outlet.level(); distance[c] = 0; heap.add(new Route(c, 0));
         }
         axis[representative(outlet)] = true;
         while (!heap.isEmpty()) {
            Route route = heap.remove(); int c = route.cell();
            if (distance[c] != route.cost()) continue;
            for (int d = 0; d < 4; d++) {
               int n = neighbor(c, d);
               if (n < 0 || component[n] != id) continue;
               long cost = route.cost() + 16 + 24 / Math.max(1, depth[n]);
               if (cost < distance[n]) {
                  distance[n] = cost; parent[n] = c; heap.add(new Route(n, cost));
               }
            }
         }
         List<Section> upstream = new ArrayList<>(ports);
         upstream.sort(Comparator.<Section>comparingLong(s -> distance[representative(s)]).reversed()
            .thenComparingInt(s -> s.cells()[0]));
         for (Section port : upstream) trace(representative(port), port.level());
         // A source in the interior has no boundary section. Trace its channel
         // to the existing network, using distance along water (not a straight
         // line across a bend or an island).
         if (ports.size() < 2) {
            int source = cells[0];
            for (int c : cells) if (original[input(c)] && distance[c] > distance[source]) source = c;
            trace(source, Math.max(outlet.level(), quantize(localHeight(source))));
         }
         // Include tributaries whose spring is wholly inside this tile. The
         // spacing scales with channel width so banks are not mistaken for
         // additional tributaries in a broad main stem.
         updateAxisDistance(cells, axisDistance);
         Integer[] candidates = Arrays.stream(cells).boxed()
            .filter(c -> original[input(c)] && axisDistance[c] > Math.max(8, depth[c] * 3))
            .sorted(Comparator.<Integer>comparingLong(c -> distance[c]).reversed().thenComparingInt(c -> c))
            .toArray(Integer[]::new);
         for (int source : candidates) if (axisDistance[source] > Math.max(8, depth[source] * 3)) {
            // Only local centres, not the edge of a wide cross-section.
            boolean centre = true;
            for (int d = 0; d < 4; d++) {
               int n = neighbor(source, d);
               if (n >= 0 && component[n] == id && depth[n] > depth[source]) centre = false;
            }
            int closedSides = 0;
            int p = input(source), radius = Math.min(halo - 1, depth[source] * 2 + 1);
            for (int d = 0; d < 4; d++) for (int step = 1; step <= radius; step++) {
               if (!water[p + step * (DZ[d] * side + DX[d])]) { closedSides++; break; }
            }
            if (centre && closedSides >= 3) {
               trace(source, Math.max(outlet.level(), quantize(localHeight(source))));
               updateAxisDistance(cells, axisDistance);
            }
         }
         // Extrude the planned axis/sections across the mapped channel.
         // Border banks are constraints, not axis seeds: otherwise proximity
         // to an upstream bank creates diagonal ridges across a wide channel.
         int head = 0, tail = 0;
         Arrays.sort(cells);
         for (int c : cells) {
            if (axis[c]) queue[tail++] = c;
            else surface[c] = NONE;
         }
         while (head < tail) {
            int c = queue[head++];
            for (int d = 0; d < 4; d++) {
               int n = neighbor(c, d);
               if (n >= 0 && component[n] == id && surface[n] == NONE) {
                  surface[n] = surface[c]; queue[tail++] = n;
               }
            }
         }
         for (int c : cells) if (boundary[c] != NONE) surface[c] = boundary[c];
      }

      void updateAxisDistance(int[] cells, int[] nearest) {
         // Multi-source Dijkstra permits incremental additions without a
         // fixed-size queue overflowing when an existing distance decreases.
         PriorityQueue<Route> pending = new PriorityQueue<>(Comparator.comparingLong(Route::cost).thenComparingInt(Route::cell));
         for (int c : cells) if (axis[c] && nearest[c] != 0) {
            nearest[c] = 0; pending.add(new Route(c, 0));
         }
         while (!pending.isEmpty()) {
            Route route = pending.remove(); int c = route.cell();
            if (nearest[c] != route.cost()) continue;
            for (int d = 0; d < 4; d++) {
               int n = neighbor(c, d);
               if (n >= 0 && component[n] == component[c] && nearest[n] > nearest[c] + 1) {
                  nearest[n] = nearest[c] + 1; pending.add(new Route(n, nearest[n]));
               }
            }
         }
      }

      int representative(Section section) {
         int best = section.cells()[section.cells().length / 2];
         for (int c : section.cells()) if (depth[c] > depth[best]) best = c;
         return best;
      }

      int localHeight(int c) {
         int p = input(c), count = 0;
         int[] samples = new int[81];
         for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
            int n = p + z * side + x;
            if (original[n] && water[n]) samples[count++] = terrain[n];
         }
         if (count == 0) return terrain[p];
         Arrays.sort(samples, 0, count);
         return samples[(count - 1) / 3];
      }

      void trace(int source, int sourceHeight) {
         int count = 0, c = source;
         while (c >= 0 && surface[c] == NONE) { queue[count++] = c; c = parent[c]; }
         if (c >= 0) axis[c] = true;
         if (count == 0) return;
         int endHeight = c < 0 ? sourceHeight : surface[c];
         // A tributary may join a previously planned, higher branch. Lower the
         // shared downstream path before constructing the tributary profile.
         if (endHeight > sourceHeight) {
            for (int n = c; n >= 0 && surface[n] > sourceHeight; n = parent[n]) surface[n] = sourceHeight;
            endHeight = sourceHeight;
         }
         int[] targets = new int[count + 1];
         for (int i = 0; i < count; i++) targets[i] = localHeight(queue[i]);
         targets[0] = sourceHeight; targets[count] = endHeight;
         int[] levels = monotoneProfile(targets, sourceHeight, endHeight);
         for (int i = 0; i < count; i++) {
            surface[queue[i]] = levels[i];
            axis[queue[i]] = true;
         }
      }

      RiverNetworkPlan rasterize() {
         int[] floor = new int[area], top = new int[area], reach = new int[area];
         Arrays.fill(top, NONE); Arrays.fill(reach, -1);
         for (int c = 0; c < area; c++) if (surface[c] != NONE) {
            floor[c] = surface[c] - Math.max(1, depth[c]);
            int upstream = surface[c];
            for (int d = 0; d < 4; d++) {
               int n = neighbor(c, d);
               if (n >= 0 && surface[n] != NONE) upstream = Math.max(upstream, surface[n]);
            }
            // Leave a receiving column for vanilla falling water; source
            // conversion is disabled here by the consumer of this plan.
            if (upstream > surface[c]) top[c] = upstream;
         }
         List<RiverReach> reaches = new ArrayList<>();
         for (int seed = 0; seed < area; seed++) if (surface[seed] != NONE && reach[seed] < 0) {
            int id = reaches.size(), head = 0, tail = 0;
            queue[tail++] = seed; reach[seed] = id;
            while (head < tail) {
               int c = queue[head++];
               for (int d = 0; d < 4; d++) {
                  int n = neighbor(c, d);
                  if (n >= 0 && reach[n] < 0 && surface[n] == surface[seed]) {
                     reach[n] = id; queue[tail++] = n;
                  }
               }
            }
            reaches.add(new RiverReach(id, surface[seed], tail));
         }
         return new RiverNetworkPlan(minX, minZ, size, surface, floor, top, reach, reaches);
      }
   }

   private static int quantize(int height) { return Math.floorDiv(height, 2) * 2; }

   /** Least-squares non-increasing profile with pinned endpoint elevations. */
   static int[] monotoneProfile(int[] terrain, int upstream, int downstream) {
      if (terrain.length < 2 || upstream < downstream) throw new IllegalArgumentException("Invalid reach endpoints");
      int n = terrain.length, pools = 0;
      double[] sum = new double[n]; int[] count = new int[n];
      for (int i = 0; i < n; i++) {
         sum[pools] = Math.max(downstream, Math.min(upstream, terrain[i])); count[pools++] = 1;
         while (pools > 1 && sum[pools - 2] / count[pools - 2] < sum[pools - 1] / count[pools - 1]) {
            sum[pools - 2] += sum[pools - 1]; count[pools - 2] += count[pools - 1]; pools--;
         }
      }
      int[] result = new int[n]; int cursor = 0;
      for (int p = 0; p < pools; p++) {
         int level = Math.max(downstream, Math.min(upstream, quantize((int)Math.round(sum[p] / count[p]))));
         Arrays.fill(result, cursor, cursor + count[p], level); cursor += count[p];
      }
      result[0] = upstream; result[n - 1] = downstream;
      return result;
   }
}
