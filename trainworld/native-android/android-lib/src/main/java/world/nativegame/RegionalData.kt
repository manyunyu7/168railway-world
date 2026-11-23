package world.nativegame

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Hash-addressed optional data. Downloads run on the analysis executor. */
internal object RegionalData {
    private data class Asset(val path: String,val name: String,val length: Long,val sha: String)
    private val assets = listOf(
        Asset("studio-native-data/2026-11-18/market-893a04a231b47f82.json.gz","index.gz",3135062,"893a04a231b47f82e4245f2974d7d927cb9cbfc8a687042bbb18ce3e0ab69a7e"),
        Asset("studio-data/2026-11-18/market-drives.bin","drives.bin",14468710,"ff3d9e82034f7489840b5736c31f3ef51b7b9c4be8cddecd6ff4e871ee6751be")
    )
    fun load(root: File): List<File> {
        val dir = File(root,"world-data/2026-11-18/regional")
        check(dir.isDirectory || dir.mkdirs())
        return assets.map { asset ->
            val file = File(dir,asset.name)
            if (!matches(file,asset)) {
                val temp = File.createTempFile(asset.name,".part",dir)
                val conn = URL("https://168railway.com/" + asset.path).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout=15000;conn.readTimeout=45000;conn.instanceFollowRedirects=false
                    conn.setRequestProperty("Accept-Encoding","identity")
                    check(conn.responseCode==200) { "HTTP " + conn.responseCode }
                    conn.inputStream.use { input -> temp.outputStream().use { out ->
                        val buf=ByteArray(65536)
                        var total=0L
                        while(true){
                            val n=input.read(buf); if(n<0)break
                            total+=n;check(total<=asset.length){"Ukuran data regional berbeda"}
                            out.write(buf,0,n)
                        }
                    }}
                    check(matches(temp,asset)){"Sidik data regional berbeda"}
                    check(temp.renameTo(file)){"Penyimpanan regional gagal"}
                } finally {conn.disconnect();temp.delete()}
            }
            file
        }
    }
    private fun matches(f: File, a: Asset): Boolean {
        if(!f.isFile || f.length()!=a.length)return false
        val digest=MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf=ByteArray(65536)
            while(true){val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}==a.sha
    }
}
