package com.cybercat.simpleftp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var pathRepository: PathRepository
    private val serverStatus = MutableStateFlow(FtpServerStatus())
    private var ftpServer: MinimalFtpServer? = null
    private var currentRelativePath: String = DEFAULT_RELATIVE_PATH
    private var hasFileAccessState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pathRepository = PathRepository(this)
        hasFileAccessState.value = hasFileAccess()

        lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    hasFileAccessState.value = hasFileAccess()
                }
            }
        )

        startFtpServer()
        setContent {
            AppContent(
                pathRepository = pathRepository,
                serverStatusFlow = serverStatus,
                hasFileAccess = hasFileAccessState.value,
                storageRoot = storageRoot(),
                onGrantFileAccess = ::requestFileAccess,
                onExit = {
                    ftpServer?.stop()
                    finishAndRemoveTask()
                }
            )
        }
    }

    override fun onDestroy() {
        ftpServer?.stop()
        super.onDestroy()
    }

    private fun startFtpServer() {
        lifecycleScope.launch {
            pathRepository.relativePath.collect { path ->
                currentRelativePath = path.cleanRelativePath()
            }
        }
        ftpServer = MinimalFtpServer(
            port = 2121,
            rootProvider = {
                File(storageRoot(), currentRelativePath).also { it.mkdirs() }
            },
            onStatus = { serverStatus.value = it }
        ).also { it.start() }
    }

    private fun storageRoot(): File = Environment.getExternalStorageDirectory()

    private fun hasFileAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageUri = Uri.parse("package:$packageName")
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
            runCatching { startActivity(intent) }
                .onFailure {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                100
            )
        }
    }
}

private enum class Screen {
    Main,
    Path
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppContent(
    pathRepository: PathRepository,
    serverStatusFlow: MutableStateFlow<FtpServerStatus>,
    hasFileAccess: Boolean,
    storageRoot: File,
    onGrantFileAccess: () -> Unit,
    onExit: () -> Unit
) {
    val relativePath by pathRepository.relativePath.collectAsState(
        initial = DEFAULT_RELATIVE_PATH
    )
    val serverStatus by serverStatusFlow.collectAsState()
    var screen by remember { mutableStateOf(Screen.Main) }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        EInkTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                when (screen) {
                    Screen.Main -> MainScreen(
                        status = serverStatus,
                        relativePath = relativePath,
                        hasFileAccess = hasFileAccess,
                        onGrantFileAccess = onGrantFileAccess,
                        onPath = { screen = Screen.Path },
                        onExit = onExit
                    )

                    Screen.Path -> PathScreen(
                        storageRoot = storageRoot,
                        relativePath = relativePath,
                        hasFileAccess = hasFileAccess,
                        onGrantFileAccess = onGrantFileAccess,
                        onBack = { screen = Screen.Main },
                        onPathSelected = { path ->
                            scope.launch {
                                pathRepository.setRelativePath(path)
                                screen = Screen.Main
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    status: FtpServerStatus,
    relativePath: String,
    hasFileAccess: Boolean,
    onGrantFileAccess: () -> Unit,
    onPath: () -> Unit,
    onExit: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        val qrSize = minOf(maxWidth, maxHeight) * 0.73f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.main_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            val url = status.url
            if (url != null) {
                val bitmap = remember(url) { QrCodeGenerator.create(url) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(qrSize)
                        .border(2.dp, Color.Black),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.None
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = url,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.ftp_login_hint),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = status.error?.let { stringResource(R.string.server_error, it) }
                        ?: stringResource(R.string.server_stopped),
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "${stringResource(R.string.storage_root)}: /$relativePath",
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            if (!hasFileAccess) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.file_access_missing),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = stringResource(R.string.grant_file_access),
                    onClick = onGrantFileAccess
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryButton(text = stringResource(R.string.path), onClick = onPath)
                PrimaryButton(text = stringResource(R.string.exit), onClick = onExit)
            }
        }
    }
}

@Composable
private fun PathScreen(
    storageRoot: File,
    relativePath: String,
    hasFileAccess: Boolean,
    onGrantFileAccess: () -> Unit,
    onBack: () -> Unit,
    onPathSelected: (String) -> Unit
) {
    var currentDirectory by remember(relativePath) {
        mutableStateOf(
            File(storageRoot, relativePath.cleanRelativePath()).safeInside(storageRoot)
                ?: storageRoot
        )
    }
    val directories = remember(currentDirectory, hasFileAccess) {
        currentDirectory.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.choose_folder),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "/${currentDirectory.relativePathFrom(storageRoot)}",
            fontSize = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!hasFileAccess) {
            Text(
                text = stringResource(R.string.file_access_missing),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            PrimaryButton(
                text = stringResource(R.string.grant_file_access),
                onClick = onGrantFileAccess
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(text = stringResource(R.string.back), onClick = onBack)
            PrimaryButton(
                text = stringResource(R.string.use_this_folder),
                onClick = { onPathSelected(currentDirectory.relativePathFrom(storageRoot)) }
            )
        }
        val parent = currentDirectory.parentFile?.safeInside(storageRoot)
        if (parent != null &&
            currentDirectory.canonicalPathSafe() != storageRoot.canonicalPathSafe()
        ) {
            FolderRow(
                name = ".. ${stringResource(R.string.parent_folder)}",
                onClick = { currentDirectory = parent }
            )
        }
        if (directories.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_folder),
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 18.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(directories, key = { it.path }) { directory ->
                    FolderRow(
                        name = directory.name,
                        onClick = { currentDirectory = directory }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black
        )
    ) {
        Text(
            text = name,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            fontSize = 20.sp,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Black,
            contentColor = Color.White
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EInkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color.Black,
            onPrimary = Color.White,
            primaryContainer = Color.Black,
            onPrimaryContainer = Color.White,
            inversePrimary = Color.White,
            secondary = Color.Black,
            onSecondary = Color.White,
            secondaryContainer = Color.White,
            onSecondaryContainer = Color.Black,
            tertiary = Color.Black,
            onTertiary = Color.White,
            tertiaryContainer = Color.White,
            onTertiaryContainer = Color.Black,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color.White,
            onSurfaceVariant = Color.Black,
            surfaceTint = Color.Black,
            inverseSurface = Color.Black,
            inverseOnSurface = Color.White,
            error = Color.Black,
            onError = Color.White,
            errorContainer = Color.White,
            onErrorContainer = Color.Black,
            outline = Color.Black,
            outlineVariant = Color.Black,
            scrim = Color.Black
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp),
            extraLarge = RoundedCornerShape(0.dp)
        ),
        content = content
    )
}

private fun File.safeInside(root: File): File? = runCatching {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = canonicalFile
    val rootPath = canonicalRoot.path
    val filePath = canonicalFile.path
    if (filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")) {
        canonicalFile
    } else {
        null
    }
}.getOrNull()

private fun File.relativePathFrom(root: File): String = runCatching {
    canonicalFile.relativeTo(root.canonicalFile).path
        .replace(File.separatorChar, '/')
        .cleanRelativePath()
}.getOrDefault(DEFAULT_RELATIVE_PATH)

private fun File.canonicalPathSafe(): String = runCatching { canonicalPath }.getOrDefault(path)
