/*
 * Copyright 2020 Hunter J Drum
 */
package theredspy15.ltecleanerfoss.controllers

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import dev.shreyaspatil.MaterialDialog.MaterialDialog
import dev.shreyaspatil.MaterialDialog.interfaces.DialogInterface
import theredspy15.ltecleanerfoss.FileScanner
import theredspy15.ltecleanerfoss.R
import theredspy15.ltecleanerfoss.databinding.ActivityMainBinding
import java.io.File
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    var binding: ActivityMainBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs == null) updateTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding!!.root)
        binding!!.cleanBtn.setOnClickListener { clean() }
        binding!!.settingsBtn.setOnClickListener { settings() }
        binding!!.whitelistBtn.setOnClickListener { whitelist() }
        binding!!.analyzeBtn.setOnClickListener { analyze() }
        WhitelistActivity.getWhiteList(prefs)
    }

    /**
     * Starts the settings activity
     */
    fun settings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    fun whitelist() {
        val intent = Intent(this, WhitelistActivity::class.java)
        startActivity(intent)
    }

    fun analyze() {
        requestWriteExternalPermission()
        if (!FileScanner.isRunning) {
            Thread { scan(false) }.start()
        }
    }

    private fun arrangeViews(isDelete: Boolean) {
        if (isDelete) arrangeForClean() else arrangeForAnalyze()
    }

    private fun arrangeForClean() {
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding!!.frameLayout.visibility = View.VISIBLE
            binding!!.fileScrollView.visibility = View.GONE
        }
    }

    private fun arrangeForAnalyze() {
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding!!.frameLayout.visibility = View.GONE
            binding!!.fileScrollView.visibility = View.VISIBLE
        }
    }

    /**
     * Runs search and delete on background thread
     */
    fun clean() {
        requestWriteExternalPermission()
        if (!FileScanner.isRunning) {
            if (prefs == null) println("presssss is null")
            if (prefs!!.getBoolean("one_click", false)) // one-click disabled
            {
                Thread { scan(true) }.start() // one-click enabled
            } else {
                val mDialog = MaterialDialog.Builder(this)
                    .setTitle(getString(R.string.are_you_sure_deletion_title))
                    .setAnimation("5453-shred-paper.json")
                    .setMessage(getString(R.string.are_you_sure_deletion))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.clean)) { dialogInterface: DialogInterface, _: Int ->
                        dialogInterface.dismiss()
                        Thread { scan(true) }.start()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.dismiss() }
                    .build()
                mDialog.animationView.scaleType = ImageView.ScaleType.FIT_CENTER
                mDialog.show()
            }
        }
    }

    private fun clearClipboard() {
        try {
            val mCbm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mCbm.clearPrimaryClip()
            } else {
                val clipService = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("", "")
                clipService.setPrimaryClip(clipData)
            }
        } catch (e: NullPointerException) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    R.string.clipboard_clear_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Searches entire device, adds all files to a list, then a for each loop filters
     * out files for deletion. Repeats the process as long as it keeps finding files to clean,
     * unless nothing is found to begin with
     */
    @SuppressLint("SetTextI18n")
    private fun scan(delete: Boolean) {
        Looper.prepare()
        runOnUiThread {
            findViewById<View>(R.id.cleanBtn).isEnabled = !FileScanner.isRunning
            findViewById<View>(R.id.analyzeBtn).isEnabled = !FileScanner.isRunning
        }
        reset()
        if (prefs!!.getBoolean("clipboard", false)) clearClipboard()
        runOnUiThread {
            arrangeViews(delete)
            binding!!.statusTextView.text = getString(R.string.status_running)
        }
        val path = Environment.getExternalStorageDirectory()

        // scanner setup
        val fs = FileScanner(path, this)
            .setEmptyDir(prefs!!.getBoolean("empty", false))
            .setAutoWhite(prefs!!.getBoolean("auto_white", true))
            .setDelete(delete)
            .setCorpse(prefs!!.getBoolean("corpse", false))
            .setGUI(binding)
            .setContext(this)
            .setUpFilters(
                prefs!!.getBoolean("generic", true),
                prefs!!.getBoolean("aggressive", false),
                prefs!!.getBoolean("apk", false)
            )

        // failed scan
        if (path.listFiles() == null) { // is this needed? yes.
            val textView = printTextView(getString(R.string.failed_scan), Color.RED)
            runOnUiThread { binding!!.fileListView.addView(textView) }
        }

        // kilobytes found/freed text
        val kilobytesTotal = fs.startScan()
        runOnUiThread {
            if (delete) binding!!.statusTextView.text =
                getString(R.string.freed) + " " + convertSize(kilobytesTotal) else binding!!.statusTextView.text =
                getString(R.string.found) + " " + convertSize(kilobytesTotal)

            // crappy but working fix for percentage never reaching 100 exactly
            binding!!.scanProgress.progress = binding!!.scanProgress.max
            binding!!.scanTextView.text = "100%"
        }
        binding!!.fileScrollView.post { binding!!.fileScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        runOnUiThread {
            findViewById<View>(R.id.cleanBtn).isEnabled = !FileScanner.isRunning
            findViewById<View>(R.id.analyzeBtn).isEnabled = !FileScanner.isRunning
        }
        Looper.loop()
    }

    /**
     * Convenience method to quickly create a textview
     * @param text - text of textview
     * @return - created textview
     */
    private fun printTextView(text: String, color: Int): TextView {
        val textView = TextView(this@MainActivity)
        textView.setTextColor(color)
        textView.text = text
        textView.setPadding(3, 3, 3, 3)
        return textView
    }

    /**
     * Displaying text for files that have been removed
     */
    fun displayDeletion(file: File): TextView {
        // creating and adding a text view to the scroll view with path to file
        val textView = printTextView(file.absolutePath, resources.getColor(R.color.colorAccent,resources.newTheme()))

        // adding to scroll view
        runOnUiThread { binding!!.fileListView.addView(textView) }

        // scroll to bottom
        binding!!.fileScrollView.post { binding!!.fileScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        return textView
    }

    /**
     * Displays generic text
     */
    fun displayText(text: String) {
        // creating and adding a text view to the scroll view with path to file
        val textView = printTextView(text, Color.YELLOW)

        // adding to scroll view
        runOnUiThread { binding!!.fileListView.addView(textView) }

        // scroll to bottom
        binding!!.fileScrollView.post { binding!!.fileScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * Removes all views present in fileListView (linear view), and sets found and removed
     * files to 0
     */
    private fun reset() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        runOnUiThread {
            binding!!.fileListView.removeAllViews()
            binding!!.scanProgress.progress = 0
            binding!!.scanProgress.max = 1
        }
    }

    /**
     * Request write permission
     */
    private fun requestWriteExternalPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // android 11 and up
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.MANAGE_EXTERNAL_STORAGE
                ),
                1
            )
            if (!Environment.isExternalStorageManager()) { // all files
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                1
            )
        }
    }

    /**
     * Handles the whether the user grants permission. Launches new fragment asking the user to give file permission.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) prompt()
    }

    /**
     * Launches the prompt activity
     */
    private fun prompt() {
        val intent = Intent(this, PromptActivity::class.java)
        startActivity(intent)
    }

    private fun updateTheme() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val dark = resources.getStringArray(R.array.themes)[2]
        val light = resources.getStringArray(R.array.themes)[1]
        val selectedTheme = prefs!!.getString("theme", dark)
        if (selectedTheme == dark) { // dark
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else if (selectedTheme == light) { // light
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } // auto
    }

    companion object {
        @JvmField
        var prefs: SharedPreferences? = null
        @JvmStatic
        fun convertSize(length: Long): String {
            val format = DecimalFormat("#.##")
            val mib = (1024 * 1024).toLong()
            val kib: Long = 1024
            if (length > mib) {
                return format.format(length / mib) + " MB"
            }
            return if (length > kib) {
                format.format(length / kib) + " KB"
            } else format.format(length) + " B"
        }
    }
}