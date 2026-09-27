package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class RiverNetworkPlanTest {
   private static final int NONE = Integer.MIN_VALUE;
   private static final int SIZE = 64;

   @Test
   void noisyProfileNeverFlowsUphillAndKeepsEndpoints() {
      int[] profile = RiverNetworkPlan.monotoneProfile(new int[]{80, 73, 79, 68, 40, 61, 60}, 80, 60);
      assertEquals(80, profile[0]);
      assertEquals(60, profile[profile.length - 1]);
      for (int i = 1; i < profile.length; i++) assertTrue(profile[i] <= profile[i - 1]);
   }

   @Test
   void flatWideChannelHasOneLevelAndRoundedBed() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> 81, (x, z) -> z >= 20 && z <= 40 ? 1 : 0);
      assertEquals(1, plan.reaches().size());
      for (int x = 0; x < SIZE; x++) for (int z = 20; z <= 40; z++) {
         RiverNetworkPlan.Column c = plan.column(x, z);
         assertEquals(80, c.surface());
         assertFalse(c.waterfall());
         assertTrue(c.floor() < c.surface());
      }
      assertEquals(79, plan.column(32, 20).floor());
      assertEquals(74, plan.column(32, 30).floor());
      assertNull(plan.column(32, 19));
   }

   @Test
   void negativeCoordinateSeamsShareSurfaceAndBedWithoutDependingOnBuildOrder() {
      IntBinaryOperator height = (x, z) -> 100 - Math.floorDiv(x, 9) + Math.floorMod(z * 17 + x * 11, 5);
      IntBinaryOperator wet = (x, z) -> z >= 20 && z <= 40 ? 1 : 0;
      RiverNetworkPlan right = plan(0, 0, height, wet);
      RiverNetworkPlan left = plan(-SIZE, 0, height, wet);
      RiverNetworkPlan rebuilt = plan(0, 0, height, wet);
      for (int z = 20; z <= 40; z++) {
         assertEquals(left.column(-1, z).surface(), right.column(0, z).surface(), "surface at " + z);
         assertEquals(left.column(-1, z).floor(), right.column(0, z).floor(), "bed at " + z);
         assertEquals(left.column(-1, z).visualSurface(), right.column(0, z).visualSurface(), "fall at " + z);
      }
      for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
         assertEquals(right.column(x, z), rebuilt.column(x, z));
      }
   }

   @Test
   void cliffCreatesAReceivingColumnInsteadOfAnUphillWaterStep() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> x < 32 ? 100 : 70,
         (x, z) -> z >= 28 && z <= 36 ? 1 : 0);
      boolean fall = false;
      int previous = Integer.MAX_VALUE;
      for (int x = 0; x < SIZE; x++) {
         RiverNetworkPlan.Column c = plan.column(x, 32);
         assertTrue(c.surface() <= previous, "uphill at " + x);
         previous = c.surface();
         if (c.waterfall()) {
            fall = true;
            assertTrue(c.fallTop() > c.surface());
            assertTrue(c.floor() < c.surface());
         }
      }
      assertTrue(fall);
      assertEquals(100, plan.column(0, 32).surface());
      assertEquals(70, plan.column(63, 32).surface());
   }

   @Test
   void originalAndExpandedWidthsUseTheSameChannelLevel() {
      RiverNetworkPlan narrow = plan(0, 0, (x, z) -> z == 32 ? 80 : 110,
         (x, z) -> z == 32 ? 1 : 0);
      RiverNetworkPlan wide = plan(0, 0, (x, z) -> z == 32 ? 80 : 110,
         (x, z) -> z == 32 ? 1 : z >= 24 && z <= 40 ? 2 : 0);
      for (int x = 0; x < SIZE; x++) for (int z = 24; z <= 40; z++) {
         assertEquals(narrow.column(x, 32).surface(), wide.column(x, z).surface());
      }
   }

   @Test
   void dryGapsAndIslandsAreNotFilledByProfileExtrusion() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> 80,
         (x, z) -> z >= 20 && z <= 40 && !(x >= 25 && x <= 35 && z >= 28 && z <= 32) ? 1 : 0);
      assertNull(plan.column(30, 30));
      assertNotNull(plan.column(30, 27));
      assertEquals(80, plan.column(36, 30).surface());
   }

   @Test
   void nearbyDisconnectedChannelsDoNotShareElevations() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> z < 30 ? 100 : 60,
         (x, z) -> z >= 20 && z <= 24 || z >= 32 && z <= 36 ? 1 : 0);
      for (int x = 0; x < SIZE; x++) {
         assertEquals(100, plan.column(x, 22).surface());
         assertEquals(60, plan.column(x, 34).surface());
         assertNull(plan.column(x, 28));
      }
   }

   @Test
   void hairpinFollowsTheMappedChannelInsteadOfCuttingAcrossTheMeander() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> {
         int distance = x <= 16 ? z - 10 : z >= 44 ? 37 + x - 13 : 71 + 47 - z;
         return 160 - distance / 3;
      }, (x, z) -> (x >= 10 && x <= 16 || x >= 44 && x <= 50) && z >= 10 && z <= 50
         || x >= 10 && x <= 50 && z >= 44 && z <= 50 ? 1 : 0);
      assertNull(plan.column(30, 25));
      int previous = Integer.MAX_VALUE;
      for (int step = 0; step <= 108; step++) {
         int x = step <= 37 ? 13 : step <= 71 ? 13 + step - 37 : 47;
         int z = step <= 37 ? 10 + step : step <= 71 ? 47 : 47 - (step - 71);
         RiverNetworkPlan.Column column = plan.column(x, z);
         assertNotNull(column);
         assertTrue(column.surface() <= previous, "hairpin uphill at " + x + "," + z);
         previous = column.surface();
      }
   }

   @Test
   void lakeIsAnExactAnchorEvenAtOddElevation() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> x < 40 ? 90 : 73,
         (x, z) -> z >= 28 && z <= 36 ? (x < 40 ? 1 : -1) : 0);
      assertNull(plan.column(40, 32));
      assertEquals(73, plan.column(39, 32).surface());
      for (int x = 0; x < 40; x++) assertTrue(plan.column(x, 32).surface() >= 73);
   }

   @Test
   void expandedOnlySeamUsesBothSidesOfTheBoundary() {
      IntBinaryOperator terrain = (x, z) -> 90 - Math.floorDiv(x, 3);
      IntBinaryOperator wet = (x, z) -> z >= 20 && z <= 40 ? 2 : 0;
      RiverNetworkPlan left = plan(-SIZE, 0, terrain, wet), right = plan(0, 0, terrain, wet);
      for (int z = 20; z <= 40; z++) assertEquals(left.column(-1, z).surface(), right.column(0, z).surface());
   }

   @Test
   void bankOutsideTheTileStillDeterminesBedDepth() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> 80, (x, z) -> x >= -2 ? 1 : 0);
      assertEquals(77, plan.column(0, 32).floor());
   }

   @Test
   void confluenceStaysConnectedAndBranchesJoinAtOneLevel() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> x < 32 ? 100 - x / 4 : 92 - (x - 32) / 8,
         (x, z) -> Math.abs(z - 32) <= 2 || (x >= 28 && x <= 32 && z <= 32) ? 1 : 0);
      assertNotNull(plan.column(30, 20));
      assertNotNull(plan.column(30, 32));
      int previous = Integer.MAX_VALUE;
      for (int z = 0; z <= 32; z++) {
         int surface = plan.column(30, z).surface();
         assertTrue(surface <= previous, "tributary uphill at " + z);
         previous = surface;
      }
   }

   @Test
   void fourTileCornerUsesTheSameConnectionIncludingVisibleFalls() {
      IntBinaryOperator height = (x, z) -> 100 - Math.floorDiv(x + z, 7);
      IntBinaryOperator wet = (x, z) -> 1;
      RiverNetworkPlan nw = plan(-SIZE, -SIZE, height, wet), ne = plan(0, -SIZE, height, wet);
      RiverNetworkPlan sw = plan(-SIZE, 0, height, wet), se = plan(0, 0, height, wet);
      for (int z = 0; z < SIZE; z++) {
         assertEquals(sw.column(-1, z).visualSurface(), se.column(0, z).visualSurface(), "south " + z);
         assertEquals(nw.column(-1, z - SIZE).visualSurface(), ne.column(0, z - SIZE).visualSurface(), "north " + z);
      }
      for (int x = 0; x < SIZE; x++) {
         assertEquals(ne.column(x, -1).visualSurface(), se.column(x, 0).visualSurface(), "east " + x);
         assertEquals(nw.column(x - SIZE, -1).visualSurface(), sw.column(x - SIZE, 0).visualSurface(), "west " + x);
      }
   }

   @Test
   void interiorTributaryRetainsItsOwnDescendingProfile() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> z < 28 ? 100 + (28 - z) : 100,
         (x, z) -> Math.abs(z - 32) <= 2 || (x >= 28 && x <= 32 && z >= 5 && z <= 32) ? 1 : 0);
      assertTrue(plan.column(30, 7).surface() > plan.column(30, 32).surface());
      int previous = Integer.MAX_VALUE;
      for (int z = 7; z <= 32; z++) {
         int level = plan.column(30, z).surface();
         assertTrue(level <= previous, "interior tributary uphill at " + z);
         previous = level;
      }
   }

   @Test
   void slopingChannelHasUniformTransverseSections() {
      RiverNetworkPlan plan = plan(0, 0, (x, z) -> 100 - x / 8,
         (x, z) -> z >= 20 && z <= 40 ? 1 : 0);
      for (int x = 0; x < SIZE; x++) for (int z = 20; z <= 40; z++) {
         assertEquals(plan.column(x, 30).surface(), plan.column(x, z).surface(), "cross-section " + x + "," + z);
      }
   }

   private static RiverNetworkPlan plan(int minX, int minZ, IntBinaryOperator height, IntBinaryOperator mask) {
      int halo = RiverNetworkPlan.HALO, side = SIZE + 2 * halo;
      int[] terrain = new int[side * side], external = new int[side * side];
      boolean[] water = new boolean[side * side], original = new boolean[side * side];
      Arrays.fill(external, NONE);
      for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
         int i = z * side + x, wx = minX + x - halo, wz = minZ + z - halo;
         terrain[i] = height.applyAsInt(wx, wz);
         int kind = mask.applyAsInt(wx, wz);
         water[i] = kind > 0;
         original[i] = kind == 1;
         if (kind < 0) external[i] = terrain[i];
      }
      return RiverNetworkPlan.build(minX, minZ, SIZE, halo, terrain, water, original, external);
   }
}
