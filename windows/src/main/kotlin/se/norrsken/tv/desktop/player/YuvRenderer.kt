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
 * HDR (4K-filmer i HDR10 och Dolby Vision, eller HLG): VLC lämnar bilden som
 * den är kodad, och visad rakt av blir den blek och grå. Den görs därför om
 * till vanlig bild här — ljuset räknas fram ur PQ- eller HLG-kurvan, färgerna
 * flyttas från BT.2020 till skärmens BT.709, och högdagrar över
 * referensvitt (203 nit) trycks mjukt ihop mot vitt i stället för att klippas.
 *
 * Går shadern inte att kompilera faller spelaren tillbaka på RGB från VLC,
 * så bilden syns alltid; går HDR-shadern inte att kompilera visas HDR som förut.
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

    /**
     * HDR till vanlig bild. Steg för steg: YUV med BT.2020:s matris, ljuset i
     * nit ur PQ (HDR10, Dolby Vision) eller HLG, 203 nit som vitt, BT.2020 till
     * BT.709, högdagrarna ihoptryckta på den starkaste färgkanalen (så att
     * färgtonen står kvar), och sist skärmens gamma.
     */
    private val hdrSksl = """
        uniform shader luma;
        uniform shader cb;
        uniform shader cr;
        uniform float hlg;

        float3 pqToNits(float3 e) {
            float3 p = pow(e, float3(0.0126833135));
            float3 num = max(p - 0.8359375, 0.0);
            float3 den = 18.8515625 - 18.6875 * p;
            return pow(num / den, float3(6.2773946360)) * 10000.0;
        }

        float3 hlgToNits(float3 e) {
            float3 low = e * e / 3.0;
            float3 high = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
            float3 s = mix(low, high, step(0.5, e));
            float ys = dot(s, float3(0.2627, 0.6780, 0.0593));
            return 1000.0 * pow(max(ys, 0.000001), 0.2) * s;
        }

        float shoulder(float x) {
            float t = clamp((x - 0.6) / 0.4, 0.0, 10.81527);
            float knee = 0.6 + 0.4 * t * (1.0 + t / 116.9701) / (1.0 + t);
            return x <= 0.6 ? x : knee;
        }

        half4 main(float2 p) {
            float y = float(luma.eval(p).r);
            float2 c = float2(p.x * 0.5 + 0.25, p.y * 0.5);
            float yy = (y - 0.0627451) * 1.1643836;
            float uu = (float(cb.eval(c).r) - 0.5019608) * 1.1383929;
            float vv = (float(cr.eval(c).r) - 0.5019608) * 1.1383929;
            float3 e = clamp(float3(yy + 1.4746 * vv, yy - 0.16455 * uu - 0.57135 * vv, yy + 1.8814 * uu), 0.0, 1.0);
            float3 nits = hlg > 0.5 ? hlgToNits(e) : pqToNits(e);
            float3 lin = nits / 203.0;
            float3 rgb = max(float3(
                dot(lin, float3(1.6605, -0.5876, -0.0728)),
                dot(lin, float3(-0.1246, 1.1329, -0.0083)),
                dot(lin, float3(-0.0182, -0.1006, 1.1187))), 0.0);
            float m = max(rgb.r, max(rgb.g, rgb.b));
            rgb = rgb * (shoulder(m) / max(m, 0.000001));
            return half4(half3(pow(clamp(rgb, 0.0, 1.0), float3(0.4166667))), 1.0);
        }
    """.trimIndent()

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(sksl) }.getOrNull()
    }

    private val hdrEffect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(hdrSksl) }.getOrNull()
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
    fun shader(
        y: Image,
        u: Image,
        v: Image,
        width: Int,
        height: Int,
        sharp: Boolean,
        range: VideoPlayer.Range = VideoPlayer.Range.SDR,
    ): Shader? {
        if (range != VideoPlayer.Range.SDR) {
            hdrShader(y, u, v, sharp, hlg = range == VideoPlayer.Range.HLG)?.let { return it }
        }
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

    /** HDR-vägen. Null om den inte går att använda: då ritas bilden som vanlig bild. */
    private fun hdrShader(y: Image, u: Image, v: Image, sharp: Boolean, hlg: Boolean): Shader? {
        val effect = hdrEffect ?: return null
        val lumaShader = y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, if (sharp) SamplingMode.CATMULL_ROM else SamplingMode.LINEAR)
        val cbShader = u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
        val crShader = v.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
        val builder = RuntimeShaderBuilder(effect)
        return try {
            builder.child("luma", lumaShader)
            builder.child("cb", cbShader)
            builder.child("cr", crShader)
            builder.uniform("hlg", if (hlg) 1f else 0f)
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
