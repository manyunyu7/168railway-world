// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal object WorldCloud {
    const val ORIGIN = "https://168railway.com"
    private fun call(path: String, method: String, session: JSONObject? = null, body: ByteArray? = null,
                     headers: Map<String,String> = emptyMap()): ByteArray {
        val conn=URL("$ORIGIN/api/studio-native/$path").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects=false
            conn.requestMethod=method
            conn.connectTimeout=15000
            conn.readTimeout=30000
            conn.setRequestProperty("Accept","application/json")
            if(session!=null) {
                conn.setRequestProperty("Authorization","Bearer "+session.getString("access_token"))
                conn.setRequestProperty("X-Studio-User",session.getString("userId"))
            }
            for ((k,v) in headers) conn.setRequestProperty(k,v)
            if(body!=null) {
                conn.doOutput=true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            return when(conn.responseCode) {
                in 200..299 -> WorldDocuments.read(conn.inputStream)
                401 -> throw IOException("Sesi habis. Hubungkan akun lagi.")
                409 -> throw IOException("Arsip di akun berubah. Unduh versi terbaru atau unggah sebagai salinan baru.")
                404 -> throw IOException("Arsip atau layanan akun belum tersedia.")
                else -> throw IOException("Permintaan arsip gagal (HTTP ${conn.responseCode}).")
            }
        } finally { conn.disconnect() }
    }
    private fun json(bytes: ByteArray) = JSONObject(String(bytes,Charsets.UTF_8))
    fun exchange(code: String, verifier: String): JSONObject = json(call("token","POST",body=JSONObject()
        .put("code",code).put("verifier",verifier).toString().toByteArray(),headers=mapOf("Content-Type" to "application/json")))
    fun list(session: JSONObject) = json(call("worlds","GET",session)).getJSONArray("worlds")
    fun read(session: JSONObject,id: String) = call("worlds/${UUID.fromString(id)}","GET",session)
    fun put(session: JSONObject,id: String,version: Int,title: String,bytes: ByteArray): JSONObject =
        json(call("worlds/${UUID.fromString(id)}","PUT",session,bytes,mapOf("Content-Type" to "application/octet-stream",
            "X-World-Version" to version.toString(),"X-World-Name" to URLEncoder.encode(title,"UTF-8").replace("+","%20")))).getJSONObject("world")
    fun logout(session: JSONObject) { call("logout","POST",session) }
}
