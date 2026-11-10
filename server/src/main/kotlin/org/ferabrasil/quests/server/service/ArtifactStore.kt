package org.ferabrasil.quests.server.service

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.ferabrasil.quests.server.config.S3Config

/**
 * Sealed-artifact storage (backend.md §6.3). Stores ciphertext only, reachable only
 * from :server on the internal network. Multipart upload / ranged read land in B4.
 */
interface ArtifactStore {
    /** Creates the bucket if needed. Called once on startup. */
    fun ensureReady()

    fun isHealthy(): Boolean
}

class MinioArtifactStore(private val cfg: S3Config) : ArtifactStore {
    private val client: MinioClient = MinioClient.builder()
        .endpoint(cfg.endpoint)
        .credentials(cfg.accessKey, cfg.secretKey)
        .build()

    override fun ensureReady() {
        if (!bucketExists()) {
            client.makeBucket(MakeBucketArgs.builder().bucket(cfg.bucket).build())
        }
    }

    override fun isHealthy(): Boolean = runCatching { bucketExists() }.getOrDefault(false)

    private fun bucketExists(): Boolean =
        client.bucketExists(BucketExistsArgs.builder().bucket(cfg.bucket).build())
}
