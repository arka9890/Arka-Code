DisposableEffect(context) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

        val item = clip.getItemAt(0)

        // Handle image data directly from clipboard (image mime type)
        if (item.mimeTypes?.contains(ClipData.MIME_TYPE_IMAGE) == true) {
            val imageUri = item.getItemData(ClipData.MIME_TYPE_IMAGE)?.uri
            if (imageUri != null) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        Attachments.import(context, sessionId, imageUri)
                    }
                    when (result) {
                        is ImportResult.Success -> {
                            attachments = attachments + result.attachment
                        }
                        is ImportResult.Failure -> {
                            // Tampilkan error singkat (bisa ditambahkan snackbar nanti)
                        }
                    }
                }
            }
        }

        // Jika ada URI (gambar dari gallery atau file), langsung import
        item.uri?.let { uri ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    Attachments.import(context, sessionId, uri)
                }
                when (result) {
                    is ImportResult.Success -> {
                        attachments = attachments + result.attachment
                    }
                    is ImportResult.Failure -> {
                        // Tampilkan error singkat (bisa ditambahkan snackbar nanti)
                    }
                }
            }
        }

        // Jika ada base64 image (data URI) dari clipboard
        item.text?.let { text ->
            val textStr = text.toString().trim()
            if (textStr.startsWith("data:image/")) {
                scope.launch {
                    val base64Data = textStr.substringAfter("base64,")
                    val bytes = runCatching { Base64.decode(base64Data, Base64.DEFAULT) }.getOrNull()
                    val result = if (bytes == null) {
                        ImportResult.Failure(ImportResult.Reason.UNREADABLE)
                    } else if (bytes.size > Attachments.MAX_BYTES) {
                        ImportResult.Failure(ImportResult.Reason.TOO_LARGE)
                    } else {
                        val mime = textStr.substringAfter("data:").substringBefore(";")
                        val name = "clipboard-image.${mime.substringAfterLast('/', '')}"
                        Attachments.importFromBytes(context, sessionId, name, bytes, mime)
                    }
                    if (result is ImportResult.Success) {
                        attachments = attachments + result.attachment
                    }
                }
            }
        }
    }

    cm.addPrimaryClipChangedListener(listener)

    onDispose {
        cm.removePrimaryClipChangedListener(listener)
    }
}