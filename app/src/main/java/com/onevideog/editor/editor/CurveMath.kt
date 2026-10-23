package com.onevideog.editor.editor

import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v0.7.0 (Fase J) — pure curve math: validation, the Fritsch–Carlson MONOTONIC
 * cubic spline, the 256-entry LUT builder, the CPU reference applier and the
 * GLSL fragment shader rendered by BOTH pipelines (preview and export share
 * the exact same shader string through [ColorCurvesEffects.effectsFor]).
 * No Android types — fully JVM-testable.
 *
 * WHY Fritsch–Carlson (and not a natural cubic spline): a natural spline can
 * OVERSHOOT between control points (a monotone input can produce a curve that
 * dips/rings between samples), which on tonal curves means non-monotone LUTs
 * — banding and reversed contrast. The Fritsch–Carlson limiter keeps the
 * interpolant monotone between every pair of adjacent points (each Hermite
 * tangent is constrained so the cubic never leaves the [y_k, y_k+1] range on
 * a monotone interval), which is the property colour grading needs.
 */
object CurveMath {

    /** LUT resolution (the shader samples the same resolution via 256x1 textures). */
    const val LUT_SIZE = 256

    /** Identity tolerance for [isIdentity] (y == x within ±1e-3). */
    const val IDENTITY_TOLERANCE = 1e-3f

    /** Minimum x separation between consecutive control points (strictly increasing x). */
    const val MIN_X_SEPARATION = 1e-3f

    /** Which channel a LUT applies to. MASTER replaces all three RGB channels. */
    enum class Channel { MASTER, RED, GREEN, BLUE }

    /** Why a [Curve] was rejected (evidence for CURVE_INVALID). */
    enum class InvalidReason {
        /** Fewer than 2 points — not a curve. */
        TOO_FEW_POINTS,
        /** A point has x or y outside 0..1, or a non-finite coordinate. */
        POINT_OUT_OF_RANGE,
        /** Points not ordered by strictly increasing x (after normalization). */
        NOT_SORTED,
    }

    // ------------------------------------------------------------- validation

    /**
     * Pure validity check for one curve (JVM-tested). Points outside 0..1 or
     * non-finite are invalid; x must be strictly increasing after sorting;
     * at least 2 points are required.
     */
    fun validate(curve: Curve): InvalidReason? {
        if (curve.points.size < 2) return InvalidReason.TOO_FEW_POINTS
        var prevX = Float.NEGATIVE_INFINITY
        for (p in curve.points) {
            if (!p.x.isFinite() || !p.y.isFinite()) return InvalidReason.POINT_OUT_OF_RANGE
            if (p.x < 0f || p.x > 1f || p.y < 0f || p.y > 1f) {
                return InvalidReason.POINT_OUT_OF_RANGE
            }
            if (p.x <= prevX) return InvalidReason.NOT_SORTED
            prevX = p.x
        }
        return null
    }

    /** True when [curve] passes [validate]. */
    fun isValid(curve: Curve): Boolean = validate(curve) == null

