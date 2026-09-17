package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Objects;

public final class OsmWaterFeature {
   private static final double LINE_HALF_WIDTH_BLOCKS = 0.5;
   private static final double WIDTH_RESPONSE_REFERENCE_BLOCKS = 4.0;
   private final long featureId;
   private final boolean lineGeometry;
   private final boolean pointGeometry;
   private final boolean oceanHint;
   private final OsmWaterKind kind;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;
   private final double riverWidthAtScaleOne;

   public OsmWaterFeature(long featureId, boolean lineGeometry, boolean oceanHint, double[][] longitudes, double[][] latitudes) {
      this(featureId, lineGeometry, oceanHint, OsmWaterKind.UNKNOWN, longitudes, latitudes);
   }

   public OsmWaterFeature(
      long featureId, boolean lineGeometry, boolean oceanHint, OsmWaterKind kind, double[][] longitudes, double[][] latitudes
   ) {
      this(featureId, lineGeometry, false, oceanHint, kind, longitudes, latitudes);
   }

   OsmWaterFeature(
      long featureId,
      boolean lineGeometry,
      boolean pointGeometry,
      boolean oceanHint,
      OsmWaterKind kind,
      double[][] longitudes,
      double[][] latitudes
   ) {
      this.featureId = featureId;
      this.lineGeometry = lineGeometry;
      this.pointGeometry = pointGeometry;
      if (lineGeometry && pointGeometry) {
         throw new IllegalArgumentException("Water feature cannot be both a line and a point");
      }
      this.kind = Objects.requireNonNullElse(kind, OsmWaterKind.UNKNOWN);
      this.oceanHint = oceanHint || this.kind.ocean();
      this.longitudes = copyParts(Objects.requireNonNull(longitudes, "longitudes"));
      this.latitudes = copyParts(Objects.requireNonNull(latitudes, "latitudes"));
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length == 0) {
         throw new IllegalArgumentException("Water feature requires matching geometry parts");
      } else {
         double lowLon = Double.POSITIVE_INFINITY;
         double highLon = Double.NEGATIVE_INFINITY;
         double lowLat = Double.POSITIVE_INFINITY;
         double highLat = Double.NEGATIVE_INFINITY;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            int minPoints = this.pointGeometry ? 1 : this.lineGeometry ? 2 : 4;
            if (lonPart.length != latPart.length || lonPart.length < minPoints) {
               throw new IllegalArgumentException("Water feature part has invalid point count");
            }

            for (int point = 0; point < lonPart.length; point++) {
               double lon = lonPart[point];
               double lat = latPart[point];
               lowLon = Math.min(lowLon, lon);
               highLon = Math.max(highLon, lon);
               lowLat = Math.min(lowLat, lat);
               highLat = Math.max(highLat, lat);
            }
         }

