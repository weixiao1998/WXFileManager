package dev.weixiao.wxfilemanager.ui.viewer

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.weixiao.wxfilemanager.R
import dev.weixiao.wxfilemanager.databinding.ActivityTextViewerBinding
import dev.weixiao.wxfilemanager.model.FileModel
import dev.weixiao.wxfilemanager.utils.SyntaxHighlighter
import dev.weixiao.wxfilemanager.utils.TextFileLoader
import kotlinx.coroutines.launch
import java.nio.charset.Charset

class TextViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextViewerBinding

    private var fileName: String = ""
    private var filePath: String = ""
    private var isSmb: Boolean = false
    private var fileSize: Long = 0L

    private var currentCharsetOverride: Charset? = null
    private var rawContent: String = ""
    private var highlighted: CharSequence = ""

    private val matchSpans = mutableListOf<BackgroundColorSpan>()
    private val matchRanges = mutableListOf<IntRange>()
    private var currentMatchIndex = -1
    private var currentKeyword: String = ""

    private companion object {
        const val HIGHLIGHT_NORMAL = 0x80FFEB3B.toInt()
        const val HIGHLIGHT_CURRENT = 0xFFFF9800.toInt()
        const val FONT_MIN = 9f
        const val FONT_MAX = 28f
        const val FONT_STEP = 1f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fileName = intent.getStringExtra("name") ?: ""
        filePath = intent.getStringExtra("path") ?: ""
        isSmb = intent.getBooleanExtra("isSmb", false)
        fileSize = intent.getLongExtra("size", 0L)

        supportActionBar?.title = fileName

        setupSearchBar()
        TextFileLoader.cleanupCache(this)
        loadFile()
    }

    private fun setupSearchBar() {
        binding.btnPrev.setOnClickListener { jumpToMatch(currentMatchIndex - 1) }
        binding.btnNext.setOnClickListener { jumpToMatch(currentMatchIndex + 1) }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }
        binding.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.etKeyword.text.toString())
                true
            } else false
        }
    }

    private fun loadFile() {
        binding.progress.visibility = View.VISIBLE
        binding.tvContent.text = ""
        val fileModel = FileModel(
            name = fileName,
            path = filePath,
            isDirectory = false,
            size = fileSize,
            lastModified = 0L,
            isSmb = isSmb
        )
        lifecycleScope.launch {
            val result = TextFileLoader.load(this@TextViewerActivity, fileModel, currentCharsetOverride)
            binding.progress.visibility = View.GONE
            when (result) {
                is TextFileLoader.LoadResult.Success -> {
                    rawContent = result.content
                    val lang = SyntaxHighlighter.languageOf(fileName)
                    highlighted = SyntaxHighlighter.highlight(rawContent, lang)
                    binding.tvContent.text = highlighted
                    val label = getString(R.string.text_viewer_charset_label, result.charset.name())
                    supportActionBar?.subtitle = label
                    if (currentKeyword.isNotEmpty()) performSearch(currentKeyword)
                }
                is TextFileLoader.LoadResult.TooLarge -> {
                    val sizeText = formatSize(result.size)
                    Toast.makeText(this@TextViewerActivity, getString(R.string.text_viewer_too_large, sizeText), Toast.LENGTH_LONG).show()
                    finish()
                }
                is TextFileLoader.LoadResult.Error -> {
                    val msg = result.throwable.localizedMessage ?: result.throwable.javaClass.simpleName
                    Toast.makeText(this@TextViewerActivity, getString(R.string.text_viewer_load_failed, msg), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_text_viewer, menu)
        menu.findItem(R.id.encoding_auto)?.isChecked = currentCharsetOverride == null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_search -> { openSearch(); true }
            R.id.action_font_inc -> { changeFontSize(FONT_STEP); true }
            R.id.action_font_dec -> { changeFontSize(-FONT_STEP); true }
            R.id.encoding_auto -> { switchEncoding(null, item); true }
            R.id.encoding_utf8 -> { switchEncoding(Charsets.UTF_8, item); true }
            R.id.encoding_gbk -> { switchEncoding(safeCharset("GBK"), item); true }
            R.id.encoding_gb18030 -> { switchEncoding(safeCharset("GB18030"), item); true }
            R.id.encoding_utf16 -> { switchEncoding(Charsets.UTF_16, item); true }
            R.id.encoding_latin1 -> { switchEncoding(Charsets.ISO_8859_1, item); true }
            R.id.encoding_big5 -> { switchEncoding(safeCharset("Big5"), item); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun safeCharset(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)

    private fun switchEncoding(charset: Charset?, item: MenuItem) {
        currentCharsetOverride = charset
        item.isChecked = true
        loadFile()
    }

    private fun changeFontSize(delta: Float) {
        val current = binding.tvContent.textSize / resources.displayMetrics.scaledDensity
        val next = (current + delta).coerceIn(FONT_MIN, FONT_MAX)
        binding.tvContent.textSize = next
    }

    private fun openSearch() {
        binding.searchBar.visibility = View.VISIBLE
        binding.etKeyword.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etKeyword, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        binding.searchBar.visibility = View.GONE
        clearMatchSpans()
        currentKeyword = ""
        binding.etKeyword.text?.clear()
        binding.tvContent.text = highlighted
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etKeyword.windowToken, 0)
    }

    private fun performSearch(keyword: String) {
        currentKeyword = keyword
        clearMatchSpans()
        if (keyword.isEmpty() || rawContent.isEmpty()) {
            binding.tvContent.text = highlighted
            binding.tvMatchInfo.text = ""
            return
        }
        val spannable = SpannableString(highlighted)
        var index = 0
        val lowerSrc = rawContent.lowercase()
        val lowerKey = keyword.lowercase()
        while (true) {
            val found = lowerSrc.indexOf(lowerKey, index)
            if (found < 0) break
            val end = found + keyword.length
            val span = BackgroundColorSpan(HIGHLIGHT_NORMAL)
            spannable.setSpan(span, found, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            matchSpans.add(span)
            matchRanges.add(found until end)
            index = end
        }
        binding.tvContent.text = spannable
        if (matchRanges.isEmpty()) {
            binding.tvMatchInfo.text = getString(R.string.text_viewer_no_match)
            currentMatchIndex = -1
        } else {
            jumpToMatch(0)
        }
    }

    private fun jumpToMatch(targetIndex: Int) {
        if (matchRanges.isEmpty()) return
        val size = matchRanges.size
        val normalized = ((targetIndex % size) + size) % size
        currentMatchIndex = normalized
        val tv = binding.tvContent
        val text = tv.text
        if (text !is SpannableString) return
        for ((i, span) in matchSpans.withIndex()) {
            text.removeSpan(span)
            val r = matchRanges[i]
            val color = if (i == normalized) HIGHLIGHT_CURRENT else HIGHLIGHT_NORMAL
            val newSpan = BackgroundColorSpan(color)
            text.setSpan(newSpan, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            matchSpans[i] = newSpan
        }
        binding.tvMatchInfo.text = getString(
            R.string.text_viewer_match_format, normalized + 1, size
        )
        scrollToOffset(matchRanges[normalized].first)
    }

    private fun scrollToOffset(charOffset: Int) {
        val tv = binding.tvContent
        val layout = tv.layout
        if (layout == null) {
            tv.post { scrollToOffset(charOffset) }
            return
        }
        val line = layout.getLineForOffset(charOffset)
        val y = layout.getLineTop(line)
        binding.verticalScroll.smoothScrollTo(0, (y - 60).coerceAtLeast(0))
    }

    private fun clearMatchSpans() {
        val tv = binding.tvContent
        val text = tv.text
        if (text is SpannableString) {
            for (span in matchSpans) text.removeSpan(span)
        }
        matchSpans.clear()
        matchRanges.clear()
        currentMatchIndex = -1
    }

    override fun onDestroy() {
        super.onDestroy()
        TextFileLoader.cleanupCache(this)
    }
}
