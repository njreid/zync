package dev.njr.zync.server.blob

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * S3-backed [BlobStore] (prod). Because keys are content-addressed, the
 * exists-then-put in [putIfAbsent] is race-safe: two writers of the same content
 * compute the same key and write identical bytes. Credentials/region come from the
 * default provider chain (the EC2 instance role). Works against MinIO too.
 *
 * NOTE: the MinIO integration test is deferred until infra is available; the
 * addressing/dedupe/size/route logic is covered via [InMemoryBlobStore].
 */
class S3BlobStore(
    private val s3: S3Client,
    private val bucket: String,
) : BlobStore {

    override fun exists(key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (_: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) false else throw e
    }

    override fun putIfAbsent(key: String, bytes: ByteArray): Boolean {
        if (exists(key)) return false
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes(bytes),
        )
        return true
    }

    override fun get(key: String): ByteArray? = try {
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
    } catch (_: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw e
    }
}
