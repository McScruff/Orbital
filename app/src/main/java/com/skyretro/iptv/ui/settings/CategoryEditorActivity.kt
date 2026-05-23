package com.skyretro.iptv.ui.settings

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.skyretro.iptv.R
import com.skyretro.iptv.data.model.SkyCategory
import com.skyretro.iptv.data.model.mapCategoryToSky
import com.skyretro.iptv.databinding.ActivityCategoryEditorBinding
import com.skyretro.iptv.utils.CategoryPrefs
import com.skyretro.iptv.utils.ThemeManager

class CategoryEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryEditorBinding

    private val skyCategories = listOf(
        SkyCategory.ENTERTAINMENT,
        SkyCategory.MOVIES,
        SkyCategory.SPORTS,
        SkyCategory.NEWS_DOCUMENTARIES,
        SkyCategory.CHILDREN,
        SkyCategory.MUSIC_SPECIALIST,
        SkyCategory.OTHER_CHANNELS
    )

    private var serverCatIds: ArrayList<String> = arrayListOf()
    private var serverCatNames: ArrayList<String> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityCategoryEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverCatIds   = intent.getStringArrayListExtra(EXTRA_CAT_IDS)   ?: arrayListOf()
        serverCatNames = intent.getStringArrayListExtra(EXTRA_CAT_NAMES) ?: arrayListOf()

        binding.btnBack.setOnClickListener { setResult(RESULT_OK); finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            binding.btnBack.setBackgroundColor(if (hasFocus) 0xFF2D6090.toInt() else 0xFF1A3560.toInt())
        }

        buildSkyCatList()
        buildServerCatList()
    }

    private fun buildSkyCatList() {
        val p = ThemeManager.palette()
        binding.skyCatList.removeAllViews()

        skyCategories.forEachIndexed { idx, sky ->
            val name = CategoryPrefs.getCategoryName(this, sky)
            val normalBg = if (idx % 2 == 0) p.bgMid else p.bgPrimary

            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                text = "  ${sky.number}  $name  ✎"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                isClickable = true
                isFocusable = true
                setBackgroundColor(normalBg)

                setOnFocusChangeListener { _, hasFocus ->
                    setBackgroundColor(if (hasFocus) p.focus else normalBg)
                }

                setOnClickListener { showRenameDialog(sky, this, idx) }
            }
            binding.skyCatList.addView(tv)
        }
    }

    private fun showRenameDialog(sky: SkyCategory, rowView: TextView, idx: Int) {
        val currentName = CategoryPrefs.getCategoryName(this, sky)
        val input = EditText(this).apply {
            setText(currentName)
            selectAll()
            setTextColor(0xFF111111.toInt())
            setSingleLine()
        }
        AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("RENAME: ${sky.displayName}")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val newName = input.text.toString().trim().uppercase()
                if (newName.isNotEmpty()) {
                    CategoryPrefs.setCategoryName(this, sky, newName)
                    val p = ThemeManager.palette()
                    val normalBg = if (idx % 2 == 0) p.bgMid else p.bgPrimary
                    rowView.text = "  ${sky.number}  $newName  ✎"
                    rowView.setBackgroundColor(normalBg)
                    setResult(RESULT_OK)
                }
            }
            .setNeutralButton("RESET TO DEFAULT") { _, _ ->
                CategoryPrefs.setCategoryName(this, sky, sky.displayName)
                val p = ThemeManager.palette()
                val normalBg = if (idx % 2 == 0) p.bgMid else p.bgPrimary
                rowView.text = "  ${sky.number}  ${sky.displayName}  ✎"
                rowView.setBackgroundColor(normalBg)
                setResult(RESULT_OK)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun buildServerCatList() {
        val p = ThemeManager.palette()
        binding.serverCatList.removeAllViews()
        val customMapping = CategoryPrefs.getCustomMapping(this)

        if (serverCatIds.isEmpty()) {
            val empty = TextView(this).apply {
                text = "  NO SERVER CATEGORIES AVAILABLE\n  Load a server first."
                setTextColor(0xFF668899.toInt())
                textSize = 11f
                setPadding(dp(12), dp(12), 0, 0)
            }
            binding.serverCatList.addView(empty)
            return
        }

        serverCatIds.forEachIndexed { idx, catId ->
            val catName = serverCatNames.getOrElse(idx) { catId }
            val assignedSky = customMapping[catId] ?: mapCategoryToSky(catName)
            val normalBg = if (idx % 2 == 0) p.bgMid else p.bgPrimary

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                isClickable = true
                isFocusable = true
                setBackgroundColor(normalBg)

                setOnFocusChangeListener { _, hasFocus ->
                    setBackgroundColor(if (hasFocus) p.focus else normalBg)
                }

                setOnClickListener { showAssignDialog(catId, catName, idx) }
            }

            val nameView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = catName
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val badgeText = CategoryPrefs.getCategoryName(this, assignedSky)
            val badgeView = TextView(this).apply {
                text = "→ $badgeText"
                setTextColor(0xFF000000.toInt())
                textSize = 9f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                setBackgroundColor(p.highlight)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }

            row.addView(nameView)
            row.addView(badgeView)
            binding.serverCatList.addView(row)
        }
    }

    private fun showAssignDialog(catId: String, catName: String, rowIdx: Int) {
        val labels = skyCategories.map { sky ->
            "  ${sky.number}  ${CategoryPrefs.getCategoryName(this, sky)}"
        }.toMutableList()
        labels.add("  AUTO-DETECT (RESET)")

        AlertDialog.Builder(this, R.style.Theme_SkyRetro_Dialog)
            .setTitle("ASSIGN  \"${catName.take(40).uppercase()}\"")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < skyCategories.size) {
                    CategoryPrefs.setServerCategoryMapping(this, catId, skyCategories[which])
                } else {
                    CategoryPrefs.setServerCategoryMapping(this, catId, null)
                }
                setResult(RESULT_OK)
                buildServerCatList()
            }
            .show()
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CAT_IDS   = "extra_cat_ids"
        const val EXTRA_CAT_NAMES = "extra_cat_names"
    }
}
