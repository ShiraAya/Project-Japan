package com.sora.pjr.core;

@FunctionalInterface
public interface Terrain {
  Sample sample(int x, int z);

  /** Unsigned distance in blocks to the ocean coastline, capped outside the coastal band. */
  default double coastDistance(int x, int z) { return Double.POSITIVE_INFINITY; }

  /** Source-mapped tidal river/canal water, kept distinct from marine water. */
  default boolean tidalChannel(int x,int z) { return false; }

  /** Weight of a source-mapped channel footprint; one means it owns the bank outline. */
  default double channelAuthority(int x,int z) { return 0; }

  /** Only actual sea-level channel replacements may carve tidal beds and zero-level banks. */
  default double surveyedTidalAuthority(int x,int z) { return channelAuthority(x,z); }

  /** Rivers whose lower reach has a verified replacement footprint in the local survey. */
  default boolean mappedRiver(String name) { return false; }

  /** Signed distance to any mapped water edge (negative inside), for channel beds/banks. */
  default double channelShoreDistance(int x,int z) { return Double.POSITIVE_INFINITY; }

  /** Whether this location originally carried land elevation, rather than a filled water-mask hole. */
  default boolean elevationSupported(int x, int z) { return sample(x,z).land(); }

  record Sample(boolean land, double metres, double coverage) {
    public int ground() {
      return land
          ? Math.max(1, (int) Math.round(metres / Geo.METRES_Y))
          : -(int) Math.round(Geo.vertical(128));
    }
  }
}
