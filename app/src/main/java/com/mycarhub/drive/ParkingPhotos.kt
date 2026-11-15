package com.mycarhub.drive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.mycarhub.assistant.core.ParkingPreview
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 주차 사진 (기기 안 앱 전용 저장소 files/parking_photos, 다른 앱은 읽을 수 없다).
 *
 * - 원본을 그대로 두지 않고 긴 변 [MAX_SIDE]px JPEG로 다시 저장한다. 다시 저장하면 EXIF(촬영 위치·기기 정보)가 빠진다.
 * - 동기화로는 원본을 보내지 않고 작은 미리보기([ParkingPreview.MAX_BYTES] 이하, 기기에서 암호화)만 보낸다.
 * - 다른 기기에서 받은 미리보기는 레코드 id의 해시로 이름을 만든다 (받은 데이터의 이름·경로를 쓰지 않는다).
 * - 지금·이전 주차 기록이 쓰지 않는 사진은 지운다 ([cleanUp]).
 */
object ParkingPhotos {

    private const val TAG = "ParkingPhotos"
    private const val DIR = "parking_photos"
    private const val MAX_SIDE = 1600
    private const val QUALITY = 85
    private const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    private const val THUMB_SUFFIX = "-t.jpg"

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    /** 기기 안 사진 파일. 이름이 안전하지 않거나 없으면 null. */
    fun file(context: Context, name: String?): File? {
        if (!ParkingPreview.isSafeFileName(name)) return null
        return File(dir(context), name!!).takeIf { it.isFile }
    }

    /**
     * 촬영·선택한 사진을 정리해 저장하고 파일 이름을 돌려준다 (실패하면 null). 메인 스레드에서 부르지 않는다.
     * 방향(EXIF)을 반영해 돌리고, 줄이고, EXIF 없이 다시 저장한다. 같은 이름의 동기화용 미리보기도 만든다.
     */
    fun import(context: Context, input: InputStream): String? = try {
        val bytes = readLimited(input) ?: return null
        val bitmap = decodeScaled(bytes, MAX_SIDE) ?: return null
        val rotated = rotate(bitmap, orientationOf(bytes))
        val name = UUID.randomUUID().toString() + ".jpg"
        writeAtomically(File(dir(context), name), compress(rotated, QUALITY))
        previewBytes(rotated)?.let { writeAtomically(File(dir(context), thumbName(name)), it) }
        rotated.recycle()
        name
    } catch (e: Exception) {
        Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "import failed: out of memory")
        null
    }

    /** 동기화로 보낼 미리보기 (JPEG). 받은 미리보기 파일이면 그대로, 원본이면 같이 만들어 둔 작은 파일. */
    fun previewFor(context: Context, name: String?): ByteArray? {
        if (!ParkingPreview.isSafeFileName(name)) return null
        val ready = File(dir(context), if (name!!.startsWith(REMOTE_PREFIX)) name else thumbName(name))
        val bytes = runCatching { ready.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (bytes != null && ParkingPreview.isAcceptable(bytes)) return bytes
        // 미리보기가 없으면 원본에서 다시 만든다
        val original = file(context, name) ?: return null
        val bitmap = runCatching { decodeScaled(original.readBytes(), PREVIEW_SIDES.first()) }.getOrNull() ?: return null
        return previewBytes(bitmap)?.also { runCatching { writeAtomically(ready, it) } }.also { bitmap.recycle() }
    }

    /** 다른 기기에서 받은 미리보기를 저장한다. 쓸 만한 JPEG가 아니면 저장하지 않고 null. */
    fun saveRemotePreview(context: Context, recordId: String, bytes: ByteArray): String? {
        if (!ParkingPreview.isAcceptable(bytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val name = REMOTE_PREFIX + sha256(recordId).take(32) + ".jpg"
        return try {
            writeAtomically(File(dir(context), name), bytes)
            name
        } catch (e: Exception) {
            null
        }
    }

    /** [keep] 외의 사진을 지운다 (지금·이전 주차 기록의 사진만 남긴다). */
    fun cleanUp(context: Context, keep: Set<String>) {
        val keepAll = keep.flatMap { listOf(it, thumbName(it)) }.toSet() + CAPTURE_TMP
        // 방금 찍어 아직 저장 버튼을 누르지 않은 사진은 남긴다 (한 시간 뒤 정리)
        val fresh = System.currentTimeMillis() - FRESH_KEEP_MS
        dir(context).listFiles()?.forEach { f ->
            if (f.name !in keepAll && !f.name.endsWith(".tmp") && f.lastModified() < fresh) f.delete()
        }
    }

    /** 사진 파일을 메모리에 맞게 줄여 읽는다 (화면 표시용) */
    fun load(context: Context, name: String?, maxSide: Int): Bitmap? {
        val f = file(context, name) ?: return null
        return runCatching { decodeScaled(f.readBytes(), maxSide) }.getOrNull()
    }

    // region 카메라 앱이 사진을 쓸 임시 파일 (CaptureProvider)

    const val CAPTURE_TMP = "capture.tmp"

    fun captureFile(context: Context): File = File(dir(context), CAPTURE_TMP)

    fun captureUri(context: Context): Uri = Uri.parse("content://${context.packageName}.parkingphoto/capture")

    // endregion

    private const val REMOTE_PREFIX = "r-"
    private const val FRESH_KEEP_MS = 60 * 60_000L
    private val PREVIEW_SIDES = listOf(320, 256, 200, 160)
    private val PREVIEW_QUALITIES = listOf(60, 45, 30)

    private fun thumbName(name: String) = name.removeSuffix(".jpg") + THUMB_SUFFIX

    private fun previewBytes(source: Bitmap): ByteArray? {
        for (side in PREVIEW_SIDES) {
            val scaled = scaleDown(source, side)
            for (q in PREVIEW_QUALITIES) {
                val out = compress(scaled, q)
                if (out.size <= ParkingPreview.MAX_BYTES) {
                    if (scaled !== source) scaled.recycle()
                    return out
                }
            }
            if (scaled !== source) scaled.recycle()
        }
        return null
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_INPUT_BYTES) return null
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = scaleDown(decoded, maxSide)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun orientationOf(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bitmap
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("rename failed")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * 카메라 앱(ACTION_IMAGE_CAPTURE)이 촬영한 사진을 쓸 곳. 외부에 공개되지 않으며(exported=false),
 * 촬영을 요청할 때만 그 카메라 앱에 이 주소 하나의 쓰기 권한을 잠시 준다. 앱에 카메라 권한이 필요 없다.
 * 쓸 수 있는 파일은 [ParkingPhotos.captureFile] 하나뿐이다.
 */
class ParkingCaptureProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun target(uri: Uri): java.io.File? {
        val ctx = context ?: return null
        return if (uri.pathSegments == listOf("capture")) ParkingPhotos.captureFile(ctx) else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = target(uri) ?: throw java.io.FileNotFoundException()
        val flags = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun getType(uri: Uri): String? = if (target(uri) != null) "image/jpeg" else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val file = target(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map<String, Any> { if (it == OpenableColumns.DISPLAY_NAME) "parking.jpg" else file.length() }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
