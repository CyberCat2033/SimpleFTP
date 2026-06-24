package com.cybercat.simpleftp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

data class FtpServerStatus(
    val running: Boolean = false,
    val url: String? = null,
    val error: String? = null
)

class MinimalFtpServer(
    private val port: Int,
    private val rootProvider: () -> File,
    private val addressProvider: () -> String?,
    private val onStatus: (FtpServerStatus) -> Unit
) {
    private val clients = CopyOnWriteArraySet<Socket>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "simple-ftp-accept", isDaemon = true) {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    val url = addressProvider()?.let {
                        "ftp://anonymous@$it:$port/"
                    }
                    onStatus(FtpServerStatus(running = true, url = url))
                    while (running) {
                        val client = socket.accept()
                        if (clients.size >= MAX_CLIENTS) {
                            thread(name = "simple-ftp-reject", isDaemon = true) {
                                runCatching {
                                    client.use {
                                        val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
                                        writer.write("421 Too many connections.\r\n")
                                        writer.flush()
                                    }
                                }
                            }
                            continue
                        }
                        clients += client
                        thread(name = "simple-ftp-client", isDaemon = true) {
                            try {
                                client.use {
                                    handleClient(it)
                                }
                            } catch (e: Exception) {
                                // Ignore client connection errors safely
                            } finally {
                                clients -= client
                            }
                        }
                    }
                }
            } catch (exception: SocketException) {
                if (running) {
                    onStatus(FtpServerStatus(error = exception.message))
                }
            } catch (exception: Exception) {
                onStatus(
                    FtpServerStatus(error = exception.message ?: exception.javaClass.simpleName)
                )
            } finally {
                running = false
                serverSocket = null
                clients.forEach { runCatching { it.close() } }
                onStatus(FtpServerStatus())
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        clients.forEach { runCatching { it.close() } }
        onStatus(FtpServerStatus())
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 5 * 60 * 1000 // 5 minutes read timeout
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val session = ClientSession(socket, writer)
        session.reply(220, "Simple FTP ready")
        while (running && !socket.isClosed) {
            val line = reader.readLine() ?: break
            val command = line.substringBefore(' ').uppercase(Locale.US)
            val argument = line.substringAfter(' ', "").trim()
            if (session.pendingRenameFrom != null && command !in RenameCommands) {
                session.pendingRenameFrom = null
            }
            when (command) {
                "USER" -> session.reply(331, "Anonymous login ok")

                "PASS" -> session.reply(230, "Logged in")

                "SYST" -> session.reply(215, "UNIX Type: L8")

                "FEAT" -> session.multiline(211, listOf("UTF8", "PASV", "EPSV", "SIZE", "MDTM"))

                "OPTS" -> session.reply(200, "OK")

                "PWD", "XPWD" -> session.reply(257, "\"${session.cwd}\"")

                "TYPE" -> session.reply(200, "Type set")

                "NOOP" -> session.reply(200, "OK")

                "CWD" -> changeDirectory(session, argument)

                "CDUP" -> changeDirectory(session, "..")

                "PASV" -> session.enterPassiveMode()

                "EPSV" -> session.enterExtendedPassiveMode()

                "LIST" -> listFiles(session, argument, namesOnly = false)

                "NLST" -> listFiles(session, argument, namesOnly = true)

                "RETR" -> retrieveFile(session, argument)

                "STOR" -> storeFile(session, argument)

                "RNFR" -> prepareRename(session, argument)

                "RNTO" -> renamePrepared(session, argument)

                "DELE" -> deleteFile(session, argument)

                "MKD", "XMKD" -> makeDirectory(session, argument)

                "RMD", "XRMD" -> removeDirectory(session, argument)

                "SIZE" -> fileSize(session, argument)

                "MDTM" -> modifiedTime(session, argument)

                "QUIT" -> {
                    session.reply(221, "Bye")
                    break
                }

                else -> session.reply(502, "Command not implemented")
            }
        }
        session.closePassiveSocket()
    }

    private fun changeDirectory(session: ClientSession, path: String) {
        val directory = resolve(session.cwd, path)
        if (directory?.isDirectory == true) {
            session.cwd = relativePath(directory)
            session.reply(250, "Directory changed")
        } else {
            session.reply(550, "Directory unavailable")
        }
    }

    private fun listFiles(session: ClientSession, path: String, namesOnly: Boolean) {
        val target = resolve(session.cwd, path.ifBlank { "." })
        if (target?.exists() != true) {
            session.reply(550, "Path unavailable")
            return
        }
        session.withDataSocket("Opening data connection") { dataSocket ->
            val dataWriter =
                BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
            val files = if (target.isDirectory) {
                target.listFiles()?.sortedWith(
                    compareBy<File> {
                        !it.isDirectory
                    }.thenBy { it.name.lowercase() }
                ).orEmpty()
            } else {
                listOf(target)
            }
            files.forEach { file ->
                val line = if (namesOnly) file.name else file.listLine()
                dataWriter.write(line)
                dataWriter.write("\r\n")
            }
            dataWriter.flush()
        }
    }

    private fun retrieveFile(session: ClientSession, path: String) {
        val file = resolve(session.cwd, path)
        if (file?.isFile != true) {
            session.reply(550, "File unavailable")
            return
        }
        session.withDataSocket("Opening data connection") { dataSocket ->
            FileInputStream(file).use { input ->
                input.copyTo(dataSocket.getOutputStream())
            }
        }
    }

    private fun storeFile(session: ClientSession, path: String) {
        val file = resolve(session.cwd, path)
        if (file == null) {
            session.reply(550, "Invalid path")
            return
        }
        file.parentFile?.mkdirs()
        session.withDataSocket("Opening data connection") { dataSocket ->
            FileOutputStream(file).use { output ->
                dataSocket.getInputStream().copyTo(output)
            }
        }
    }

    private fun prepareRename(session: ClientSession, path: String) {
        val file = resolve(session.cwd, path)
        if (file?.exists() == true) {
            session.pendingRenameFrom = file
            session.reply(350, "Ready for RNTO")
        } else {
            session.pendingRenameFrom = null
            session.reply(550, "Path unavailable")
        }
    }

    private fun renamePrepared(session: ClientSession, path: String) {
        val source = session.pendingRenameFrom
        session.pendingRenameFrom = null
        if (source == null) {
            session.reply(503, "RNFR required first")
            return
        }

        val destination = resolve(session.cwd, path)
        if (destination == null) {
            session.reply(550, "Invalid path")
            return
        }
        if (!source.exists()) {
            session.reply(550, "Source unavailable")
            return
        }
        if (destination.exists()) {
            session.reply(550, "Destination already exists")
            return
        }
        val parent = destination.parentFile
        if (parent?.isDirectory != true) {
            session.reply(550, "Destination directory unavailable")
            return
        }

        if (source.renameTo(destination)) {
            session.reply(250, "Renamed")
        } else {
            session.reply(550, "Rename failed")
        }
    }

    private fun deleteFile(session: ClientSession, path: String) {
        val file = resolve(session.cwd, path)
        if (file?.isFile == true && file.delete()) {
            session.reply(250, "Deleted")
        } else {
            session.reply(550, "Delete failed")
        }
    }

    private fun makeDirectory(session: ClientSession, path: String) {
        val directory = resolve(session.cwd, path)
        if (directory != null && (directory.isDirectory || directory.mkdirs())) {
            session.reply(257, "\"${relativePath(directory)}\"")
        } else {
            session.reply(550, "Create directory failed")
        }
    }

    private fun removeDirectory(session: ClientSession, path: String) {
        val root = rootProvider().canonicalFile
        val directory = resolve(session.cwd, path)
        if (directory != null && directory.canonicalPath == root.canonicalPath) {
            session.reply(550, "Cannot remove root directory")
            return
        }
        if (directory?.isDirectory == true && directory.delete()) {
            session.reply(250, "Removed")
        } else {
            session.reply(550, "Remove directory failed")
        }
    }

    private fun fileSize(session: ClientSession, path: String) {
        val file = resolve(session.cwd, path)
        if (file?.isFile == true) {
            session.reply(213, file.length().toString())
        } else {
            session.reply(550, "File unavailable")
        }
    }

    private fun modifiedTime(session: ClientSession, path: String) {
        val file = resolve(session.cwd, path)
        if (file?.exists() == true) {
            session.reply(213, FtpDateFormat.format(Date(file.lastModified())))
        } else {
            session.reply(550, "Path unavailable")
        }
    }

    private fun resolve(cwd: String, input: String): File? {
        val root = rootProvider().also { it.mkdirs() }.canonicalFile
        val combined = when {
            input.isBlank() || input == "." -> cwd
            input.startsWith("/") -> input
            cwd == "/" -> "/$input"
            else -> "$cwd/$input"
        }
        val clean = combined.split('/')
            .fold(emptyList<String>()) { parts, part ->
                when (part) {
                    "", "." -> parts
                    ".." -> parts.dropLast(1)
                    else -> parts + part
                }
            }
            .joinToString(File.separator)
        val file = if (clean.isEmpty()) root else File(root, clean)
        val canonical = file.canonicalFile
        val rootPath = root.path
        val path = canonical.path
        return if (path == rootPath ||
            path.startsWith("$rootPath${File.separator}")
        ) {
            canonical
        } else {
            null
        }
    }

    private fun relativePath(file: File): String {
        val root = rootProvider().canonicalFile
        val relative = file.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    private inner class ClientSession(
        private val socket: Socket,
        private val writer: BufferedWriter
    ) {
        var cwd: String = "/"
        var pendingRenameFrom: File? = null
        private var passiveSocket: ServerSocket? = null

        fun reply(code: Int, message: String) {
            writer.write("$code $message\r\n")
            writer.flush()
        }

        fun multiline(code: Int, lines: List<String>) {
            writer.write("$code-Features\r\n")
            lines.forEach {
                writer.write(" $it\r\n")
            }
            writer.write("$code End\r\n")
            writer.flush()
        }

        fun enterPassiveMode() {
            val passive = openPassiveSocket()
            val host = addressProvider()
                ?: socket.localAddress.hostAddress
                ?: "127.0.0.1"
            val parts = host.split('.')
            val p1 = passive.localPort / 256
            val p2 = passive.localPort % 256
            reply(227, "Entering Passive Mode (${parts.joinToString(",")},$p1,$p2)")
        }

        fun enterExtendedPassiveMode() {
            val passive = openPassiveSocket()
            reply(229, "Entering Extended Passive Mode (|||${passive.localPort}|)")
        }

        fun withDataSocket(openMessage: String, block: (Socket) -> Unit) {
            val passive = passiveSocket
            if (passive == null) {
                reply(425, "Use PASV first")
                return
            }
            try {
                reply(150, openMessage)
                passive.accept().use(block)
                reply(226, "Transfer complete")
            } catch (exception: Exception) {
                reply(451, exception.message ?: "Transfer failed")
            } finally {
                closePassiveSocket()
            }
        }

        fun closePassiveSocket() {
            runCatching { passiveSocket?.close() }
            passiveSocket = null
        }

        private fun openPassiveSocket(): ServerSocket {
            closePassiveSocket()
            return ServerSocket(0).also {
                it.soTimeout = 30_000
                passiveSocket = it
            }
        }
    }

    private companion object {
        const val MAX_CLIENTS = 5
        val FtpDateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val RenameCommands = setOf("RNFR", "RNTO")
    }
}

private fun File.listLine(): String {
    val permissions = if (isDirectory) "drwxr-xr-x" else "-rw-r--r--"
    val date = ListDateFormat.format(Date(lastModified()))
    val safeName = name.replace('\r', '_').replace('\n', '_')
    return "$permissions 1 owner group ${length()} $date $safeName"
}

private val ListDateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)
