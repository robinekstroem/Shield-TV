package se.norrsken.tv.desktop.player

import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * Gör om videons YUV-plan till färg på grafikkortet.
 *
 * Varför inte låta VLC göra det: VLC:s omvandling till RGB för en bild som
 * ritas av appen räknar med samma färgmatris för allt, och HD-kanaler (BT.709)
 * får då något förskjutna färger. Här väljs matrisen efter upplösningen,
 * TV-nivåerna 16–235 sträcks ut till 0–255, och färgplanen placeras exakt som
 * i H.264/HEVC (vänsterställda). Skalningen av luminansen är bikubisk
 * (Catmull-Rom) — skarpare än bilinjär när en 720p-kanal fyller en 4K-skärm.
 *
 * Går shadern inte att kompilera faller spelaren tillbaka på RGB från VLC,
 * så bilden syns alltid.
 */
object YuvRenderer {

    private val sksl = """
        uniform shader luma;
        uniform shader cb;
        uniform shader cr;
        uniform float4 rowR;
        uniform float4 rowG;
        uniform float4 rowB;

        half4 main(float2 p) {
            float y = float(luma.eval(p).r);
            float2 c = float2(p.x * 0.5 + 0.25, p.y * 0.5);
            float4 yuv = float4(y, float(cb.eval(c).r), float(cr.eval(c).r), 1.0);
            float3 rgb = float3(dot(rowR, yuv), dot(rowG, yuv), dot(rowB, yuv));
            return half4(half3(saturate(rgb)), 1.0);
        }
    """.trimIndent()

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(sksl) }.getOrNull()
    }

    /** Om bildvägen via grafikkortet går att använda på den här datorn. */
    val available: Boolean
        get() = effect != null

    // Rad för rad: R, G, B = rad · (Y, U, V, 1), med TV-nivåerna utsträckta.
    private val bt709 = rows(kr = 1.5748f, kgU = 0.187324f, kgV = 0.468124f, kb = 1.8556f)
    private val bt601 = rows(kr = 1.402f, kgU = 0.344136f, kgV = 0.714136f, kb = 1.772f)

    /**
     * En shader för en bildruta, i bildrutans egna pixelkoordinater. Den som
     * ritar skalar och flyttar med canvasen.
     */
    fun shader(y: Image, u: Image, v: Image, width: Int, height: Int, sharp: Boolean): Shader? {
        val effect = effect ?: return null
        // Videon talar inte om sin färgmatris här. Samma tumregel som alla
        // spelare använder när den saknas: HD är BT.709, SD är BT.601.
        val rows = if (width >= 1280 || height >= 720) bt709 else bt601
        val lumaShader = y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, if (sharp) SamplingMode.CATMULL_ROM else SamplingMode.LINEAR)
        val cbShader = u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
        val crShader = v.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
        val builder = RuntimeShaderBuilder(effect)
        return try {
            builder.child("luma", lumaShader)
            builder.child("cb", cbShader)
            builder.child("cr", crShader)
            builder.uniform("rowR", rows[0], rows[1], rows[2], rows[3])
            builder.uniform("rowG", rows[4], rows[5], rows[6], rows[7])
            builder.uniform("rowB", rows[8], rows[9], rows[10], rows[11])
            builder.makeShader()
        } catch (e: RuntimeException) {
            null
        } finally {
            builder.close()
            lumaShader.close()
            cbShader.close()
            crShader.close()
        }
    }

    private fun rows(kr: Float, kgU: Float, kgV: Float, kb: Float): FloatArray {
        val ys = 255f / 219f
        val cs = 255f / 224f
        val y0 = -16f / 255f * ys
        val c0 = 128f / 255f
        return floatArrayOf(
            ys, 0f, kr * cs, y0 - c0 * kr * cs,
            ys, -kgU * cs, -kgV * cs, y0 + c0 * (kgU + kgV) * cs,
            ys, kb * cs, 0f, y0 - c0 * kb * cs,
        )
    }
}
