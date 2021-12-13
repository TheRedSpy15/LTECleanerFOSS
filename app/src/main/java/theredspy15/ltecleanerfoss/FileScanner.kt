/*
 * Copyright 2020 Hunter J Drum
 */
package theredspy15.ltecleanerfoss

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.widget.TextView
import androidx.preference.PreferenceManager
import theredspy15.ltecleanerfoss.controllers.MainActivity
import theredspy15.ltecleanerfoss.controllers.WhitelistActivity
import theredspy15.ltecleanerfoss.databinding.ActivityMainBinding
import java.io.File
import java.util.*

class FileScanner(private val path: File, context: Context?) {
    private var prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var context: Context? = null
    private var res: Resources? = null
    private var gui: ActivityMainBinding? = null
    private var filesRemoved = 0
    private var kilobytesTotal: Long = 0
    private var delete = false
    private var emptyDir = false
    private var autoWhite = true
    private var corpse = false
    private val listFiles: List<File>
        get() = getListFiles(path)

    /**
     * Used to generate a list of all files on device
     * @param parentDirectory where to start searching from
     * @return List of all files on device (besides whitelisted ones)
     */
    private fun getListFiles(parentDirectory: File): List<File> {
        val inFiles = ArrayList<File>()
        val files = parentDirectory.listFiles()
        if (files != null) {
            for (file in files) {
                if (file != null) { // hopefully to fix crashes on a very limited number of devices.
                    if (!isWhiteListed(file)) { // won't touch if whitelisted
                        if (file.isDirectory) { // folder
                            if (autoWhite) {
                                if (!autoWhiteList(file)) inFiles.add(file)
                            } else inFiles.add(file) // add folder itself
                            inFiles.addAll(getListFiles(file)) // add contents to returned list
                        } else inFiles.add(file) // add file
                    }
                }
            }
        }
        return inFiles
    }

    /**
     * Runs a for each loop through the white list, and compares the path of the file to each path in
     * the list
     * @param file file to check if in the whitelist
     * @return true if is the file is in the white list, false if not
     */
    private fun isWhiteListed(file: File): Boolean {
        for (path in WhitelistActivity.getWhiteList(prefs)) when {
            path.equals(file.absolutePath, ignoreCase = true) ||
                    path.equals(file.name, ignoreCase = true) -> return true
        }
        return false
    }

    /**
     * Runs before anything is filtered/cleaned. Automatically adds folders to the whitelist based on
     * the name of the folder itself
     * @param file file to check whether it should be added to the whitelist
     */
    private fun autoWhiteList(file: File): Boolean {
        protectedFileList.forEach { protectedFile ->
            if (file.name.lowercase(Locale.getDefault()).contains(protectedFile) &&
                !WhitelistActivity.getWhiteList(prefs)
                    .contains(file.absolutePath.lowercase(Locale.getDefault()))
            ) {
                WhitelistActivity.getWhiteList(prefs)
                    .toMutableList().add(file.absolutePath.lowercase(Locale.getDefault()))
                prefs
                    .edit()
                    .putStringSet("whitelist", HashSet(WhitelistActivity.getWhiteList(prefs)))
                    .apply()
                return true
            }
        }
        return false
    }

    /**
     * Runs as for each loop through the filter, and checks if the file matches any filters
     * @param file file to check
     * @return true if the file's extension is in the filter, false otherwise
     */
    fun filter(file: File?): Boolean {
        try {
            if (file != null) {
                // corpse checking - TODO: needs improved!
                when {
                    file.parentFile != null && file.parentFile.parentFile != null && corpse -> {
                        when {
                            file.parentFile.name == "data" &&
                                    file.parentFile.parentFile.name == "Android" &&
                                    !installedPackages.contains(file.name) &&
                                    file.name != ".nomedia" -> return true
                        }
                    }
                }
                // empty folder
                if (file.isDirectory && isDirectoryEmpty(file) && emptyDir) return true

                // file
                val filterIterator = filters.iterator()
                while (filterIterator.hasNext()) {
                    val filter = filterIterator.next()
                    if (file.absolutePath.lowercase(Locale.getDefault()).matches(filter.lowercase(Locale.getDefault()).toRegex()))
                        return true
                }
            }
        } catch (e: NullPointerException) {
            return false
        }
        return false // not empty folder or file in filter
    }

    private val installedPackages: List<String>
        get() {
            val pm = context!!.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val packagesString: MutableList<String> = ArrayList()
            for (packageInfo in packages) {
                packagesString.add(packageInfo.packageName)
            }
            return packagesString
        }

    /**
     * lists the contents of the file to an array, if the array length is 0, then return true, else
     * false
     * @param directory directory to test
     * @return true if empty, false if containing a file(s)
     */
    private fun isDirectoryEmpty(directory: File): Boolean {
        return if (directory.list() != null && directory.list() != null) directory.list().isEmpty()
        else false
    }

