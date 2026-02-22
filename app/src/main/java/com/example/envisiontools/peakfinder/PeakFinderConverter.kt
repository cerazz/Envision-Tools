package com.example.envisiontools.peakfinder

import com.example.envisiontools.ble.LandscapeLine
import com.example.envisiontools.ble.LandscapePoint
import com.example.envisiontools.ble.PoiEntry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private const val EARTH_R = 6_371_000.0
private const val NUM_SECTORS = 32
private const val SECTOR_DEG = 360.0 / NUM_SECTORS  // 11.25°

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------

fun estimateDistance(altRad: Double, observerEle: Double = 0.0): Double {
    if (altRad <= -0.05) return 200_000.0   // horizon or below → cap at 200 km
    val K = 240.0               // empirical scale factor (metres · radian ≈ near-horizon calibration)
    val dminFactor = 4e-5       // prevents division by zero for near-zero elevation angles
    val tanAlt = if (altRad > -Math.PI / 4) Math.tan(altRad) else -1.0
    val denom = tanAlt + dminFactor
    val dist = if (denom <= 0) 150_000.0 else K / denom
    return dist.coerceIn(500.0, 320_000.0)
}

fun elevationFromDistAngle(dist: Double, altRad: Double, observerEle: Double): Double {
    val curveCorrection = (dist * dist) / (2.0 * EARTH_R)
    return observerEle + dist * Math.tan(altRad) + curveCorrection
}

fun azimuthToSector(aziDeg: Double): Int = ((aziDeg % 360.0) / SECTOR_DEG).toInt() % NUM_SECTORS

// ---------------------------------------------------------------------------
// Horizon / landscape parsing
// ---------------------------------------------------------------------------

/**
 * Parse horizon.txt lines into (azimuth_deg, altitude_deg) pairs.
 * Lines starting with '#' are ignored.
 */
fun parseHorizonTxt(text: String): List<Pair<Double, Double>> {
    val result = mutableListOf<Pair<Double, Double>>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.size < 2) continue
        val az = parts[0].toDoubleOrNull() ?: continue
        val alt = parts[1].toDoubleOrNull() ?: continue
        result.add(Pair(az, alt))
    }
    return result
}

/**
 * Linearly interpolate altitude between two surrounding horizon points at [targetAz].
 */
private fun interpolateAltitude(
    pts: List<Pair<Double, Double>>,
    targetAz: Double
): Double {
    if (pts.isEmpty()) return 0.0
    if (pts.size == 1) return pts[0].second
    // Find surrounding pair
    for (i in 0 until pts.size - 1) {
        val (az0, alt0) = pts[i]
        val (az1, alt1) = pts[i + 1]
        if (targetAz >= az0 && targetAz <= az1) {
            if (az1 == az0) return alt0
            val t = (targetAz - az0) / (az1 - az0)
            return alt0 + t * (alt1 - alt0)
        }
    }
    // Outside range: return nearest
    return if (targetAz <= pts.first().first) pts.first().second else pts.last().second
}

/**
 * Convert a list of (azimuth_deg, altitude_deg) raw points into 32 [LandscapeLine]s.
 *
 * Each sector i covers azimuths [i*11.25, (i+1)*11.25). Boundary points are interpolated
 * so that the last point of sector N equals the first point of sector N+1.
 */
fun horizonToLandscapeLines(
    pts: List<Pair<Double, Double>>,
    observerEle: Double
): List<LandscapeLine> {
    // Normalise azimuths to [0, 360) and sort
    val sorted = pts.map { (az, alt) -> Pair(az % 360.0, alt) }
        .sortedBy { it.first }

    val lines = mutableListOf<LandscapeLine>()
    for (i in 0 until NUM_SECTORS) {
        val sectorStartDeg = i * SECTOR_DEG
        val sectorEndDeg = (i + 1) * SECTOR_DEG

        // Interior points that fall within (sectorStart, sectorEnd)
        val interior = sorted.filter { (az, _) -> az > sectorStartDeg && az < sectorEndDeg }

        // Interpolated boundary altitudes
        val startAlt = interpolateAltitude(sorted, sectorStartDeg)
        val endAlt = interpolateAltitude(sorted, sectorEndDeg)

        val allPts = mutableListOf<Pair<Double, Double>>()
        allPts.add(Pair(sectorStartDeg, startAlt))
        allPts.addAll(interior)
        allPts.add(Pair(sectorEndDeg, endAlt))

        val sectorStartRad = Math.toRadians(sectorStartDeg)
        val sectorEndRad = Math.toRadians(sectorEndDeg % 360.0)

        val landscapePoints = allPts.map { (az, alt) ->
            val azRad = Math.toRadians(az % 360.0)
            val altRad = Math.toRadians(alt)
            LandscapePoint(azRad.toFloat(), altRad.toFloat())
        }

        lines.add(
            LandscapeLine(
                lineIndex = i,
                azMin = sectorStartRad.toFloat(),
                azMax = sectorEndRad.toFloat(),
                points = landscapePoints
            )
        )
    }
    return lines
}

