package org.osm2world.texset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.osm2world.scene.material.RuntimeTexture;
import org.osm2world.scene.material.TextureDataDimensions;

/**
 * An atlas page of a texture set, held as the bytes of its {@code web} derivative (WebP).
 * Decoding needs an ImageIO reader for WebP on the classpath (the JVM build adds one).
 */
public class TexsetPageTexture extends RuntimeTexture {

	private final String name;
	private final byte[] data;

	public TexsetPageTexture(String name, byte[] data) {
		super(new TextureDataDimensions(1, 1), Wrap.CLAMP, null);
		this.name = name;
		this.data = data;
	}

	/** true if the page can be decoded here, checked before use so that a broken page falls back to T0 */
	static boolean decodes(byte[] data) {
		try {
			return ImageIO.read(new ByteArrayInputStream(data)) != null;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	@Override
	public BufferedImage createBufferedImage() {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
			if (image == null) {
				throw new IOException("no image reader for " + name + " (WebP support missing?)");
			}
			return image;
		} catch (IOException e) {
			throw new Error("Could not decode texture set page " + name, e);
		}
	}

	@Override
	public String toString() {
		return "TexsetPageTexture(" + name + ")";
	}

	@Override
	public final boolean equals(Object o) {
		return o instanceof TexsetPageTexture that && name.equals(that.name) && Arrays.equals(data, that.data);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

}
