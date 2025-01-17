package com.xayah.core.network.client

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.io.InputStreamByteChunkProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.SmbVersion
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.network.R
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.toPathList
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.PickYouLauncher
import com.xayah.libpickyou.ui.model.PickerType
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Paths
import kotlin.io.path.pathString


class SMBClientImpl(private val entity: CloudEntity, private val extra: SMBExtra) : CloudClient {
    private var client: SMBClient? = null
    private var session: Session? = null
    private var share: Share? = null
    private var shareName: String = ""
    private var availableShares = listOf<String>()

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "SMBClientImpl" to msg() }
        msg()
    }

    companion object {
        // Ref: https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-srvs/6069f8c0-c93f-43a0-a5b4-7ed447eb4b84
        private const val STYPE_SPECIAL: Int = (0x80000000).toInt()
    }

    private fun withClient(block: (client: SMBClient) -> Unit) = run {
        if (client == null) throw NullPointerException("Client is null.")
        block(client!!)
    }

    private fun withSession(block: (session: Session) -> Unit) = run {
        if (session == null) throw NullPointerException("Session is null.")
        block(session!!)
    }

    private fun withDiskShare(block: (diskShare: DiskShare) -> Unit) = run {
        if (share == null) throw NullPointerException("Share is null.")
        block(share!! as DiskShare)
    }

    private fun setShare(share: String?) = withSession { session ->
        if (share == null) {
            this.share = null
            this.shareName = ""
        } else {
            this.share = session.connectShare(share)
            this.shareName = share
        }
    }

    private fun SmbVersion.toDialect() = when (this) {
        SmbVersion.SMB_2_0_2 -> SMB2Dialect.SMB_2_0_2
        SmbVersion.SMB_2_1 -> SMB2Dialect.SMB_2_1
        SmbVersion.SMB_3_0 -> SMB2Dialect.SMB_3_0
        SmbVersion.SMB_3_0_2 -> SMB2Dialect.SMB_3_0_2
        SmbVersion.SMB_3_1_1 -> SMB2Dialect.SMB_3_1_1
    }

    override fun connect() {
        val dialects = extra.version.map { it.toDialect() }
        val config = SmbConfig.builder()
            .withDialects(dialects)
            .build()
        client = SMBClient(config).apply {
            connect(entity.host, extra.port).also { connection ->
                log { "Dialect: ${connection.connectionContext.negotiatedProtocol.dialect.name}" }
                AuthenticationContext(entity.user, entity.pass.toCharArray(), extra.domain).also { authentication ->
                    session = connection.authenticate(authentication)
                    if (extra.share.isNotEmpty()) setShare(extra.share)
                    withSession { _ ->
                        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                        val serverService = ServerService(transport)
                        val shares = serverService.shares1
                        availableShares = shares.filter { (it.type and STYPE_SPECIAL == STYPE_SPECIAL).not() }.map { it.netName }
                    }
                }
            }
        }
    }

    override fun disconnect() {
        runCatching {
            withDiskShare { diskShare ->
                diskShare.close()
            }
            withClient { client ->
                client.close()
            }
        }
        share = null
        client = null
    }

    private fun exists(src: String): Boolean {
        var exists = false
        withDiskShare { diskShare ->
            if (diskShare.fileExists(src)) {
                exists = true
                return@withDiskShare
            }
            if (diskShare.folderExists(src)) {
                exists = true
                return@withDiskShare
            }
        }
        return exists
    }

    override fun mkdir(dst: String) = withDiskShare { diskShare ->
        log { "mkdir: $dst" }
        if (exists(dst).not()) diskShare.mkdir(dst)
    }

    override fun mkdirRecursively(dst: String) {
        val dirs = dst.split("/")
        var currentDir = ""
        for (i in dirs) {
            currentDir += "/$i"
            currentDir = currentDir.trimStart('/')
            if (exists(currentDir).not()) mkdir(currentDir)
        }
    }

    override fun upload(src: String, dst: String) = withDiskShare { diskShare ->
        val name = Paths.get(src).fileName
        val dstPath = "$dst/$name"
        log { "upload: $src to $dstPath" }
        val dstFile = diskShare.openFile(
            dstPath,
            setOf(
                AccessMask.FILE_WRITE_DATA,
                AccessMask.FILE_WRITE_ATTRIBUTES,
                AccessMask.FILE_WRITE_EA,
            ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            setOf(SMB2CreateOptions.FILE_RANDOM_ACCESS)
        )
        val srcFile = File(src)
        val srcInputStream = FileInputStream(srcFile)
        dstFile.write(InputStreamByteChunkProvider(srcInputStream))
        srcInputStream.close()
        dstFile.close()
    }

    override fun download(src: String, dst: String) = withDiskShare { diskShare ->
        val name = Paths.get(src).fileName
        val dstPath = "$dst/$name"
        log { "download: $src to $dstPath" }
        val dstOutputStream = File(dstPath).outputStream()
        val srcFile = diskShare.openFile(
            src,
            setOf(
                AccessMask.FILE_READ_DATA,
                AccessMask.FILE_READ_ATTRIBUTES,
                AccessMask.FILE_READ_EA,
            ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN_IF,
            setOf(SMB2CreateOptions.FILE_RANDOM_ACCESS)
        )
        srcFile.read(dstOutputStream)
        srcFile.close()
        dstOutputStream.close()
    }

    override fun deleteFile(src: String) = withDiskShare { diskShare ->
        log { "deleteFile: $src" }
        diskShare.rm(src)
    }

    override fun removeDirectory(src: String) = withDiskShare { diskShare ->
        log { "removeDirectory: $src" }
        diskShare.rmdir(src, true)
    }

    override fun deleteRecursively(src: String) = withDiskShare { diskShare ->
        if (diskShare.fileExists(src)) deleteFile(src)
        else if (diskShare.folderExists(src)) removeDirectory(src)
        else throw IOException("$src not found.")
    }

    /**
     * @param src "$shareName/path"
     */
    override fun listFiles(src: String): DirChildrenParcelable {
        if (src.isEmpty()) {
            setShare(null)
        } else {
            val sharePrefix = src.split("/").getOrNull(1) ?: ""
            withSession {
                setShare(sharePrefix)
            }
        }

        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        if (share != null) {
            withDiskShare { diskShare ->
                // Remove share name
                val srcPath = src.replaceFirst("/${shareName}", "")
                val clientFiles = diskShare.list(srcPath)
                for (file in clientFiles) {
                    if (file.fileName == "." || file.fileName == "..") continue
                    val path = if (srcPath.isEmpty()) file.fileName else "${srcPath.trimEnd('/')}/${file.fileName}"
                    val creationTime = file.creationTime.toEpochMillis()
                    val fileParcelable = FileParcelable(file.fileName, creationTime)
                    if (diskShare.folderExists(path)) directories.add(fileParcelable)
                    else files.add(fileParcelable)
                }
            }
        } else if (availableShares.isEmpty()) {
            throw SMBRuntimeException("Share is null and there are no other available shares.")
        } else {
            directories.addAll(availableShares.map { FileParcelable(it, 0L) })
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    /**
     * @param src "path" without "$shareName"
     */
    override fun size(src: String): Long {
        var size = 0L
        withDiskShare { diskShare ->
            if (diskShare.folderExists(src)) {
                val files = listFiles("/${shareName}/$src")
                for (i in files.files) {
                    size += diskShare.getFileInformation("${src}/${i.name}").standardInformation.endOfFile
                }
                for (i in files.directories) {
                    size("${src}/${i.name}")
                }
            } else if (diskShare.fileExists(src)) {
                size += diskShare.getFileInformation(src).standardInformation.endOfFile
            }
        }
        log { "size: $size, $src" }
        return size
    }

    override suspend fun testConnection() {
        connect()
        disconnect()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<SMBExtra>()!!
        connect()
        PickYouLauncher.apply {
            val prefix = "${context.getString(R.string.cloud)}:"
            sTraverseBackend = { listFiles(it.pathString.replaceFirst(prefix, "")) }
            sTitle = context.getString(R.string.select_target_directory)
            sPickerType = PickerType.DIRECTORY
            sLimitation = 1
            sRootPathList = listOf(prefix)
            sDefaultPathList = if (extra.share.isNotEmpty()) listOf(prefix, extra.share) else listOf(prefix)

        }
        withMainContext {
            val pathList = PickYouLauncher.awaitPickerOnce(context)
            pathList.firstOrNull()?.also { pathString ->
                val pathSplit = pathString.toPathList().toMutableList()
                // Remove “$Cloud:/$share”
                pathSplit.removeFirst()
                val share = pathSplit.removeFirst()
                val remote = pathSplit.toPathString()
                onSet(remote, GsonUtil().toJson(extra.copy(share = share)))
            }
        }
        disconnect()
    }
}
