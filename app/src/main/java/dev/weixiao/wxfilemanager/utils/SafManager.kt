package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import dev.weixiao.wxfilemanager.model.FileModel
import java.io.File

object SafManager {

    private const val PREFS_NAME = "saf_prefs"
    private const val KEY_DATA_URI = "uri_android_data"
    private const val KEY_OBB_URI = "uri_android_obb"

    fun isRestrictedPath(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val root = Environment.getExternalStorageDirectory().absolutePath
        return path == "$root/Android/data" || path.startsWith("$root/Android/data/") ||
               path == "$root/Android/obb" || path.startsWith("$root/Android/obb/")
    }

    fun needsPermission(context: Context, path: String): Boolean {
        if (!isRestrictedPath(path)) return false
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        val uriString = if (isData) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DATA_URI, null)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_OBB_URI, null)
        }

        if (uriString == null) return true

        val uri = Uri.parse(uriString)
        return !hasUriPermission(context, uri)
    }

    private fun hasUriPermission(context: Context, uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any { 
            it.uri == uri && it.isReadPermission
        }
    }

    fun getActionOpenDocumentTreeIntent(path: String): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        
        // Android 11+ direct path shortcut
        // For Xiaomi/HyperOS and Android 14+, the restriction is even tighter.
        // Using the "primary:Android/data" document URI directly sometimes works better
        val targetPath = if (isData) "Android%2Fdata" else "Android%2Fobb"
        
        // Android 14+ / Xiaomi HyperOS often block the "Android/data" directory directly.
        // We try to point to the directory but with a slightly different URI structure.
        val uri = if (Build.VERSION.SDK_INT >= 34) {
            // Some newer devices respond better to the direct document URI as the initial URI
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$targetPath")
        } else {
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$targetPath")
        }
        
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Essential for Android 13/14+ to show the "Use this folder" button in some cases
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            // Some devices need this to bypass the restriction screen
            putExtra("android.provider.extra.SHOW_ADVANCED", true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION or 
                     Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                     Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun saveUriPermission(context: Context, path: String, uri: Uri) {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        val key = if (isData) KEY_DATA_URI else KEY_OBB_URI
        
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, uri.toString())
            .apply()
    }

    fun listFiles(context: Context, path: String): List<FileModel> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        val uriString = if (isData) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DATA_URI, null)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_OBB_URI, null)
        } ?: return emptyList()

        val rootUri = Uri.parse(uriString)
        val documentFile = getTargetDocumentFile(context, rootUri, path) ?: return emptyList()

        return documentFile.listFiles().map { doc ->
            val isDirectory = doc.isDirectory
            val extension = doc.name?.substringAfterLast('.', "")?.lowercase() ?: ""
            val mimeType = if (isDirectory) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            
            FileModel(
                name = doc.name ?: "",
                path = if (path.endsWith("/")) "$path${doc.name}" else "$path/${doc.name}",
                isDirectory = isDirectory,
                size = if (isDirectory) 0 else doc.length(),
                lastModified = doc.lastModified(),
                mimeType = mimeType,
                isSmb = false
            )
        }
    }

    private fun getTargetDocumentFile(context: Context, rootUri: Uri, path: String): DocumentFile? {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        val baseRestrictedPath = if (isData) "$root/Android/data" else "$root/Android/obb"
        
        var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        
        if (path == baseRestrictedPath) return currentDoc
        
        val relativePath = path.substring(baseRestrictedPath.length).trimStart('/')
        if (relativePath.isEmpty()) return currentDoc
        
        val segments = relativePath.split('/')
        for (segment in segments) {
            if (segment.isEmpty()) continue
            currentDoc = currentDoc.findFile(segment) ?: return null
        }
        
        return currentDoc
    }

    fun searchRecursive(context: Context, path: String, query: String): List<FileModel> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        val uriString = if (isData) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DATA_URI, null)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_OBB_URI, null)
        } ?: return emptyList()

        val rootUri = Uri.parse(uriString)
        val startDoc = getTargetDocumentFile(context, rootUri, path) ?: return emptyList()
        
        val results = mutableListOf<FileModel>()
        
        fun doSearch(currentDoc: DocumentFile, currentPath: String) {
            for (doc in currentDoc.listFiles()) {
                val docName = doc.name ?: continue
                val isDirectory = doc.isDirectory
                val fullPath = if (currentPath.endsWith("/")) "$currentPath$docName" else "$currentPath/$docName"
                
                if (docName.contains(query, ignoreCase = true)) {
                    val extension = docName.substringAfterLast('.', "").lowercase()
                    val mimeType = if (isDirectory) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    
                    results.add(FileModel(
                        name = docName,
                        path = fullPath,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0 else doc.length(),
                        lastModified = doc.lastModified(),
                        mimeType = mimeType,
                        isSmb = false
                    ))
                }
                
                if (isDirectory) {
                    doSearch(doc, fullPath)
                }
            }
        }
        
        doSearch(startDoc, path)
        return results
    }

    fun getFileUri(context: Context, path: String): Uri? {
        if (!isRestrictedPath(path)) return Uri.fromFile(File(path))
        
        val root = Environment.getExternalStorageDirectory().absolutePath
        val isData = path.contains("$root/Android/data")
        val uriString = if (isData) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DATA_URI, null)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_OBB_URI, null)
        } ?: return null

        val rootUri = Uri.parse(uriString)
        val doc = getTargetDocumentFile(context, rootUri, path)
        return doc?.uri
    }
}
