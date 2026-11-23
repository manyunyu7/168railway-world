// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.graphics.Path
import kotlin.math.*

/** Geometry from the OpenMapTiles layers used by Studio. Tile coordinates remain local to
 * avoid rounding large world coordinates at street zoom. */
internal class StudioVectorTile {
    enum class Style { GREEN, RESIDENTIAL, INDUSTRY, WATER, RIVER, BUILDING, FOOT, ROAD, MAIN, MOTORWAY, RAIL }
    data class Label(val name:String,val x:Float,val y:Float,val angle:Float,val major:Boolean)
    val paths=java.util.EnumMap<Style,Path>(Style::class.java)
    val labels=ArrayList<Label>()

    private class Proto(val bytes:ByteArray,var p:Int=0,val end:Int=bytes.size) {
        fun number():Int {
            var result=0
            for (bit in 0..63 step 7) { require(p<end); val b=bytes[p++].toInt() and 255
                if(bit<32)result=result or ((b and 127) shl bit)
                if(b<128)return result }
            error("Invalid varint")
        }
        fun block():Proto { val n=number(); require(n>=0 && n<=end-p); val slice=Proto(bytes,p,p+n); p+=n; return slice }
        fun string():String { val v=block();return String(bytes,v.p,v.end-v.p,Charsets.UTF_8) }
        fun skip(tag:Int) { when(tag and 7) {0->number(); 1->{require(end-p>=8);p+=8}; 2->block(); 5->{require(end-p>=4);p+=4};else->error("Invalid protobuf wire type")} }
    }
    private fun style(layer:String,k:String):Style? = when(layer) {
        "water" -> Style.WATER
        "waterway" -> Style.RIVER
        "building" -> Style.BUILDING
        "park" -> Style.GREEN
        "landcover","landuse" -> when(k) {
            "wood","forest","grass","park","grassland","farmland","cemetery","garden","recreation_ground"->Style.GREEN
            "residential","suburb","neighbourhood"->Style.RESIDENTIAL
            "industrial","commercial","retail"->Style.INDUSTRY
            else->null
        }
        "transportation"->when(k) {
            "motorway","trunk" -> Style.MOTORWAY
            "primary","secondary","tertiary" -> Style.MAIN
            "rail","transit" -> Style.RAIL
            "path","track","footway","cycleway","steps" -> Style.FOOT
            "minor","service","residential","unclassified","raceway","busway" -> Style.ROAD
            else->null
        }
        else -> null
    }
    private fun layer(block:Proto) {
        var name=""; var extent=4096
        val keys=ArrayList<String>(); val vals=ArrayList<String?>(); val features=ArrayList<Proto>()
        while(block.p<block.end) { val tag=block.number()
            when(tag) {10->name=block.string();18->features.add(block.block());26->keys.add(block.string())
                34->{ val v=block.block(); var str:String?=null
                    while(v.p<v.end){val t=v.number();if(t==10)str=v.string() else v.skip(t)}; vals.add(str) }
                40->extent=block.number();else->block.skip(tag)} }
        if(name !in layers || extent !in 1..65536)return
        for(f in features) {
            var attrs:Proto?=null;var coords:Proto?=null;var type=0
            while(f.p<f.end){val tag=f.number();when(tag){18->attrs=f.block();24->type=f.number();34->coords=f.block();else->f.skip(tag)}}
            val tags=attrs;var klass="";var text:String?=null
            if(tags!=null)while(tags.p<tags.end){val key=keys.getOrNull(tags.number());val value=vals.getOrNull(tags.number())
                if(key=="class" && value!=null)klass=value
                if(key=="name" && !value.isNullOrBlank())text=value }
            val geom=coords?:continue
            val chosen=style(name,klass)
            val forLabel=name=="transportation_name" && type==2 && !text.isNullOrBlank()
            if(chosen==null && !forLabel)continue
            if(type!=2 && type!=3)continue
            val path=chosen?.let { paths.getOrPut(it){Path()} }
            var x=0;var y=0;var best=0f;var label:Label?=null
            val factor=4096f/extent
            while(geom.p<geom.end) {
                val word=geom.number();val cmd=word and 7;val count=word ushr 3
                require(count>0 && count<=geom.end+1)
                if(cmd==7){path?.close();continue}
                require(cmd==1 || cmd==2)
                repeat(count){
                    val prevX=x;val prevY=y
                    val dx=geom.number();val dy=geom.number()
                    x+=(dx ushr 1) xor -(dx and 1);y+=(dy ushr 1) xor -(dy and 1)
                    if(cmd==1)path?.moveTo(x*factor,y*factor) else {
                        path?.lineTo(x*factor,y*factor)
                        if(forLabel){val span=hypot((x-prevX).toFloat(),(y-prevY).toFloat())
                            if(span>best){best=span;var angle=Math.toDegrees(atan2((y-prevY).toDouble(),(x-prevX).toDouble())).toFloat()
                                if(angle>90)angle-=180 else if(angle< -90)angle+=180
                                label=Label(text!!,(x+prevX)*.5f*factor,(y+prevY)*.5f*factor,angle,klass in majorRoads)}}
                    }
                }
            }
            label?.let {if(it.x in 0f..4096f && it.y in 0f..4096f)labels.add(it)}
        }
    }
    companion object {
        private val majorRoads=setOf("primary","secondary","tertiary","trunk","motorway")
        private val layers=setOf("transportation","transportation_name","water","waterway","building","landuse","landcover","park")
        fun decode(bytes:ByteArray):StudioVectorTile {
            val result=StudioVectorTile();val tile=Proto(bytes)
            while(tile.p<tile.end){val tag=tile.number();if(tag==26)result.layer(tile.block()) else tile.skip(tag)}
            return result
        }
    }
}
