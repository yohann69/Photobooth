package com.photobooth.camera

import java.io.File

/** Hidden temp sibling that keeps the target's extension; scoped storage rejects unknown extensions like ".part". */
internal fun partialFileFor(target: File): File =
    File(target.absoluteFile.parentFile, ".part_${target.name}")
