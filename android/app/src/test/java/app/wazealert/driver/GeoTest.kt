package app.wazealert.driver

import org.junit.Assert.*
import org.junit.Test

class GeoTest {
    @Test fun bearingCardinal() {
        assertEquals(0.0, Geo.bearing(0.0, 0.0, 1.0, 0.0), 0.5)
        assertEquals(90.0, Geo.bearing(0.0, 0.0, 0.0, 1.0), 0.5)
        assertEquals(180.0, Geo.bearing(1.0, 0.0, 0.0, 0.0), 0.5)
        assertEquals(270.0, Geo.bearing(0.0, 1.0, 0.0, 0.0), 0.5)
    }

    @Test fun angleDiffWraps() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
    }

    @Test fun aheadFilter() {
        assertTrue(Geo.isAhead(270.0, 270.0))
        assertTrue(Geo.isAhead(270.0, 300.0))
        assertFalse(Geo.isAhead(270.0, 90.0))
        assertFalse(Geo.isAhead(270.0, 0.0))
        assertTrue(Geo.isAhead(5.0, 350.0))
        assertTrue(Geo.isAhead(null, 90.0))
    }

    @Test fun cameraDirection() {
        assertTrue(Geo.dirMatches(270.0, intArrayOf(282)))        // same way
        assertFalse(Geo.dirMatches(270.0, intArrayOf(102)))       // opposite carriageway
        assertTrue(Geo.dirMatches(270.0, intArrayOf(102, 282)))   // either of two
        assertTrue(Geo.dirMatches(270.0, null))                   // unknown direction: never hide
        assertTrue(Geo.dirMatches(null, intArrayOf(102)))         // stationary: never hide
        assertTrue(Geo.dirMatches(350.0, intArrayOf(10)))         // wraps through north
    }

    @Test fun alertModes() {
        // driving west (270). Target bearing 270 = dead ahead.
        assertTrue(Geo.inMode("front", 270.0, 270.0, 1000.0))
        assertTrue(Geo.inMode("front", 270.0, 300.0, 1000.0))         // 30 off: ahead
        assertFalse(Geo.inMode("front", 270.0, 90.0, 1000.0))         // behind
        // path: needs to be within 25 deg AND within 120 m sideways
        assertTrue(Geo.inMode("path", 270.0, 270.0, 1000.0))
        assertTrue(Geo.inMode("path", 270.0, 276.0, 1000.0))          // 6 deg at 1 km = 105 m sideways
        assertFalse(Geo.inMode("path", 270.0, 280.0, 1000.0))         // 10 deg at 1 km = 174 m sideways
        assertTrue(Geo.inMode("path", 270.0, 290.0, 200.0))           // 20 deg at 200 m = 68 m sideways
        assertFalse(Geo.inMode("path", 270.0, 300.0, 200.0))          // 30 deg: outside the cone
        // radius shows everything, and so does anything with no heading
        assertTrue(Geo.inMode("radius", 270.0, 90.0, 1000.0))
        assertTrue(Geo.inMode("path", null, 90.0, 1000.0))
        assertEquals(0.0, Geo.crossTrackM(1000.0, 0.0), 1e-9)
        assertEquals(1000.0, Geo.crossTrackM(1000.0, 90.0), 1e-6)
    }

    @Test fun warnRadius() {
        assertEquals(800.0, Geo.warnRadiusM(5.0), 1e-9)
        assertEquals(1350.0, Geo.warnRadiusM(30.0), 1e-9)
        assertEquals(3000.0, Geo.warnRadiusM(100.0), 1e-9)
        assertEquals(2100.0, Geo.warnRadiusM(30.0, 70), 1e-9)
    }

    @Test fun units() {
        assertEquals("35 mph", Fmt.limit(56, false))
        assertEquals("30 mph", Fmt.limit(48, false))
        assertEquals("75 mph", Fmt.limit(120, false))
        assertEquals("56 km/h", Fmt.limit(56, true))
        assertEquals("0.5 mi", Fmt.dist(805.0, false))
        assertEquals("400 m", Fmt.dist(400.0, true))
        assertEquals("1.5 km", Fmt.dist(1500.0, true))
        assertEquals("45 mph", Fmt.speed(20.1168, false))
    }
}
