package com.github.kr328.clash

/** Inputs must come from the verified APK and the installed app, never the download filename. */
internal object UpdateApkPolicy {
    fun accepts(
        installedPackage: String, installedVersion: Long, installedSigners: Set<String>,
        candidatePackage: String, candidateVersion: Long, candidateSigners: Set<String>,
        rotationAncestorsWithInstalledData: Set<String> = emptySet(),
    ): Boolean {
        if (candidatePackage != installedPackage || candidateVersion <= installedVersion) return false
        if (installedSigners.isEmpty() || candidateSigners.isEmpty()) return false
        if (installedSigners == candidateSigners) return true
        // Rotation is single-signer only. A verified proof-of-rotation must grant installed-data
        // capability to the CURRENT installed signer. Merely sharing a historical key is insufficient.
        return installedSigners.size == 1 && candidateSigners.size == 1 &&
            rotationAncestorsWithInstalledData.containsAll(installedSigners)
    }
}