         this.minLon = lowLon;
         this.maxLon = highLon;
         this.minLat = lowLat;
         this.maxLat = highLat;
         this.riverWidthAtScaleOne = this.lineGeometry || this.pointGeometry ? 1.0 : this.estimatePolygonWidthAtScaleOne();
      }
   }

   public static OsmWaterFeature waterfallMarker(long featureId, double longitude, double latitude) {
      return new OsmWaterFeature(
         featureId,
         false,
         true,
         false,
         OsmWaterKind.WATERFALL,
         new double[][]{{longitude}},
         new double[][]{{latitude}}
      );
   }

   public long featureId() {
      return this.featureId;
   }

   public boolean lineGeometry() {
      return this.lineGeometry;
   }

   public boolean pointGeometry() {
      return this.pointGeometry;
   }

   public boolean waterfallMarker() {
      return this.pointGeometry && this.kind == OsmWaterKind.WATERFALL;
   }

   public boolean oceanHint() {
      return this.oceanHint;
   }

   public OsmWaterKind kind() {
      return this.kind;
   }

   public boolean flowingWater() {
      return !this.pointGeometry && (this.lineGeometry || this.kind.flowing());
   }

   public int partCount() {
      return this.longitudes.length;
   }

   public int pointCount(int partIndex) {
      return this.longitudes[partIndex].length;
   }

   public double lonAt(int partIndex, int pointIndex) {
      return this.longitudes[partIndex][pointIndex];
   }

   public double latAt(int partIndex, int pointIndex) {
      return this.latitudes[partIndex][pointIndex];
   }

   public double minLon() {
      return this.minLon;
   }

   public double maxLon() {
      return this.maxLon;
   }

   public double minLat() {
      return this.minLat;
   }

   public double maxLat() {
      return this.maxLat;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsBlock(int blockX, int blockZ, double worldScale) {
      return this.containsBlock(blockX, blockZ, worldScale, 1.0);
   }

   public boolean containsBlock(int blockX, int blockZ, double worldScale, double riverWidthScale) {
      return this.containsWorldPosition(blockX, blockZ, worldScale, riverWidthScale);
   }

   /**
    * Tests a world-space position against this water feature. Flowing polygon
    * features receive the same lateral expansion as line rivers, so Overture's
    * {@code riverbank} polygons respond to the river width setting as well.
    */
   public boolean containsWorldPosition(double blockX, double blockZ, double worldScale, double riverWidthScale) {
      if (worldScale <= 0.0) {
         return false;
      } else if (this.pointGeometry) {
         return false;
      } else if (this.lineGeometry) {
         return this.touchesBlockLine(blockX, blockZ, worldScale, this.effectiveRiverWidthScale(worldScale, riverWidthScale));
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double longitude = blockX / blocksPerDegree;
         double latitude = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.containsLonLat(longitude, latitude)
            || this.flowingWater() && this.touchesBlockPolygon(blockX, blockZ, worldScale, riverWidthScale);
      }
   }

   /**
    * Converts the user-selected maximum scale to a feature-specific scale.
    * Small streams receive a restrained increase, while broad river polygons
    * approach the requested factor without the unbounded growth of L^k.
    */
   public double effectiveRiverWidthScale(double worldScale, double requestedScale) {
      if (!this.flowingWater()) {
         return 1.0;
      }

      double requested = Math.max(1.0, requestedScale);
      double baseWidth = this.riverWidthBlocks(worldScale);
      double response = baseWidth / (baseWidth + WIDTH_RESPONSE_REFERENCE_BLOCKS);
      return 1.0 + (requested - 1.0) * response;
   }

   /** Returns the lateral buffer required to widen a flowing polygon. */
   public double riverWidthExpansionBlocks(double worldScale, double requestedScale) {
      if (!this.flowingWater() || this.lineGeometry) {
         return LINE_HALF_WIDTH_BLOCKS * Math.max(0.0, this.effectiveRiverWidthScale(worldScale, requestedScale) - 1.0);
      }

      return 0.5 * this.riverWidthBlocks(worldScale) * Math.max(0.0, this.effectiveRiverWidthScale(worldScale, requestedScale) - 1.0);
   }

   public boolean containsLonLat(double lon, double lat) {
      if (this.lineGeometry || this.pointGeometry || lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
         return false;
      } else {
         boolean inside = false;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            int points = lonPart.length;

            for (int i = 0, j = points - 1; i < points; j = i++) {
               double lonA = lonPart[i];
               double latA = latPart[i];
               double lonB = lonPart[j];
               double latB = latPart[j];
               if ((latA > lat) != (latB > lat)) {
                  double crossLon = (lonB - lonA) * (lat - latA) / (latB - latA) + lonA;
                  if (lon <= crossLon) {
                     inside = !inside;
                  }
               }
            }
         }

         return inside;
      }
   }

   private boolean touchesBlockLine(double blockX, double blockZ, double worldScale, double riverWidthScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double queryX = blockX;
      double queryZ = blockZ;
      double halfWidth = LINE_HALF_WIDTH_BLOCKS * Math.max(1.0, riverWidthScale);
      double maxDistanceSq = halfWidth * halfWidth + 1.0E-6;

      for (int part = 0; part < this.longitudes.length; part++) {
         double[] lonPart = this.longitudes[part];
         double[] latPart = this.latitudes[part];

         for (int point = 1; point < lonPart.length; point++) {
            double startX = lonPart[point - 1] * blocksPerDegree;
            double startZ = EarthProjection.latToBlockZ(latPart[point - 1], worldScale);
            double endX = lonPart[point] * blocksPerDegree;
            double endZ = EarthProjection.latToBlockZ(latPart[point], worldScale);
            if (distanceToSegmentSq(queryX, queryZ, startX, startZ, endX, endZ) <= maxDistanceSq) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean touchesBlockPolygon(double blockX, double blockZ, double worldScale, double riverWidthScale) {
      double expansion = this.riverWidthExpansionBlocks(worldScale, riverWidthScale);
      if (expansion <= 0.0) {
         return false;
      }

      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double maxDistanceSq = expansion * expansion + 1.0E-6;
      for (int part = 0; part < this.longitudes.length; part++) {
         double[] lonPart = this.longitudes[part];
         double[] latPart = this.latitudes[part];
         int points = lonPart.length;
         for (int point = 0; point < points; point++) {
            int nextPoint = (point + 1) % points;
            double startX = lonPart[point] * blocksPerDegree;
            double startZ = EarthProjection.latToBlockZ(latPart[point], worldScale);
            double endX = lonPart[nextPoint] * blocksPerDegree;
            double endZ = EarthProjection.latToBlockZ(latPart[nextPoint], worldScale);
            if (distanceToSegmentSq(blockX, blockZ, startX, startZ, endX, endZ) <= maxDistanceSq) {
               return true;
            }
         }
      }

      return false;
   }

   private double riverWidthBlocks(double worldScale) {
      return Math.max(1.0, this.riverWidthAtScaleOne / Math.max(1.0E-4, worldScale));
   }

   private double estimatePolygonWidthAtScaleOne() {
      double minX = Double.POSITIVE_INFINITY;
      double maxX = Double.NEGATIVE_INFINITY;
      double minZ = Double.POSITIVE_INFINITY;
      double maxZ = Double.NEGATIVE_INFINITY;
      double area = 0.0;
      double perimeter = 0.0;
      double blocksPerDegree = EarthProjection.blocksPerDegree(1.0);

      for (int part = 0; part < this.longitudes.length; part++) {
         double[] longitudes = this.longitudes[part];
         double[] latitudes = this.latitudes[part];
         for (int point = 0; point < longitudes.length; point++) {
            int next = (point + 1) % longitudes.length;
            double x0 = longitudes[point] * blocksPerDegree;
            double z0 = EarthProjection.latToBlockZ(latitudes[point], 1.0);
            double x1 = longitudes[next] * blocksPerDegree;
            double z1 = EarthProjection.latToBlockZ(latitudes[next], 1.0);
            minX = Math.min(minX, x0);
            maxX = Math.max(maxX, x0);
            minZ = Math.min(minZ, z0);
            maxZ = Math.max(maxZ, z0);
            area += Math.abs(x0 * z1 - x1 * z0) * 0.5;
            perimeter += Math.hypot(x1 - x0, z1 - z0);
         }
      }

      if (!(perimeter > 1.0E-6) || !(area > 1.0E-6)) {
         return 1.0;
      }

      double boundingWidth = Math.min(maxX - minX, maxZ - minZ);
      return Math.max(1.0, Math.min(boundingWidth, 4.0 * area / perimeter));
   }

   private static double distanceToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
      double dx = bx - ax;
      double dz = bz - az;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq <= 1.0E-9) {
         double distX = px - ax;
         double distZ = pz - az;
         return distX * distX + distZ * distZ;
      } else {
         double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
         t = Math.max(0.0, Math.min(1.0, t));
         double projX = ax + t * dx;
         double projZ = az + t * dz;
         double distX = px - projX;
         double distZ = pz - projZ;
         return distX * distX + distZ * distZ;
      }
   }

   private static double[][] copyParts(double[][] input) {
      double[][] copy = new double[input.length][];

      for (int i = 0; i < input.length; i++) {
         copy[i] = input[i].clone();
      }

      return copy;
   }
}
