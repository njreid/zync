package dev.njr.zync

import dev.njr.zync.replica.DriveOcr
import dev.njr.zync.replica.GoogleDriveOcr
import dev.njr.zync.replica.LocalBlobStore
import dev.njr.zync.replica.OcrProcessor
import java.io.File

/**
 * The capture/OCR construction cluster, extracted out of [ZyncApp] to keep that class from
 * growing without bound: the local blob store, the Drive OCR transport, and the
 * WorkManager-free OCR pipeline ([OcrWorker][dev.njr.zync.sync.OcrWorker] drives) that binds
 * them to the op-log. Pure code motion — each member's construction is unchanged from its
 * former home in [ZyncApp], just relocated behind one more layer of `by lazy` indirection.
 */
class CaptureGraph(app: ZyncApp) {
    val localBlobs: LocalBlobStore by lazy { LocalBlobStore(File(app.filesDir, "blobs")) }

    /** Scanned-doc OCR: Drive transport + the WorkManager-free pipeline the OcrWorker drives. */
    val driveOcr: DriveOcr by lazy { GoogleDriveOcr(app) }
    val ocrProcessor: OcrProcessor by lazy {
        OcrProcessor(localBlobs, app.opWriter, driveOcr, app.opStore, onChanged = { app.contentChanges.notifyChanged() })
    }
}