    /**
     * Adds paths to the white list that are not to be cleaned. As well as adds extensions to filter.
     * 'generic', 'aggressive', and 'apk' should be assigned by calling preferences.getBoolean()
     */
    @SuppressLint("ResourceType")
    fun setUpFilters(generic: Boolean, aggressive: Boolean, apk: Boolean): FileScanner {
        val folders: MutableList<String> = ArrayList()
        val files: MutableList<String> = ArrayList()
        setResources(context!!.resources)
        if (generic) {
            folders.addAll(listOf(*res!!.getStringArray(R.array.generic_filter_folders)))
            files.addAll(listOf(*res!!.getStringArray(R.array.generic_filter_files)))
        }
        if (aggressive) {
            folders.addAll(listOf(*res!!.getStringArray(R.array.aggressive_filter_folders)))
            files.addAll(listOf(*res!!.getStringArray(R.array.aggressive_filter_files)))
        }

        // filters
        filters.clear()
        for (folder in folders) filters.add(getRegexForFolder(folder))
        for (file in files) filters.add(getRegexForFile(file))

        // apk
        if (apk) filters.add(getRegexForFile(".apk"))
        return this
    }

    fun startScan(): Long {
        isRunning = true
        var cycles: Byte = 0
        var maxCycles: Byte = 1
        var foundFiles: List<File>
        if (prefs.getBoolean("multirun", false)) maxCycles = 10
        if (!delete) maxCycles =
            1 // when nothing is being deleted. Stops duplicates from being found

        // removes the need to 'clean' multiple times to get everything
        while (cycles < maxCycles) {

            // cycle indicator
            if (gui != null)
                (context as MainActivity?)!!.displayText(
                    "Running Cycle" + " " + (cycles + 1) + "/" + maxCycles
                )

            // find files
            foundFiles = listFiles
            if (gui != null) gui!!.scanProgress.max = gui!!.scanProgress.max + foundFiles.size

            // scan & delete
            for (file in foundFiles) {
                if (filter(file)) { // filter
                    var tv: TextView? = null
                    if (gui != null) tv = (context as MainActivity?)!!.displayDeletion(file)

                    if (delete) {
                        kilobytesTotal += file.length()
                        ++filesRemoved

                        // deletion
                        when {
                            !file.delete() && tv != null -> {
                                val finalTv: TextView = tv
                                (context as MainActivity?)!!.runOnUiThread {
                                    finalTv.setTextColor(
                                        Color.GRAY // error effect - red looks too concerning
                                    )
                                }
                            }
                        }
                    } else {
                        kilobytesTotal += file.length()
                    }
                }
                if (gui != null) { // progress
                    (context as MainActivity?)!!.runOnUiThread {
                        gui!!.scanProgress.progress = gui!!.scanProgress.progress + 1
                    }
                    val scanPercent = gui!!.scanProgress.progress * 100.0 / gui!!.scanProgress.max
                    (context as MainActivity?)!!.runOnUiThread {
                        gui!!.scanTextView.text =
                            String.format(Locale.US, "%.0f", scanPercent) + "%"
                    }
                }
            }

            // cycle indicator
            if (gui != null)
                (context as MainActivity?)!!.displayText(
                    "Finished Cycle" + " " + (cycles + 1) + "/" + maxCycles
                )
            if (filesRemoved == 0) break // nothing found this run, no need to run again
            filesRemoved = 0 // reset for next cycle
            ++cycles
        }
        isRunning = false
        return kilobytesTotal
    }

    private fun getRegexForFolder(folder: String): String {
        return ".*(\\\\|/)$folder(\\\\|/|$).*"
    }

    private fun getRegexForFile(file: String): String {
        return ".+" + file.replace(".", "\\.") + "$"
    }

    fun setGUI(gui: ActivityMainBinding?): FileScanner {
        this.gui = gui
        return this
    }

    fun setResources(res: Resources?): FileScanner {
        this.res = res
        return this
    }

    fun setEmptyDir(emptyDir: Boolean): FileScanner {
        this.emptyDir = emptyDir
        return this
    }

    fun setDelete(delete: Boolean): FileScanner {
        this.delete = delete
        return this
    }

    fun setCorpse(corpse: Boolean): FileScanner {
        this.corpse = corpse
        return this
    }

    fun setAutoWhite(autoWhite: Boolean): FileScanner {
        this.autoWhite = autoWhite
        return this
    }

    fun setContext(context: Context?): FileScanner {
        this.context = context
        return this
    }

    companion object {
        // TODO remove local prefs objects, create setter for one instead
        @JvmField
        var isRunning = false
        private val filters = ArrayList<String>()
        private val protectedFileList =
            arrayOf(
                "backup",
                "copy",
                "copies",
                "important",
                "do_not_edit"
            ) // TODO: move to resources for translations
    }

    init {
        WhitelistActivity.getWhiteList(prefs)
    }
}