    /**
     * Normalizes a user-drawn curve into a storable one: clamps coordinates to
     * 0..1, sorts by x, drops points closer than [MIN_X_SEPARATION] to the
     * previous kept x (strictly increasing x — the spline divides by dx).
     * Returns null when fewer than 2 points remain (the caller stores null /
     * drops the channel and logs CURVE_INVALID).
     */
    fun sanitize(points: List<CurvePoint>): Curve? {
        val cleaned = points
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }
        val kept = mutableListOf<CurvePoint>()
        for (p in cleaned) {
            if (kept.isEmpty() || p.x - kept.last().x >= MIN_X_SEPARATION) {
                kept += p
            }
        }
        if (kept.size < 2) return null
        return Curve(kept)
    }

    // -------------------------------------------------------------- identity

    /**
     * True when the curve is the identity (y = x within [IDENTITY_TOLERANCE]
     * for EVERY point). Identity curves build no GPU work (the effect glue
     * skips them — no LUT texture, no shader pass).
     */
    fun isIdentity(curve: Curve): Boolean {
        if (curve.points.size < 2) return false
        return curve.points.all {
            kotlin.math.abs(it.y - it.x) <= IDENTITY_TOLERANCE
        }
    }

    // ------------------------------------------------------------------- LUT

    /**
     * Maps the curve's control points to a [LUT_SIZE]-entry LUT (values 0..1)
     * through the Fritsch–Carlson monotonic cubic spline. x outside the
     * point range clamps to the first/last point's y (full-range curves have
     * points at x = 0 and x = 1, so this only matters for partial curves).
     */
    fun buildLut(curve: Curve): FloatArray {
        val pts = curve.points.sortedBy { it.x }
        val n = pts.size
        val lut = FloatArray(LUT_SIZE)
        if (n < 2) {
            // Degenerate curve: identity fallback (defence in depth — the
            // ViewModel never stores curves with < 2 points).
            for (i in 0 until LUT_SIZE) lut[i] = i / (LUT_SIZE - 1f)
            return lut
        }
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y }
        val tangents = fritschCarlsonTangents(xs, ys)
        for (i in 0 until LUT_SIZE) {
            val x = i / (LUT_SIZE - 1f)
            lut[i] = evaluateSpline(xs, ys, tangents, x).coerceIn(0f, 1f)
        }
        return lut
    }

    /**
     * Applies a float LUT (0..1 values, [LUT_SIZE] entries) to one channel of
     * an ARGB pixel buffer. MASTER replaces R, G and B with the same mapping.
     * The byte index selects the LUT entry; the 0..1 result is scaled back to
     * 0..255 (rounded) — the same quantization the GL path stores in its
     * 8-bit LUT textures (documented parity basis, PREVIEW_EXPORT_PARITY).
     */
    fun applyLut(
        pixels: IntArray,
        lut: FloatArray,
        channel: Channel,
    ): IntArray {
        require(lut.size == LUT_SIZE) { "lut must have $LUT_SIZE entries" }
        val bytes = IntArray(LUT_SIZE) { i -> (lut[i] * 255f).roundToInt().coerceIn(0, 255) }
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            var r = (c ushr 16) and 0xFF
            var g = (c ushr 8) and 0xFF
            var b = c and 0xFF
            when (channel) {
                Channel.MASTER -> {
                    r = bytes[r]; g = bytes[g]; b = bytes[b]
                }
                Channel.RED -> r = bytes[r]
                Channel.GREEN -> g = bytes[g]
                Channel.BLUE -> b = bytes[b]
            }
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Float LUT -> the 8-bit table the CPU reference and the GL texture share. */
    fun lutBytes(lut: FloatArray): IntArray =
        IntArray(LUT_SIZE) { i -> (lut[i] * 255f).roundToInt().coerceIn(0, 255) }

    // ---------------------------------------------------- Fritsch–Carlson

    /**
     * Fritsch–Carlson tangents for a monotone-ish data set (the classic
     * algorithm, as published in Fritsch & Carlson 1980 / the standard
     * "monotone cubic interpolation" reference):
     *  1. secant slopes d_k between consecutive points;
     *  2. initial tangents: endpoints take the adjacent secant, interior
     *     points the average — zeroed where the neighbourhood changes
     *     direction (d_{k-1} * d_k <= 0), which forces a local extremum;
     *  3. the limiter pass: per interval, when (m_k/d_k)² + (m_{k+1}/d_k)² > 9
     *     both tangents are scaled by tau = 3/sqrt(...) — the region inside
     *     which the cubic is guaranteed monotone on that interval.
     */
    fun fritschCarlsonTangents(xs: FloatArray, ys: FloatArray): FloatArray {
        val n = xs.size
        if (n < 2) return FloatArray(n)
        val d = FloatArray(n - 1)
        for (k in 0 until n - 1) {
            val dx = xs[k + 1] - xs[k]
            d[k] = if (dx > 0f) (ys[k + 1] - ys[k]) / dx else 0f
        }
        val m = FloatArray(n)
        if (n == 2) {
            m[0] = d[0]
            m[1] = d[0]
            return m
        }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) {
            m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        }
        for (k in 0 until n - 1) {
            if (d[k] == 0f) {
                m[k] = 0f
                m[k + 1] = 0f
            } else {
                val a = m[k] / d[k]
                val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / sqrt(s)
                    m[k] = tau * a * d[k]
                    m[k + 1] = tau * b * d[k]
                }
            }
        }
        return m
    }

    /** Hermite evaluation of the spline at [x] (clamped to the point range). */
    fun evaluateSpline(
        xs: FloatArray,
        ys: FloatArray,
        tangents: FloatArray,
        x: Float,
    ): Float {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        // Find the interval [k, k+1] containing x (linear scan — n <= 8).
        var k = 0
        while (k < n - 2 && x > xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        if (h <= 0f) return ys[k]
        val t = (x - xs[k]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        return h00 * ys[k] + h10 * h * tangents[k] + h01 * ys[k + 1] + h11 * h * tangents[k + 1]
    }

    // ----------------------------------------------------------------- GLSL

    /**
     * The curves fragment shader rendered by BOTH pipelines. The four LUTs
     * are 256x1 8-bit textures (u_LutMaster / u_LutR / u_LutG / u_LutB,
     * GL_NEAREST, CLAMP_TO_EDGE); sampling at the channel value u = v returns
     * texel floor(v*256) == the byte-mapped LUT entry the CPU reference uses
     * (for 8-bit input values u = k/255 maps to texel k exactly). Curves are
     * applied IN SEQUENCE: Master (all three channels) first, then per
     * channel — the per-channel curves receive the MASTER-graded input,
     * matching [applyCurves] and the classic grading order.
     */
    fun curvesFragmentShaderCode(): String = """
#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
uniform sampler2D u_LutMaster;
uniform sampler2D u_LutR;
uniform sampler2D u_LutG;
uniform sampler2D u_LutB;

float applyLut(sampler2D lut, float v) {
  return texture2D(lut, vec2(clamp(v, 0.0, 1.0), 0.5)).r;
}

void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 rgb = c.rgb;
  float mr = applyLut(u_LutMaster, rgb.r);
  float mg = applyLut(u_LutMaster, rgb.g);
  float mb = applyLut(u_LutMaster, rgb.b);
  rgb = vec3(applyLut(u_LutR, mr), applyLut(u_LutG, mg), applyLut(u_LutB, mb));
  gl_FragColor = vec4(rgb, c.a);
}
""".trim()

    // ------------------------------------------------------- CPU reference

    /**
     * CPU reference of the GPU pass: the four LUTs applied IN SEQUENCE —
     * Master to all three channels first, then R/G/B on the master-graded
     * values (the exact order the shader runs). JVM-testable parity spec.
     */
    fun applyCurves(
        pixels: IntArray,
        masterLut: FloatArray,
        redLut: FloatArray,
        greenLut: FloatArray,
        blueLut: FloatArray,
    ): IntArray {
        val out = applyLut(pixels, masterLut, Channel.MASTER)
        applyLutInto(out, redLut, Channel.RED)
        applyLutInto(out, greenLut, Channel.GREEN)
        applyLutInto(out, blueLut, Channel.BLUE)
        return out
    }

    /** In-place variant used by [applyCurves] to chain the four LUTs. */
    private fun applyLutInto(pixels: IntArray, lut: FloatArray, channel: Channel) {
        require(lut.size == LUT_SIZE) { "lut must have $LUT_SIZE entries" }
        val bytes = lutBytes(lut)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            var r = (c ushr 16) and 0xFF
            var g = (c ushr 8) and 0xFF
            var b = c and 0xFF
            when (channel) {
                Channel.MASTER -> {
                    r = bytes[r]; g = bytes[g]; b = bytes[b]
                }
                Channel.RED -> r = bytes[r]
                Channel.GREEN -> g = bytes[g]
                Channel.BLUE -> b = bytes[b]
            }
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    // -------------------------------------------------------------- presets

    /** The four UI presets of the ColorCurvesSheet, as point lists. */
    object Presets {
        val LINEAR = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

        /** S-Curve: gentle contrast — shadows lifted down, highlights up. */
        val S_CURVE = listOf(
            CurvePoint(0f, 0f), CurvePoint(0.25f, 0.16f),
            CurvePoint(0.75f, 0.84f), CurvePoint(1f, 1f),
        )

        /** Fade: lifted blacks, slightly lowered whites (matte look). */
        val FADE = listOf(CurvePoint(0f, 0.08f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 0.94f))

        /** Cinematic: lifted blacks + rolled highlights + slight teal shadow. */
        val CINEMATIC = listOf(
            CurvePoint(0f, 0.06f), CurvePoint(0.25f, 0.2f),
            CurvePoint(0.75f, 0.85f), CurvePoint(1f, 0.95f),
        )
    }
}
