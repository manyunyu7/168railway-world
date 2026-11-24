// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Color
import kotlin.math.*

/** Same 256 OKLab samples and train-share exponent as game/palette.ts. */
internal object PassengerRamps {
    val trainShare = build(0xff4d4d,0x4d4dff)
    val accessible = build(0xf2c200,0x0072b2)

    private fun lab(color: Int): DoubleArray {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 255) / 255.0
            return if (value <= .04045) value/12.92 else ((value+.055)/1.055).pow(2.4)
        }
        val r=channel(16); val g=channel(8); val b=channel(0)
        val l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b)
        val m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b)
        val s=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b)
        return doubleArrayOf(.2104542553*l+.793617785*m-.0040720468*s,1.9779984951*l-2.428592205*m+.4505937099*s,.0259040371*l+.7827717662*m-.808675766*s)
    }

    private fun build(from: Int, to: Int): IntArray {
        val start=lab(from); val end=lab(to)
        fun gamma(raw: Double): Int {
            val x=raw.coerceIn(0.0,1.0)
            return (255*(if(x<=.0031308) 12.92*x else 1.055*x.pow(1/2.4)-.055)).roundToInt()
        }
        return IntArray(256) { index ->
            val t=(index/255.0).pow(ln(.5)/ln(.3))
            fun at(i:Int) = start[i]+(end[i]-start[i])*t
            val light=at(0); val a=at(1); val b=at(2)
            val l=(light+.3963377774*a+.2158037573*b).pow(3)
            val m=(light-.1055613458*a-.0638541728*b).pow(3)
            val s=(light-.0894841775*a-1.291485548*b).pow(3)
            Color.rgb(gamma(4.0767416621*l-3.3077115913*m+.2309699292*s),gamma(-1.2684380046*l+2.6097574011*m-.3413193965*s),gamma(-.0041960863*l-.7034186147*m+1.7076147010*s))
        }
    }
}
