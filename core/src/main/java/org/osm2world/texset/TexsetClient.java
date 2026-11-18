package org.osm2world.texset;

import static java.lang.Math.*;
import static org.osm2world.conversion.ConversionLog.LogLevel.WARNING;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import javax.annotation.Nullable;

import org.osm2world.conversion.ConversionLog;
import org.osm2world.conversion.O2WConfig;
import org.osm2world.map_data.data.MapArea;
import org.osm2world.map_data.data.MapData;
import org.osm2world.map_data.data.MapNode;
import org.osm2world.map_elevation.data.EleConnector;
import org.osm2world.math.VectorXYZ;
import org.osm2world.math.VectorXZ;
import org.osm2world.math.geo.LatLon;
import org.osm2world.math.geo.MapProjection;
import org.osm2world.scene.color.Color;
import org.osm2world.scene.material.Material;
import org.osm2world.scene.material.TextureLayer;
import org.osm2world.util.platform.json.JsonUtil;
import org.osm2world.util.platform.uri.LoadUriUtil;
import org.osm2world.world.data.WorldObject;
import org.osm2world.world.modules.building.Building;
import org.osm2world.world.modules.building.BuildingPart;

/**
 * Client for streamed texture sets (format {@code osm3d-texset/0.3}), conformance level 1 (walls) plus node faces.
 * <p>
 * Enabled by the config property {@code texsetUrl} (one or more bootstrap URLs, comma-separated, each serving
 * {@code textures/<region>/latest.json}) and {@code texsetRegion}. It loads latest.json, picks a mirror,
 * loads the manifest and the cell indices (XYZ z16) of the data bounds, and adds a {@link TexsetSurface}
 * for every matching surface:
 * <ul>
 * <li>walls of building parts mapped as ways, matched by the key {@code w<way id>:wall:<node a>-<node b>}
 * (either node order); because the map projection rounds positions, the exact geom_hash cannot be reproduced,
 * so the geometry is checked against the stored {@code edge} with the position tolerance (0.3 m) instead;</li>
 * <li>node faces {@code n<id>:face} (e.g. billboards), placed by their authoritative {@code plane}.</li>
 * </ul>
 * Pages are read from their {@code web} derivative (WebP) and checked against its sha256.
 * Anything unmatched or unavailable keeps OSM2World's own appearance.
 */
public class TexsetClient {

	public static final String FORMAT = "osm3d-texset/0.3";
	private static final int Z = 16;
	private static final double TOLERANCE = 0.3;
	private static final double OFFSET = 0.03;

	private final O2WConfig config;
	private final MapProjection projection;
	private final List<String> bootstrap;
	private final String region;

	private List<String> mirrors = List.of();
	private int mirrorIndex = 0;
	private String release;

	private final Map<String, Material> materialsByPage = new HashMap<>();

	/** adds textured surfaces to the map data if the config enables texture sets; no-op otherwise */
	public static void addTexturedSurfaces(MapData mapData, @Nullable MapProjection projection, O2WConfig config) {
		String url = config.getString("texsetUrl");
		if (url == null || url.isBlank()) return;
		if (projection == null) {
			ConversionLog.log(WARNING, "texture set needs a map projection", null, null);
			return;
		}
		try {
			new TexsetClient(config, projection).run(mapData);
		} catch (Exception e) {
			ConversionLog.log(WARNING, "texture set could not be loaded, keeping the default appearance", e, null);
		}
	}

	TexsetClient(O2WConfig config, MapProjection projection) {
		this.config = config;
		this.projection = projection;
		this.bootstrap = Arrays.stream(config.getString("texsetUrl").split(","))
				.map(String::trim).map(s -> s.endsWith("/") ? s : s + "/").toList();
		this.region = config.getString("texsetRegion", "bodensee");
	}

	/* ------------------------------------------------------------------ loading */

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(String json) throws IOException {
		return JsonUtil.fromJson(json, Map.class);
	}

	/** fetches a path relative to textures/<region>/ from the current mirror, failing over to the next ones */
	private @Nullable byte[] fetchFromMirror(String path) {
		for (int attempt = 0; attempt < mirrors.size(); attempt++) {
			String base = mirrors.get((mirrorIndex + attempt) % mirrors.size());
			try {
				byte[] result = LoadUriUtil.fetchBinary(URI.create(base + "textures/" + region + "/" + path));
				mirrorIndex = (mirrorIndex + attempt) % mirrors.size();
				return result;
			} catch (IOException e) {
				// 404, network error, 429 or 5xx: the URI API does not tell them apart, try the next mirror
			}
		}
		return null; // treated as "no texture here"
	}

