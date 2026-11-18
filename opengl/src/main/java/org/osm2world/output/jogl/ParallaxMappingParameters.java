package org.osm2world.output.jogl;

import org.osm2world.conversion.O2WConfig;

/**
 * Settings for the optional parallax occlusion mapping (POM) in {@link DefaultShader}.
 * POM uses the displacement texture of a material's first texture layer to fake relief in the surface.
 * It is disabled by default, so rendering is unchanged unless it is explicitly requested.
 * <p>
 * Configuration properties (see {@link #fromConfig(O2WConfig)}):
 * <ul>
 * <li> <code>useParallaxMapping</code>: enables POM (default: false)
 * <li> <code>parallaxHeightScale</code>: depth of the relief in metres, for a displacement value of 0 (default: 0.05)
 * <li> <code>parallaxLayers</code>: maximum number of ray marching steps per fragment (default: 16)
 * <li> <code>parallaxFadeStart</code>: distance from the camera in metres at which the effect starts to fade out
 *      (default: 20)
 * <li> <code>parallaxFadeEnd</code>: distance from the camera in metres beyond which POM is skipped completely
 *      (default: 40)
 * </ul>
 */
public final class ParallaxMappingParameters {

	/** upper limit for {@link #maxLayers}, has to match the loop bound in the fragment shader */
	public static final int MAX_LAYERS_LIMIT = 64;

	/** parameters with POM turned off */
	public static final ParallaxMappingParameters DISABLED = new ParallaxMappingParameters(false, 0.05f, 16, 20, 40);

	public final boolean enabled;

	/** depth of the relief in metres, i.e. how far below the polygon the lowest point of the displacement map lies */
	public final float heightScale;

	/** maximum number of ray marching layers, between 2 and {@link #MAX_LAYERS_LIMIT} */
	public final int maxLayers;

	/** distance from the camera (in metres) at which the effect starts to fade out */
	public final float fadeStart;

	/** distance from the camera (in metres) at which the effect has faded out completely */
	public final float fadeEnd;

	public ParallaxMappingParameters(boolean enabled, float heightScale, int maxLayers,
			float fadeStart, float fadeEnd) {
		this.enabled = enabled;
		this.heightScale = Math.max(0, heightScale);
		this.maxLayers = Math.max(2, Math.min(MAX_LAYERS_LIMIT, maxLayers));
		this.fadeStart = fadeStart;
		this.fadeEnd = Math.max(fadeStart, fadeEnd);
	}

	/**
	 * reads the parallax mapping settings from a configuration.
	 * Returns {@link #DISABLED} unless <code>useParallaxMapping</code> is set to true.
	 */
	public static ParallaxMappingParameters fromConfig(O2WConfig config) {
		if (!config.getBoolean("useParallaxMapping", false)) {
			return DISABLED;
		}
		return new ParallaxMappingParameters(true,
				config.getFloat("parallaxHeightScale", 0.05f),
				config.getInt("parallaxLayers", 16),
				config.getFloat("parallaxFadeStart", 20f),
				config.getFloat("parallaxFadeEnd", 40f));
	}

}
