// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

internal object StudioPendingSaves {
    private val generationLock = Any()
    fun hash(bytes: ByteArray): String = android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP)
    private const val JOB = 1682608
    private fun pending(c: Context) = c.getSharedPreferences("studio-host-pending", Context.MODE_PRIVATE)
    private fun conflicts(c: Context) = c.getSharedPreferences("studio-host-conflicts", Context.MODE_PRIVATE)
    fun conflicted(c: Context,id: String) = conflicts(c).getBoolean(id,false)
    private fun failures(c: Context) = c.getSharedPreferences("studio-host-save-errors", Context.MODE_PRIVATE)
    fun failure(c: Context,id: String) = failures(c).getString(id,null)
    private fun links(c: Context) = c.getSharedPreferences("studio-host-worlds", Context.MODE_PRIVATE)
    private fun host(c: Context) = c.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    fun user(c: Context): String? = runCatching {
        JSONObject(host(c).getString("flutter.railway_user_data", null) ?: return null).getString("id")
    }.getOrNull()

    fun session(c: Context, owner: String): JSONObject {
        val token = host(c).getString("flutter.railway_auth_token", null)
        check(user(c) == owner && !token.isNullOrBlank()) { "Masuk kembali dengan akun pemilik dunia." }
        return JSONObject().put("mobileHost", true).put("userId", owner).put("access_token", token)
    }

    fun registerCopy(c: Context, slot: WorldDocuments.Slot, owner: String) {
        val link = JSONObject().put("owner",owner).put("id",UUID.randomUUID().toString()).put("version",0).put("name",slot.title)
        check(links(c).edit().putString(slot.id,link.toString()).commit())
        markDirty(c,slot.id)
    }

    fun markDirty(c: Context, slot: String) {
        if (!links(c).contains(slot)) return
        failures(c).edit().remove(slot).apply()
        synchronized(generationLock) {
            pending(c).edit().putString(slot, UUID.randomUUID().toString()).apply()
        }
        schedule(c)
    }

    fun schedule(c: Context) {
        if (pending(c).all.isEmpty()) return
        val scheduler = c.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) == null) {
            scheduler.schedule(JobInfo.Builder(JOB, ComponentName(c, StudioSaveJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(3000)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }

    fun pendingIds(c: Context) = pending(c).all.keys.toList()

    @Synchronized
    fun upload(c: Context, slot: String, owner: String, title: String, copy: Boolean = false) {
        val metadata = JSONObject(links(c).getString(slot, null) ?: return)
        check(metadata.getString("owner") == owner) { "Dunia ini milik akun lain." }
        val account = session(c, owner)
        val revision = pending(c).getString(slot, null)
        val bytes = AtomicFile(WorldDocuments.path(c,slot)).openRead().use { WorldDocuments.read(it) }
        val hash = hash(bytes)
        val name = metadata.optString("name").ifBlank { title }
        if (copy || hash != metadata.optString("hash")) {
            val id = if (!copy) metadata.getString("id") else {
                if (metadata.optString("copyHash") != hash || !metadata.has("copyId")) {
                    metadata.put("copyHash",hash).put("copyId",UUID.randomUUID().toString())
                    check(links(c).edit().putString(slot,metadata.toString()).commit())
                }
                metadata.getString("copyId")
            }
            val base = if (copy) 0 else metadata.getInt("version")
            val row = try {
                WorldCloud.put(account,id,base,if (copy) name.take(90) + " (salinan)" else name,bytes)
            } catch (conflict: WorldCloud.Conflict) {
                // A lost PUT response is safe to acknowledge only for byte-identical remote contents.
                val worlds = WorldCloud.list(account)
                val current = (0 until worlds.length()).map { worlds.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
                if (current == null || !WorldCloud.read(account,id,current.getInt("version")).contentEquals(bytes)) {
                    conflicts(c).edit().putBoolean(slot,true).apply()
                    throw conflict
                }
                current
            } catch (rejected: WorldCloud.Rejected) {
                failures(c).edit().putString(slot,rejected.message).apply()
                throw rejected
            }
            metadata.remove("copyId")
            metadata.remove("copyHash")
            metadata.put("id",row.getString("id")).put("version",row.getInt("version"))
                .put("name",row.getString("name")).put("hash",hash)
            check(links(c).edit().putString(slot,metadata.toString()).commit()) { "Gagal mencatat versi Studio." }
        }
        failures(c).edit().remove(slot).apply()
        conflicts(c).edit().remove(slot).apply()
        synchronized(generationLock) {
            if (pending(c).getString(slot,null) == revision) pending(c).edit().remove(slot).commit()
        }
    }

    fun flush(c: Context): Boolean {
        val owner = user(c) ?: return false
        val slots = WorldDocuments.list(c).associateBy { it.id }
        var retry = false
        for (id in pendingIds(c)) {
            val slot = slots[id] ?: continue
            if (conflicted(c,id) || failure(c,id) != null) continue
            try {
                upload(c,id,owner,slot.title)
            } catch (_: WorldCloud.Conflict) {
                // Keep the local snapshot. The player can choose Save as a new world.
            } catch (_: WorldCloud.Rejected) {
                // Keep a recoverable local world; quota and validation errors need player action.
            } catch (_: IOException) {
                retry = true
            } catch (_: IllegalStateException) {
                // Retry after a new login or the next visit to the library.
            }
        }
        return retry || pendingIds(c).any { it in slots && !conflicted(c,it) && failure(c,it) == null && !host(c).getString("flutter.railway_auth_token",null).isNullOrBlank() }
    }
}

class StudioSaveJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            val retry = runCatching { StudioPendingSaves.flush(this) }.getOrDefault(true)
            jobFinished(params,retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