	private @Nullable Map<String, Object> fetchJson(String path) throws IOException {
		byte[] data = fetchFromMirror(path);
		return data == null ? null : parse(new String(data, StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private void run(MapData mapData) throws IOException {

		Map<String, Object> latest = null;
		for (String b : bootstrap) {
			try {
				latest = parse(LoadUriUtil.fetchText(URI.create(b + "textures/" + region + "/latest.json")));
				break;
			} catch (IOException e) {
				// try the next bootstrap URL
			}
		}
		if (latest == null || !FORMAT.equals(latest.get("format"))) {
			ConversionLog.log(WARNING, "texture set: no latest.json of format " + FORMAT, null, null);
			return;
		}

		release = (String) latest.get("release");
		mirrors = pickMirrorOrder((List<Map<String, Object>>) latest.get("mirrors"));

		Map<String, Object> manifest = fetchJson((String) latest.get("manifest"));
		if (manifest == null || !FORMAT.equals(manifest.get("format"))) {
			ConversionLog.log(WARNING, "texture set: manifest missing or of another format", null, null);
			return;
		}

		/* cells of the data bounds */

		var bounds = mapData.getDataBoundary();
		LatLon nw = projection.toLatLon(new VectorXZ(bounds.minX, bounds.maxZ));
		LatLon se = projection.toLatLon(new VectorXZ(bounds.maxX, bounds.minZ));
		int[] c0 = cellOf(nw.lon, nw.lat), c1 = cellOf(se.lon, se.lat);

		Map<String, Map<String, Object>> surfaces = new LinkedHashMap<>(); // key -> surface record
		Map<String, Map<String, Object>> pageOfSurface = new HashMap<>();

		for (int x = c0[0]; x <= c1[0]; x++) {
			for (int y = c0[1]; y <= c1[1]; y++) {
				Map<String, Object> cell = fetchJson(release + "/cells/" + Z + "/" + x + "/" + y + ".json");
				if (cell == null || !FORMAT.equals(cell.get("format"))) continue;
				var pages = (List<Map<String, Object>>) cell.get("pages");
				for (var object : (List<Map<String, Object>>) cell.get("objects")) {
					for (var surface : (List<Map<String, Object>>) object.get("surfaces")) {
						String key = (String) surface.get("key");
						surfaces.putIfAbsent(key, surface);
						pageOfSurface.putIfAbsent(key, pages.get(((Number) surface.get("page")).intValue()));
					}
				}
			}
		}

		if (surfaces.isEmpty()) return;

		int added = addWalls(mapData, surfaces, pageOfSurface) + addNodeFaces(mapData, surfaces, pageOfSurface);
		ConversionLog.log(WARNING, "texture set " + release + ": " + added + " of " + surfaces.size()
				+ " surfaces matched", null, null);

	}

	/** weighted random first mirror, the others as failover in their listed order */
	private static List<String> pickMirrorOrder(List<Map<String, Object>> mirrorRecords) {
		if (mirrorRecords == null || mirrorRecords.isEmpty()) return List.of();
		double total = mirrorRecords.stream().mapToDouble(TexsetClient::weight).sum();
		double r = new Random().nextDouble() * total;
		int first = 0;
		for (int i = 0; i < mirrorRecords.size(); i++) {
			r -= weight(mirrorRecords.get(i));
			if (r <= 0) { first = i; break; }
		}
		List<String> result = new ArrayList<>();
		for (int i = 0; i < mirrorRecords.size(); i++) {
			String url = (String) mirrorRecords.get((first + i) % mirrorRecords.size()).get("url");
			result.add(url.endsWith("/") ? url : url + "/");
		}
		return result;
	}

	private static double weight(Map<String, Object> mirror) {
		return mirror.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
	}

	static int[] cellOf(double lon, double lat) {
		double n = pow(2, Z);
		int x = (int) floor((lon + 180) / 360 * n);
		double latRad = toRadians(lat);
		double asinhTan = log(tan(latRad) + sqrt(tan(latRad) * tan(latRad) + 1));
		int y = (int) floor((1 - asinhTan / PI) / 2 * n);
		return new int[] {x, y};
	}

	/* ------------------------------------------------------------------ materials */

	/** material of a page: its web derivative, or the T0 colour of the surface if the page cannot be loaded */
	private Material material(Map<String, Object> page, Map<String, Object> surface) {

		@SuppressWarnings("unchecked")
		var web = (Map<String, Object>) page.get("web");
		if (web != null) {
			String file = (String) web.get("file");
			Material cached = materialsByPage.get(file);
			if (cached != null) return cached;
			byte[] data = fetchFromMirror(release + "/" + file);
			if (data != null && (web.get("sha256") == null || web.get("sha256").equals(sha256(data)))) {
				var texture = new TexsetPageTexture(file, data);
				Material m = new Material(Material.Interpolation.FLAT, Color.WHITE, false, Material.Transparency.BINARY,
						Material.Shadow.TRUE, Material.AmbientOcclusion.TRUE,
						List.of(new TextureLayer(texture, null, null, null, false)));
				materialsByPage.put(file, m);
				return m;
			}
			ConversionLog.log(WARNING, "texture set: page " + file + " missing or with a wrong sha256", null, null);
		}

		return new Material(Material.Interpolation.FLAT, t0Color(surface));

	}

	@SuppressWarnings("unchecked")
	private static Color t0Color(Map<String, Object> surface) {
		var t0 = (Map<String, Object>) surface.get("t0");
		String hex = t0 == null ? null : (String) t0.get("color");
		if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) return new Color(200, 200, 200);
		int rgb = Integer.parseInt(hex.substring(1), 16);
		return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
	}

	private static String sha256(byte[] data) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
	}

	private static double[] numbers(Object list) {
		return ((List<?>) list).stream().mapToDouble(o -> ((Number) o).doubleValue()).toArray();
	}

	/**
	 * texture coordinates of a point of the texture rectangle, in OSM2World's convention (origin lower left);
	 * page uv has v = 0 at the top row, so t = 1 - v
	 *
	 * @param fs  0..1 along page u (from the rectangle's left edge), ft  0..1 upwards (from its lower edge)
	 */
	private static VectorXZ texCoord(double[] uv, double fs, double ft) {
		double pageU = uv[0] + fs * (uv[2] - uv[0]);
		double pageV = uv[3] - ft * (uv[3] - uv[1]);
		return new VectorXZ(pageU, 1 - pageV);
	}

	/* ------------------------------------------------------------------ walls */

	@SuppressWarnings("unchecked")
	private int addWalls(MapData mapData, Map<String, Map<String, Object>> surfaces,
			Map<String, Map<String, Object>> pageOfSurface) {

		int count = 0;

		for (MapArea area : mapData.getMapAreas()) {
			for (WorldObject rep : List.copyOf(area.getRepresentations())) {
				if (!(rep instanceof Building building)) continue;
				for (BuildingPart part : building.getParts()) {

					MapArea partArea = part.getPrimaryMapElement();
					if (partArea.isBasedOnRelation()) continue; // the key needs the id of the member way

					double ground = building.getGroundLevelEle();
					double base = ground + part.levelStructure.bottomHeight();
					double top = ground + part.levelStructure.heightWithoutRoof();

					List<List<MapNode>> rings = new ArrayList<>();
					rings.add(partArea.getBoundaryNodes());
					rings.addAll(partArea.getHoles());

					for (int r = 0; r < rings.size(); r++) {
						List<MapNode> ring = rings.get(r);
						boolean ccw = signedArea(ring) > 0;
						int side = (r == 0) == ccw ? 1 : -1; // +1: outer face to the right of the ring direction
						for (int i = 0; i + 1 < ring.size(); i++) {
							MapNode n1 = ring.get(i), n2 = ring.get(i + 1);
							String prefix = "w" + partArea.getId() + ":wall:";
							Map<String, Object> s;
							MapNode a, b;
							int sideAB;
							if ((s = surfaces.get(prefix + n1.getId() + "-" + n2.getId())) != null) {
								a = n1; b = n2; sideAB = side;
							} else if ((s = surfaces.get(prefix + n2.getId() + "-" + n1.getId())) != null) {
								a = n2; b = n1; sideAB = -side;
							} else {
								continue;
							}
							if (!edgeMatches(s, a, b)) continue; // geometry changed: keep the default (T0 of OSM2World)
							TexsetSurface wall = buildWall(partArea, s, pageOfSurface.get((String) s.get("key")),
									a.getPos(), b.getPos(), sideAB, base, top);
							if (wall != null) {
								partArea.addRepresentation((TexsetSurface.Wall) wall);
								count++;
							}
						}
					}
				}
			}
		}

		return count;

	}

	private static double signedArea(List<MapNode> ring) {
		double area = 0;
		for (int i = 0; i + 1 < ring.size(); i++) {
			VectorXZ p = ring.get(i).getPos(), q = ring.get(i + 1).getPos();
			area += p.x * q.z - q.x * p.z;
		}
		return area; // x = east, z = north: positive = counter-clockwise seen from above
	}

	private boolean edgeMatches(Map<String, Object> surface, MapNode a, MapNode b) {
		if (surface.get("edge") == null) return false;
		double[] e = numbers(surface.get("edge"));
		VectorXZ sa = projection.toXZ(e[1], e[0]), sb = projection.toXZ(e[3], e[2]);
		return sa.distanceTo(a.getPos()) <= TOLERANCE && sb.distanceTo(b.getPos()) <= TOLERANCE;
	}

	private @Nullable TexsetSurface buildWall(MapArea area, Map<String, Object> s, Map<String, Object> page,
			VectorXZ a, VectorXZ b, int side, double base, double top) {

		double len = a.distanceTo(b);
		if (len < 0.01) return null;
		VectorXZ d = b.subtract(a).normalize();
		VectorXZ outward = new VectorXZ(side * d.z, -side * d.x); // right of a -> b for side = +1

		double[] origin = numbers(s.get("origin")), size = numbers(s.get("size_m")), uv = numbers(s.get("uv"));

		/* texture rectangle, clipped to the wall */
		double s0 = max(origin[0], 0), s1 = min(origin[0] + size[0], len);
		double t0 = max(origin[1], 0), t1 = min(origin[1] + size[1], top - base);
		if (s1 <= s0 || t1 <= t0) return null;

		List<VectorXYZ> corners = new ArrayList<>();
		List<VectorXZ> tcs = new ArrayList<>();
		double[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
		for (double[] p : st) {
			VectorXZ xz = a.add(d.mult(p[0])).add(outward.mult(OFFSET));
			corners.add(new VectorXYZ(xz.x, base + p[1], xz.z));
			tcs.add(texCoord(uv, (p[0] - origin[0]) / size[0], (p[1] - origin[1]) / size[1]));
		}

		return new TexsetSurface.Wall(area, (String) s.get("key"), corners, tcs,
				new VectorXYZ(outward.x, 0, outward.z), material(page, s));

	}

	/* ------------------------------------------------------------------ node faces */

	@SuppressWarnings("unchecked")
	private int addNodeFaces(MapData mapData, Map<String, Map<String, Object>> surfaces,
			Map<String, Map<String, Object>> pageOfSurface) {

		Map<Long, MapNode> nodes = new HashMap<>();
		for (MapNode node : mapData.getMapNodes()) {
			nodes.put(node.getId(), node);
		}

		int count = 0;

		for (var s : surfaces.values()) {

			String key = (String) s.get("key");
			if (!key.matches("n\\d+:face") || s.get("plane") == null) continue;
			MapNode node = nodes.get(Long.parseLong(key.substring(1, key.indexOf(':'))));
			if (node == null) continue;

			var plane = (Map<String, Object>) s.get("plane");
			double[] o = numbers(plane.get("origin")), u = numbers(plane.get("u")), v = numbers(plane.get("v"));
			double[] origin = numbers(s.get("origin")), size = numbers(s.get("size_m")), uv = numbers(s.get("uv"));

			/* east-north-up -> OSM2World x (east), y (up), z (north); height_m is above the terrain */
			VectorXZ originXZ = projection.toXZ(o[1], o[0]);
			double ground = groundEle(node);
			VectorXYZ p0 = new VectorXYZ(originXZ.x, ground + o[2], originXZ.z);
			VectorXYZ uVec = new VectorXYZ(u[0], u[2], u[1]), vVec = new VectorXYZ(v[0], v[2], v[1]);
			double[] f = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]}; // u x v (front)
			VectorXYZ front = new VectorXYZ(f[0], f[2], f[1]);

			List<VectorXYZ> corners = new ArrayList<>();
			List<VectorXZ> tcs = new ArrayList<>();
			double[][] st = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
			for (double[] p : st) {
				corners.add(p0.add(uVec.mult(origin[0] + p[0] * size[0])).add(vVec.mult(origin[1] + p[1] * size[1]))
						.add(front.mult(OFFSET)));
				tcs.add(texCoord(uv, p[0], p[1]));
			}

			node.addRepresentation(new TexsetSurface.NodeFace(node, key, corners, tcs, front,
					material(pageOfSurface.get(key), s)));
			count++;

		}

		return count;

	}

	private static double groundEle(MapNode node) {
		for (var rep : node.getRepresentations()) {
			for (EleConnector c : rep.getEleConnectors()) {
				return c.getPosXYZ().y;
			}
		}
		return 0;
	}

}
