@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
package blbl.cat3399.feature.player.engine

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import blbl.cat3399.core.cdn.CdnByteStream
import blbl.cat3399.core.cdn.CdnResumableStream
import blbl.cat3399.core.cdn.CdnRoute

/** Attached only to exact PlayURL media routes; subtitle/live/API factories never use it. */
internal class AcceleratedCdnDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val route: CdnRoute,
    private val passthrough: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = AcceleratedCdnDataSource(upstream, route, passthrough)
}

private class AcceleratedCdnDataSource(
    private val factory: DataSource.Factory,
    private val route: CdnRoute,
    private val passthrough: DataSource.Factory,
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var stream: CdnResumableStream? = null
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        close()
        if (route.candidates.none { it.url == dataSpec.uri.toString() }) {
            val source = passthrough.createDataSource()
            listeners.forEach { source.addTransferListener(it) }
            active = source
            return try { source.open(dataSpec) } catch (e: Exception) { close(); throw e }
        }
        val reader = CdnResumableStream(route, dataSpec.position, dataSpec.length) { candidate ->
            val source = factory.createDataSource()
            listeners.forEach { source.addTransferListener(it) }
            object : CdnByteStream {
                override fun open(consumed: Long): Long {
                    active = source
                    return source.open(dataSpec.subrange(consumed).buildUpon().setUri(Uri.parse(candidate.url)).build())
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
                override fun close() = source.close()
            }
        }
        stream = reader
        return try { reader.open() } catch (e: Exception) { close(); throw e }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream?.read(buffer, offset, length) ?: checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() {
        if (stream != null) stream?.close() else active?.close()
        stream = null
        active = null
    }
}
