package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeronauticsPackedCoordinateCompatibilityTest {
   @Test
   void denseBlockPosReplacementRequiresExplicitLaunchOptIn() {
      // Create Aeronautics/Sable stores and synchronizes packed BlockPos values
      // for physics sub-levels. A normal Tellus installation must preserve the
      // vanilla encoding used by those mods.
      assertFalse(HighYPackedCoordinateProfile.isEnabledForRequestedProfile(null));
      assertFalse(HighYPackedCoordinateProfile.isEnabledForRequestedProfile(""));
      assertFalse(HighYPackedCoordinateProfile.isEnabledForRequestedProfile("  "));
      assertFalse(HighYPackedCoordinateProfile.isEnabledForRequestedProfile("another_profile"));
   }

   @Test
   void denseBlockPosReplacementRemainsAvailableAsAnExplicitExperimentalMode() {
      assertTrue(
         HighYPackedCoordinateProfile.isEnabledForRequestedProfile(HighYPackedCoordinateProfile.PROFILE_ID)
      );
      assertTrue(
         HighYPackedCoordinateProfile.isEnabledForRequestedProfile(" " + HighYPackedCoordinateProfile.PROFILE_ID + " ")
      );
   }
}
