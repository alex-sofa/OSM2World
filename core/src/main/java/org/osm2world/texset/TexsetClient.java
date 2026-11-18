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
import org.osm2world.world.modules.StreetFurnitureModule;
import org.osm2world.world.modules.building.Building;
import org.osm2world.world.modules.building.BuildingPart;

/**
 * Client for streamed texture sets (format {@code osm3d-texset/0.4}, no other version is read): walls with the
 * outward test and the height model, wall chains and node faces with {@code basis}.
 * <p>
 * Enabled by the config property {@code texsetUrl} (one or more cdn URLs, comma-separated, each serving
 * {@code textures/<region>/latest.json}) and {@code texsetRegion}. It loads latest.json from every cdn (highest release wins), picks a cdn,
 * loads the manifest and the cell indices (XYZ z16) of the data bounds, and adds a {@link TexsetSurface}
 * for every matching surface:
 * <ul>
 * <li>walls of building parts mapped as ways, matched by the key {@code w<way id>:wall:<node a>-<node b>}
 * (either node order); because the map projection rounds positions, the exact geom_hash cannot be reproduced,
 * so the geometry is checked against the stored {@code edge} with the position tolerance (0.3 m) instead, and the
 * outer normal must be within 45° of {@code outward};</li>
 * <li>wall chains {@code w<way id>:walls:<first>-<last>}: the {@code nodes} are found in the ring (either direction),
 * every node is checked against {@code chain}, u is the arc length (scaled to the stored length if they differ by
 * less than 2 %);</li>
 * <li>v is in metres from OSM2World's wall base, clipped at its wall top; stretched to OSM2World's wall height only
 * when {@code basis.height} is {@code assumed};</li>
 * <li>part walls: OSM2World draws the parts of Simple 3D Buildings, so they match by their own key
 * ({@code outline_key} is not needed);</li>
 * <li>node faces {@code n<id>:face} (e.g. billboards), placed by their {@code plane}; where {@code basis.size} is
 * {@code assumed} the texture goes onto OSM2World's own billboard model instead, so there is only one board.</li>
 * </ul>
 * Pages are read from their {@code web} derivative (WebP) and checked against its sha256.
 * Anything unmatched or unavailable keeps OSM2World's own appearance.
 */
public class TexsetClient {

	public static final String FORMAT = "osm3d-texset/0.4";
	private static final int Z = 16;
	private static final double TOLERANCE = 0.3;
	private static final double OFFSET = 0.03;
	private static final double OUTWARD_TOLERANCE = 45; // degrees
	private static final double METERS_PER_DEGREE = 111319.49; // arc length of wall chains

	private final O2WConfig config;
	private final MapProjection projection;
	private final List<String> configCdns; // cdn URLs of the config
	private final String region;