// ---------------------------------------------------------------------------
// Gazetteer / POI parsing
// ---------------------------------------------------------------------------

/**
 * Parse gazetteer.en.utf8 lines.
 * Format per line: `azimuth_deg | altitude_deg | ignored | ignored | peak_name`
 * Returns a list of maps with keys: az, alt, name.
 */
fun parseGazetteer(text: String): List<Map<String, Any>> {
    val result = mutableListOf<Map<String, Any>>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val parts = trimmed.split("|")
        if (parts.size < 5) continue
        val az = parts[0].trim().toDoubleOrNull() ?: continue
        val alt = parts[1].trim().toDoubleOrNull() ?: continue
        val name = parts[4].trim()
        result.add(mapOf("az" to az, "alt" to alt, "name" to name))
    }
    return result
}

/**
 * Convert parsed gazetteer entries into [PoiEntry] objects.
 * `importance` is normalised altitude (0..1) relative to the max altitude entry.
 */
fun gazetteerToPoiEntries(
    entries: List<Map<String, Any>>,
    observerEle: Double
): List<PoiEntry> {
    if (entries.isEmpty()) return emptyList()
    val maxAlt = entries.mapNotNull { (it["alt"] as? Double)?.takeIf { a -> a > 0.0 } }
        .maxOrNull() ?: 1.0

    return entries.map { entry ->
        val azDeg = entry["az"] as Double
        val altDeg = entry["alt"] as Double
        val name = entry["name"] as String
        val azRad = Math.toRadians(azDeg % 360.0)
        val altRad = Math.toRadians(altDeg)
        val importance = (maxOf(0.0, altDeg) / maxAlt).coerceIn(0.0, 1.0)
        val dist = estimateDistance(altRad, observerEle)
        val elevation = elevationFromDistAngle(dist, altRad, observerEle)
        val sector = azimuthToSector(azDeg)
        PoiEntry(
            sectorIndex = sector,
            azimut = azRad.toFloat(),
            altitude = altRad.toFloat(),
            importance = importance.toFloat(),
            elevation = elevation.toFloat(),
            distance = dist.toFloat(),
            name = name
        )
    }
}

// ---------------------------------------------------------------------------
// ZIP parsing
// ---------------------------------------------------------------------------

/**
 * Parse a Stellarium ZIP archive (bytes) from PeakFinder.
 *
 * Reads:
 * - `horizon.txt`        → landscape lines
 * - `gazetteer.en.utf8`  → POI entries
 * - `landscape.ini`      → observer elevation
 *
 * Returns a [Pair] of (landscape lines, POI entries), either of which may be null
 * if the corresponding data was not found.
 */
fun parseStellariumZip(bytes: ByteArray): Pair<List<LandscapeLine>?, List<PoiEntry>?> {
    var horizonText: String? = null
    var gazetteerText: String? = null
    var observerEle = 0.0

    ZipInputStream(bytes.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name.substringAfterLast("/").lowercase()
            when {
                name == "horizon.txt" -> {
                    horizonText = BufferedReader(InputStreamReader(zis, Charsets.UTF_8)).readText()
                }
                name.startsWith("gazetteer") -> {
                    gazetteerText = BufferedReader(InputStreamReader(zis, Charsets.UTF_8)).readText()
                }
                name == "landscape.ini" -> {
                    val iniText = BufferedReader(InputStreamReader(zis, Charsets.UTF_8)).readText()
                    for (line in iniText.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("altitude")) {
                            val value = trimmed.substringAfter("=").trim()
                            observerEle = value.toDoubleOrNull() ?: 0.0
                            break
                        }
                    }
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }

    val landscapeLines = horizonText?.let {
        val pts = parseHorizonTxt(it)
        horizonToLandscapeLines(pts, observerEle)
    }

    val poiEntries = gazetteerText?.let {
        val entries = parseGazetteer(it)
        gazetteerToPoiEntries(entries, observerEle)
    }

    return Pair(landscapeLines, poiEntries)
}
