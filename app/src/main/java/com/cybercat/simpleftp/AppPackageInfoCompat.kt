package com.cybercat.simpleftp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

internal fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }

internal fun PackageManager.getPackageArchiveInfoCompat(path: String): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
    }

internal fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

internal fun PackageInfo.signingCertificateBytes(): Set<List<Byte>> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            signature.toByteArray().asList()
        }.toSet()
    } else {
        @Suppress("DEPRECATION")
        signatures.orEmpty().map { signature ->
            signature.toByteArray().asList()
        }.toSet()
    }
