package com.beamcast.receiver.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Picture enhancement on the TV's GPU: scales each frame up to the screen resolution and applies
 * contrast-adaptive sharpening, so low-resolution streams look crisper than with the TV's own
 * scaler. It is a fixed filter, not a neural network: old TV chips cannot run one in real time.
 */
@UnstableApi
class EnhanceEffect(private val screenWidth: Int, private val screenHeight: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            Program(screenWidth, screenHeight)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

    private class Program(private val screenWidth: Int, private val screenHeight: Int) : BaseGlShaderProgram(false, 1) {
        private val program = GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            program.setFloatsUniform("uTexel", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            return outputSize(inputWidth, inputHeight, screenWidth, screenHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        /** Largest output the filter renders, to keep old GPUs within budget. */
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080

        /** Input scaled up (never down) to fit the screen, keeping its aspect ratio; even dimensions. */
        fun outputSize(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Size {
            val maxW = minOf(maxOf(screenWidth, screenHeight), MAX_WIDTH)
            val maxH = minOf(minOf(screenWidth, screenHeight), MAX_HEIGHT)
            val scale = minOf(maxW.toFloat() / width, maxH.toFloat() / height)
            if (scale <= 1f) return Size(width, height)
            return Size((width * scale).toInt() / 2 * 2, (height * scale).toInt() / 2 * 2)
        }

        private const val VERTEX = """
            attribute vec4 aFramePosition;
            uniform vec2 uTexel;
            varying vec2 vCenter;
            varying vec2 vUp;
            varying vec2 vDown;
            varying vec2 vLeft;
            varying vec2 vRight;
            void main() {
              gl_Position = aFramePosition;
              vCenter = aFramePosition.xy * 0.5 + 0.5;
              vUp = vCenter + vec2(0.0, uTexel.y);
              vDown = vCenter - vec2(0.0, uTexel.y);
              vLeft = vCenter - vec2(uTexel.x, 0.0);
              vRight = vCenter + vec2(uTexel.x, 0.0);
            }
        """

        // Contrast-adaptive sharpening: sharpens flat detail strongly and already-contrasty edges
        // gently, which avoids the halos of a plain unsharp mask.
        private const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            varying vec2 vCenter;
            varying vec2 vUp;
            varying vec2 vDown;
            varying vec2 vLeft;
            varying vec2 vRight;
            void main() {
              vec4 center = texture2D(uTexSampler, vCenter);
              vec3 e = center.rgb;
              vec3 b = texture2D(uTexSampler, vUp).rgb;
              vec3 h = texture2D(uTexSampler, vDown).rgb;
              vec3 d = texture2D(uTexSampler, vLeft).rgb;
              vec3 f = texture2D(uTexSampler, vRight).rgb;
              vec3 mn = min(e, min(min(b, h), min(d, f)));
              vec3 mx = max(e, max(max(b, h), max(d, f)));
              vec3 amount = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.004)), 0.0, 1.0));
              vec3 w = amount * -0.125;
              vec3 color = (e + (b + h + d + f) * w) / (1.0 + 4.0 * w);
              gl_FragColor = vec4(clamp(color, 0.0, 1.0), center.a);
            }
        """
    }
}
