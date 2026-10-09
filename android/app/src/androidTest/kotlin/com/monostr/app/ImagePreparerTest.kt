package com.monostr.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.media.ImagePreparer
import com.monostr.app.media.ImageTarget
import com.monostr.app.media.UploadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.security.MessageDigest

/** Spec 5.1: what leaves the device is upright, small enough and free of metadata. No network, no activity. */
@RunWith(AndroidJUnit4::class)
class ImagePreparerTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preparer get() = ImagePreparer(ctx.contentResolver)
    private val files = ArrayList<File>()

    @After fun cleanUp() = files.forEach { it.delete() }

    private fun file(name: String) = File(ctx.cacheDir, "prep-${System.nanoTime()}-$name").also { files += it }

    private fun picture(w: Int, h: Int, format: Bitmap.CompressFormat, name: String, transparent: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (transparent) bitmap.eraseColor(Color.TRANSPARENT) else bitmap.eraseColor(Color.rgb(200, 120, 40))
        // a marker in the top-left corner, to see which way is up afterwards
        for (x in 0 until w / 4) for (y in 0 until h / 4) bitmap.setPixel(x, y, Color.rgb(0, 0, 255))
        return file(name).also { f -> f.outputStream().use { bitmap.compress(format, 95, it) } }
    }

    private fun prepare(f: File, target: ImageTarget = ImageTarget.NOTE) = runBlocking { preparer.prepare(Uri.fromFile(f).toString(), target) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun aPortraitPhotoWithGpsComesOutUprightAndWithoutMetadata() {
        val f = picture(3000, 2000, Bitmap.CompressFormat.JPEG, "portrait.jpg")
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,31/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "13/1,24/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_MODEL, "TestPhone 1")
            saveAttributes()
        }
        // the test is only worth something if the source really carries a position
        assertTrue(ExifInterface(f.absolutePath).getLatLong(FloatArray(2)))

        val out = prepare(f)

        assertEquals("image/jpeg", out.type)
        assertEquals(1365, out.width) // 2000 x 3000 after turning, fitted into 2048
        assertEquals(2048, out.height)
        val decoded = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size)
        assertEquals(out.width, decoded.width)
        assertEquals(out.height, decoded.height)
        // the marker was top-left in the stored picture; turned by 90 degrees it is top-right
        val topRight = decoded.getPixel(decoded.width - 20, 20)
        val topLeft = decoded.getPixel(20, 20)
        assertTrue("marker not top-right: ${Integer.toHexString(topRight)}", Color.blue(topRight) > 150 && Color.red(topRight) < 100)
        assertTrue("marker still top-left: ${Integer.toHexString(topLeft)}", Color.red(topLeft) > 150)
        val exif = ExifInterface(ByteArrayInputStream(out.bytes))
        assertFalse(exif.getLatLong(FloatArray(2)))
        assertEquals(null, exif.getAttribute(ExifInterface.TAG_MODEL))
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) in setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(sha256(out.bytes), out.sha256)
        assertNotNull(out.blurhash)
        assertEquals(28, out.blurhash!!.length) // 4 x 3 components
    }

    /** A source whose stored top-left marker must end up at [corner] after turning it upright by [orientation]. */
    private fun turned(orientation: Int, w: Int, h: Int, corner: String) {
        val f = picture(3000, 2000, Bitmap.CompressFormat.JPEG, "turned-$orientation.jpg")
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        val out = prepare(f)
        assertEquals(w to h, out.width to out.height)
        val decoded = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size)
        assertEquals(w to h, decoded.width to decoded.height)
        val corners = mapOf(
            "top-left" to decoded.getPixel(20, 20),
            "top-right" to decoded.getPixel(decoded.width - 20, 20),
            "bottom-left" to decoded.getPixel(20, decoded.height - 20),
            "bottom-right" to decoded.getPixel(decoded.width - 20, decoded.height - 20),
        )
        for ((name, pixel) in corners) {
            val marker = Color.blue(pixel) > 150 && Color.red(pixel) < 100
            assertEquals("orientation $orientation, $name: ${Integer.toHexString(pixel)}", name == corner, marker)
        }
    }

    @Test
    fun aPhotoTurnedTheOtherWayComesOutUpright() = turned(ExifInterface.ORIENTATION_ROTATE_270, 1365, 2048, "bottom-left")

    @Test
    fun aPhotoUpsideDownComesOutUpright() = turned(ExifInterface.ORIENTATION_ROTATE_180, 2048, 1365, "bottom-right")

    @Test
    fun twoPicturesArePreparedOneAfterTheOther() = runBlocking {
        val p = preparer
        val small = picture(400, 300, Bitmap.CompressFormat.JPEG, "after.jpg")
        val pipe = file("pipe.jpg")
        Os.mkfifo(pipe.absolutePath, "600".toInt(8))
        val first = async(Dispatchers.IO) { p.prepare(Uri.fromFile(pipe).toString(), ImageTarget.NOTE) }
        // The writing end opens only once the first prepare has the pipe open for reading: it is inside, reading.
        val writer = withContext(Dispatchers.IO) { openWriter(pipe) }
        val second = async(Dispatchers.IO) { p.prepare(Uri.fromFile(small).toString(), ImageTarget.NOTE) }
        val early = try {
            withTimeoutOrNull(2_000) { second.await() }
        } finally {
            // the first gets its bytes either way, so nothing is left hanging on the pipe
            withContext(Dispatchers.IO) {
                // a FileOutputStream on a bare descriptor does not close it, so close it here: that is the reader's end of file
                try { FileOutputStream(writer).write(small.readBytes()) } finally { Os.close(writer) }
            }
        }
        assertNull("the second picture was prepared while the first was still being read", early)
        assertEquals(400, first.await().width)
        assertEquals(400, second.await().width)
    }

    /** Opens [fifo] for writing once a reader has it open; fails instead of hanging when none comes. */
    private suspend fun openWriter(fifo: File): FileDescriptor {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            try {
                val fd = Os.open(fifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_WRONLY)
                return fd
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENXIO || System.currentTimeMillis() > deadline) throw e
                delay(10)
            }
        }
    }

    @Test
    fun aTruncatedPictureIsUnreadable() {
        val whole = picture(400, 300, Bitmap.CompressFormat.JPEG, "whole.jpg").readBytes()
        val cut = file("cut.jpg").also { it.writeBytes(whole.copyOf(64)) }
        try {
            prepare(cut)
            fail("expected Unreadable")
        } catch (e: UploadException.Unreadable) {
            // expected
        }
    }

    @Test
    fun aSmallPictureIsNotEnlarged() {
        val out = prepare(picture(100, 50, Bitmap.CompressFormat.JPEG, "small.jpg"))
        assertEquals(100, out.width)
        assertEquals(50, out.height)
    }

    @Test
    fun theTargetDecidesTheLongEdge() {
        val f = picture(2400, 1200, Bitmap.CompressFormat.JPEG, "wide.jpg")
        assertEquals(2048 to 1024, prepare(f, ImageTarget.NOTE).let { it.width to it.height })
        assertEquals(1500 to 750, prepare(f, ImageTarget.BANNER).let { it.width to it.height })
        assertEquals(800 to 400, prepare(f, ImageTarget.AVATAR).let { it.width to it.height })
    }

    @Test
    fun aPictureWithTransparencyStaysPng() {
        val out = prepare(picture(300, 200, Bitmap.CompressFormat.PNG, "clear.png", transparent = true))
        assertEquals("image/png", out.type)
        val decoded = BitmapFactory.decodeByteArray(out.bytes, 0, out.bytes.size)
        assertEquals(0, Color.alpha(decoded.getPixel(250, 150)))
    }

    @Test
    fun anOpaquePngBecomesJpeg() {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.RGB_565).apply { eraseColor(Color.GREEN) }
        val f = file("opaque.png").also { it.outputStream().use { o -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        assertEquals("image/jpeg", prepare(f).type)
    }

    @Test
    fun aGifGoesThroughUnchanged() {
        // a 1 x 1 GIF89a
        val gif = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
        val f = file("tiny.gif").also { it.writeBytes(gif) }
        val out = prepare(f)
        assertEquals("image/gif", out.type)
        assertArrayEquals(gif, out.bytes)
        assertEquals(1 to 1, out.width to out.height)
        assertEquals(sha256(gif), out.sha256)
    }

    @Test
    fun whatIsNoPictureOrNotThereIsUnreadable() {
        val garbage = file("garbage.jpg").also { it.writeText("this is not a picture") }
        for (source in listOf(Uri.fromFile(garbage).toString(), Uri.fromFile(File(ctx.cacheDir, "missing-${System.nanoTime()}.jpg")).toString())) {
            try {
                runBlocking { preparer.prepare(source, ImageTarget.NOTE) }
                fail("expected Unreadable for $source")
            } catch (e: UploadException.Unreadable) {
                // expected
            }
        }
    }
}
