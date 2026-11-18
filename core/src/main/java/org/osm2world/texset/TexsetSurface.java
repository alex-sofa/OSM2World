package org.osm2world.texset;

import java.util.ArrayList;
import java.util.List;

import org.osm2world.map_data.data.MapArea;
import org.osm2world.map_data.data.MapNode;
import org.osm2world.map_elevation.data.EleConnector;
import org.osm2world.math.VectorXYZ;
import org.osm2world.math.VectorXZ;
import org.osm2world.math.shapes.TriangleXYZ;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.mesh.Mesh;
import org.osm2world.scene.mesh.TriangleGeometry;
import org.osm2world.world.data.AreaWorldObject;
import org.osm2world.world.data.NodeWorldObject;
import org.osm2world.world.data.WorldObject;

/**
 * A textured quad from a texture set, added as an extra representation of the OSM element it belongs to
 * (the building part for walls, the node for node faces). The quad lies slightly in front of the surface.
 */
abstract class TexsetSurface implements WorldObject {

	final String key;
	private final List<VectorXYZ> corners;
	private final List<VectorXZ> texCoords;
	private final VectorXYZ frontNormal;
	private final Material material;

	/**
	 * @param corners  lower-left, lower-right, upper-right, upper-left as seen from the front
	 * @param texCoords  OSM2World texture coordinates (origin lower left) of the corners
	 */
	TexsetSurface(String key, List<VectorXYZ> corners, List<VectorXZ> texCoords, VectorXYZ frontNormal, Material material) {
		this.key = key;
		this.corners = corners;
		this.texCoords = texCoords;
		this.frontNormal = frontNormal;
		this.material = material;
	}

	@Override
	public List<Mesh> buildMeshes() {

		int[] order = {0, 1, 2, 0, 2, 3};

		/* the triangle winding must make the normal point to the front */
		var test = new TriangleXYZ(corners.get(0), corners.get(1), corners.get(2));
		if (test.getNormal().dot(frontNormal) < 0) {
			order = new int[] {0, 2, 1, 0, 3, 2};
		}

		List<VectorXYZ> vs = new ArrayList<>(6);
		List<VectorXZ> tcs = new ArrayList<>(6);
		for (int i : order) {
			vs.add(corners.get(i));
			tcs.add(texCoords.get(i));
		}

		var builder = new TriangleGeometry.Builder(material.textureLayers().size(), null, Material.Interpolation.FLAT);
		builder.addTriangleVs(vs, material.textureLayers().isEmpty() ? List.of() : List.of(tcs));
		return List.of(new Mesh(builder.build(), material));

	}

	@Override
	public Iterable<EleConnector> getEleConnectors() {
		return List.of();
	}

	@Override
	public String toString() {
		return "TexsetSurface(" + key + ")";
	}

	static class Wall extends TexsetSurface implements AreaWorldObject {

		private final MapArea area;

		Wall(MapArea area, String key, List<VectorXYZ> corners, List<VectorXZ> texCoords, VectorXYZ frontNormal,
				Material material) {
			super(key, corners, texCoords, frontNormal, material);
			this.area = area;
		}

		@Override
		public MapArea getPrimaryMapElement() {
			return area;
		}

	}

	static class NodeFace extends TexsetSurface implements NodeWorldObject {

		private final MapNode node;

		NodeFace(MapNode node, String key, List<VectorXYZ> corners, List<VectorXZ> texCoords, VectorXYZ frontNormal,
				Material material) {
			super(key, corners, texCoords, frontNormal, material);
			this.node = node;
		}

		@Override
		public MapNode getPrimaryMapElement() {
			return node;
		}

	}

}