	private List<String> cdns = List.of();
	private int cdnIndex = 0;
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
		this.configCdns = Arrays.stream(config.getString("texsetUrl").split(","))
				.map(String::trim).map(s -> s.endsWith("/") ? s : s + "/").toList();
		this.region = config.getString("texsetRegion", "bodensee");
	}

	/* ------------------------------------------------------------------ loading */

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(String json) throws IOException {
		return JsonUtil.fromJson(json, Map.class);
	}

	/** fetches a path relative to textures/<region>/ from the current cdn, failing over to the next ones */
	private @Nullable byte[] fetchFromCdn(String path) {
		for (int attempt = 0; attempt < cdns.size(); attempt++) {
			String base = cdns.get((cdnIndex + attempt) % cdns.size());
			try {
				byte[] result = LoadUriUtil.fetchBinary(URI.create(base + "textures/" + region + "/" + path));
				cdnIndex = (cdnIndex + attempt) % cdns.size();
				return result;
			} catch (IOException e) {
				// 404, network error, 429 or 5xx: the URI API does not tell them apart, try the next cdn
			}
		}
		return null; // treated as "no texture here"
	}

	private @Nullable Map<String, Object> fetchJson(String path) throws IOException {
		byte[] data = fetchFromCdn(path);
		return data == null ? null : parse(new String(data, StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private void run(MapData mapData) throws IOException {

		/* latest.json from every configured cdn; the highest release wins (cdns may lag during replication) */
		Map<String, Object> latest = null;
		for (String b : configCdns) {
			try {
				var candidate = parse(LoadUriUtil.fetchText(URI.create(b + "textures/" + region + "/latest.json")));
				if (!FORMAT.equals(candidate.get("format")) || !(candidate.get("release") instanceof String r)) continue;
				if (latest == null || r.compareTo((String) latest.get("release")) > 0) latest = candidate;
			} catch (IOException e) {
				// this cdn did not answer, try the next one
			}
		}
		if (latest == null) {
			ConversionLog.log(WARNING, "texture set: no cdn answered with a latest.json of format " + FORMAT, null, null);
			return;
		}

		release = (String) latest.get("release");
		cdns = pickCdnOrder((List<Map<String, Object>>) latest.get("cdns"));

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

	/** weighted random first cdn, the others as failover in their listed order */
	private static List<String> pickCdnOrder(List<Map<String, Object>> cdnRecords) {
		if (cdnRecords == null || cdnRecords.isEmpty()) return List.of();
		double total = cdnRecords.stream().mapToDouble(TexsetClient::weight).sum();
		double r = new Random().nextDouble() * total;
		int first = 0;
		for (int i = 0; i < cdnRecords.size(); i++) {
			r -= weight(cdnRecords.get(i));
			if (r <= 0) { first = i; break; }
		}
		List<String> result = new ArrayList<>();
		for (int i = 0; i < cdnRecords.size(); i++) {
			String url = (String) cdnRecords.get((first + i) % cdnRecords.size()).get("url");
			result.add(url.endsWith("/") ? url : url + "/");
		}
		return result;
	}

	private static double weight(Map<String, Object> cdn) {
		return cdn.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
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
			byte[] data = fetchFromCdn(release + "/" + file);
			if (data != null && (web.get("sha256") == null || web.get("sha256").equals(sha256(data)))
					&& TexsetPageTexture.decodes(data)) {
				var texture = new TexsetPageTexture(file, data);
				Material m = new Material(Material.Interpolation.FLAT, Color.WHITE, false, Material.Transparency.BINARY,
						Material.Shadow.TRUE, Material.AmbientOcclusion.TRUE,
						List.of(new TextureLayer(texture, null, null, null, false)));
				materialsByPage.put(file, m);
				return m;
			}
			ConversionLog.log(WARNING, "texture set: page " + file + " missing, with a wrong sha256 or not decodable", null, null);
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

		/* wall chains by way id */
		Map<Long, List<Map<String, Object>>> chainsByWay = new HashMap<>();
		for (var s : surfaces.values()) {
			String key = (String) s.get("key");
			if (key.matches("w\\d+:walls:\\d+-\\d+")) {
				chainsByWay.computeIfAbsent(Long.parseLong(key.substring(1, key.indexOf(':'))), k -> new ArrayList<>()).add(s);
			}
		}

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
							// geometry changed or other side: keep the default (T0 of OSM2World)
							if (!edgeMatches(s, a, b) || !outwardMatches(s, a.getPos(), b.getPos(), sideAB)) continue;
							TexsetSurface wall = buildPiece(partArea, s, pageOfSurface.get((String) s.get("key")),
									a.getPos(), b.getPos(), sideAB, base, top, 0, 1);
							if (wall != null) {
								partArea.addRepresentation((TexsetSurface.Wall) wall);
								count++;
							}
						}
						for (var chain : chainsByWay.getOrDefault(partArea.getId(), List.of())) {
							count += addChain(partArea, chain, pageOfSurface.get((String) chain.get("key")), ring, side, base, top);
						}
					}
				}
			}
		}

		return count;

	}

	/** wall chain on a ring (closed, last node = first node): consecutive edges carrying the chain's nodes */
	private int addChain(MapArea area, Map<String, Object> s, Map<String, Object> page, List<MapNode> ring,
			int side, double base, double top) {

		long[] ids = ((List<?>) s.get("nodes")).stream().mapToLong(o -> ((Number) o).longValue()).toArray();
		List<?> chain = (List<?>) s.get("chain");
		if (ids.length < 3 || chain == null || chain.size() != ids.length) return 0;
		int n = ring.size() - 1;
		if (n < 3) return 0;

		/* find the run in the ring, either direction */
		List<MapNode> run = null;
		int runSide = side;
		for (int start = 0; start < n && run == null; start++) {
			if (ring.get(start).getId() != ids[0]) continue;
			for (int dir : new int[] {1, -1}) {
				List<MapNode> candidate = new ArrayList<>();
				for (int k = 0; k < ids.length; k++) {
					MapNode node = ring.get(((start + dir * k) % n + n) % n);
					if (node.getId() != ids[k]) { candidate = null; break; }
					candidate.add(node);
				}
				if (candidate != null) {
					run = candidate;
					runSide = dir > 0 ? side : -side;
					break;
				}
			}
		}
		if (run == null) return 0;

		/* geometry check against the stored positions, then the outward test on the chord */
		for (int k = 0; k < ids.length; k++) {
			double[] p = numbers(chain.get(k));
			if (projection.toXZ(p[1], p[0]).distanceTo(run.get(k).getPos()) > TOLERANCE) return 0;
		}
		if (!outwardMatches(s, run.get(0).getPos(), run.get(run.size() - 1).getPos(), runSide)) return 0;

		/* arc lengths: stored (local approximation of the format) and OSM2World's own */
		double meanLat = chain.stream().mapToDouble(p -> numbers(p)[1]).average().orElse(0);
		double kx = cos(toRadians(meanLat)) * METERS_PER_DEGREE;
		double stored = 0, own = 0;
		for (int k = 1; k < ids.length; k++) {
			double[] p = numbers(chain.get(k - 1)), q = numbers(chain.get(k));
			stored += hypot((q[0] - p[0]) * kx, (q[1] - p[1]) * METERS_PER_DEGREE);
			own += run.get(k - 1).getPos().distanceTo(run.get(k).getPos());
		}
		if (abs(own - stored) > 0.02 * stored) return 0;
		double scale = stored / own;

		int count = 0;
		double arc = 0;
		for (int k = 1; k < ids.length; k++) {
			VectorXZ a = run.get(k - 1).getPos(), b = run.get(k).getPos();
			TexsetSurface piece = buildPiece(area, s, page, a, b, runSide, base, top, arc, scale);
			if (piece != null) {
				area.addRepresentation((TexsetSurface.Wall) piece);
				count++;
			}
			arc += a.distanceTo(b) * scale;
		}
		return count > 0 ? 1 : 0;

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

	/** P1: the outer normal of a -> b (outer face right of it for side +1) must be within 45° of {@code outward} */
	private static boolean outwardMatches(Map<String, Object> surface, VectorXZ a, VectorXZ b, int side) {
		if (!(surface.get("outward") instanceof Number outward)) return false;
		VectorXZ d = b.subtract(a);
		double azimuth = toDegrees(atan2(side * d.z, -side * d.x)); // x = east, z = north
		double diff = abs(((azimuth - outward.doubleValue()) % 360 + 540) % 360 - 180);
		return diff <= OUTWARD_TOLERANCE;
	}

	/** P2: v scale, 1 unless the record's wall height is an assumption; then stretched to OSM2World's wall */
	@SuppressWarnings("unchecked")
	private static double vScale(Map<String, Object> s, double base, double top) {
		var basis = (Map<String, Object>) s.get("basis");
		if (basis == null || !"assumed".equals(basis.get("height")) || s.get("wall_m") == null) return 1;
		double[] wm = numbers(s.get("wall_m"));
		return wm[1] > wm[0] && top > base ? (top - base) / (wm[1] - wm[0]) : 1;
	}

	/**
	 * One quad on the edge a -> b. Texture u at a is {@code uStart} and grows by {@code uPerMetre} along the edge;
	 * v is metres above the wall base. The texture rectangle is clipped to the edge and to the wall height.
	 */
	private @Nullable TexsetSurface buildPiece(MapArea area, Map<String, Object> s, Map<String, Object> page,
			VectorXZ a, VectorXZ b, int side, double base, double top, double uStart, double uPerMetre) {

		double len = a.distanceTo(b);
		if (len < 0.01) return null;
		VectorXZ d = b.subtract(a).normalize();
		VectorXZ outward = new VectorXZ(side * d.z, -side * d.x); // right of a -> b for side = +1
		double vs = vScale(s, base, top);

		double[] origin = numbers(s.get("origin")), size = numbers(s.get("size_m")), uv = numbers(s.get("uv"));

		/* texture rectangle (texture metres), clipped to the edge and the wall */
		double s0 = max(origin[0], uStart), s1 = min(origin[0] + size[0], uStart + len * uPerMetre);
		double t0 = max(origin[1], 0), t1 = min(origin[1] + size[1], (top - base) / vs);
		if (s1 <= s0 || t1 <= t0) return null;

		List<VectorXYZ> corners = new ArrayList<>();
		List<VectorXZ> tcs = new ArrayList<>();
		double[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
		for (double[] p : st) {
			VectorXZ xz = a.add(d.mult((p[0] - uStart) / uPerMetre)).add(outward.mult(OFFSET));
			corners.add(new VectorXYZ(xz.x, base + p[1] * vs, xz.z));
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

			double[] uv = numbers(s.get("uv"));

			/* P6: where the record's size is an assumption (like OSM2World's own default size), the texture goes onto
			 * OSM2World's own billboard model (the texture rectangle is stretched to the poster), so the model and the
			 * texture do not show two different boards. Tagged or measured sizes use the record's plane. */
			var billboard = billboardOf(node);
			var basis = (Map<String, Object>) s.get("basis");
			if (billboard != null && basis != null && "assumed".equals(basis.get("size"))) {
				List<VectorXYZ> poster = billboard.getPosterCorners();
				VectorXZ dir = billboard.getDirection();
				VectorXYZ front = new VectorXYZ(dir.x, 0, dir.z);
				List<VectorXYZ> corners = poster.stream().map(c -> c.add(front.mult(OFFSET))).toList();
				List<VectorXZ> tcs = List.of(texCoord(uv, 0, 0), texCoord(uv, 1, 0), texCoord(uv, 1, 1), texCoord(uv, 0, 1));
				node.addRepresentation(new TexsetSurface.NodeFace(node, key, corners, tcs, front,
						material(pageOfSurface.get(key), s)));
				count++;
				continue;
			}

			var plane = (Map<String, Object>) s.get("plane");
			double[] o = numbers(plane.get("origin")), u = numbers(plane.get("u")), v = numbers(plane.get("v"));
			double[] origin = numbers(s.get("origin")), size = numbers(s.get("size_m"));

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

	private static @Nullable StreetFurnitureModule.Billboard billboardOf(MapNode node) {
		for (var rep : node.getRepresentations()) {
			if (rep instanceof StreetFurnitureModule.Billboard b) return b;
		}
		return null;
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
