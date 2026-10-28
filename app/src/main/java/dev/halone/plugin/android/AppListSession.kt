package dev.halone.plugin.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.halone.plugin.ViewPanelSession
import java.util.concurrent.Executors

class AppListSession(private val provider: AndroidAppsProvider) : ViewPanelSession() {
    private val main = Handler(Looper.getMainLooper())
    private val iconLoader = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Bitmap>(ICON_CACHE)
    private var query = ""
    private var shown: List<AppCatalog.PhoneApp> = emptyList()
    private lateinit var adapter: Adapter
    private lateinit var empty: TextView
    private lateinit var root: FrameLayout
    private var menu: View? = null
    private lateinit var recentSection: View
    private lateinit var recentRow: LinearLayout
    private lateinit var allHeader: TextView
    private val previews = LruCache<String, Bitmap>(PREVIEW_CACHE)
    private val onListChanged: () -> Unit = { filter() }

    override fun onCreateView(context: Context): View {
        host.setTitle(context.getString(R.string.list_name))
        provider.listChanged += onListChanged

        val search = EditText(context).apply {
            hint = context.getString(R.string.search_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(SEARCH_PADDING, 0, SEARCH_PADDING, 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x26, 0x29, 0x30))
                cornerRadius = SEARCH_HEIGHT / 2f
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    query = s.toString().trim()
                    filter()
                }
            })
        }

        adapter = Adapter(context)
        val grid = GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = CELL_WIDTH
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = SPACING
            setPadding(0, SPACING, 0, SPACING)
            clipToPadding = false
            adapter = this@AppListSession.adapter
            setOnItemClickListener { _, _, position, _ -> provider.launchApp(shown[position].id) }
            setOnItemLongClickListener { _, cell, position, _ ->
                showMenu(cell, shown[position])
                true
            }
        }

        recentRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val clear = TextView(context).apply {
            setText(R.string.recent_clear)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(MUTED)
            setPadding(SPACING, SPACING / 2, SPACING, SPACING / 2)
            selectable(context)
            setOnClickListener { provider.recents.clear() }
        }
        recentSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(header(context, context.getString(R.string.recent_header)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(clear)
            })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(recentRow)
            })
        }
        allHeader = header(context, "")

        empty = TextView(context).apply {
            text = context.getString(R.string.no_apps)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        filter()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x16, 0x18, 0x1d))
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SEARCH_HEIGHT))
            addView(recentSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SPACING })
            addView(allHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = SPACING })
            addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root = FrameLayout(context).apply { addView(content) }
        return root
    }

    override fun onClose() {
        provider.listChanged -= onListChanged
        iconLoader.shutdownNow()
        super.onClose()
    }

    private fun filter() {
        val all = provider.catalog.apps
        shown = if (query.isEmpty()) all else all.filter { it.label.contains(query, ignoreCase = true) }
        if (!::adapter.isInitialized) return
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        allHeader.text = allHeader.context.getString(R.string.all_apps_header, shown.size).uppercase()
        showRecents()
    }

    /** Hidden while searching, so the results come first. */
    private fun showRecents() {
        val recent = if (provider.recentsOn && query.isEmpty()) provider.recents.ids.mapNotNull(provider.catalog::find) else emptyList()
        recentSection.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        recentRow.removeAllViews()
        recent.forEach { recentRow.addView(recentTile(recentRow.context, it)) }
    }

    private fun recentTile(context: Context, app: AppCatalog.PhoneApp): View {
        val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val badge = ImageView(context)
        val discard = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = context.getString(R.string.recent_discard)
            setPadding(DISCARD_PADDING, DISCARD_PADDING, DISCARD_PADDING, DISCARD_PADDING)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0xcc, 0x16, 0x18, 0x1d))
            }
            visibility = View.GONE
            setOnClickListener { provider.recents.remove(app.id) }
        }
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(0x23, 0x26, 0x2c))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, TILE_RADIUS)
            }
            clipToOutline = true
            addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(badge, FrameLayout.LayoutParams(BADGE_SIZE, BADGE_SIZE, Gravity.BOTTOM or Gravity.START).apply { setMargins(BADGE_MARGIN, 0, 0, BADGE_MARGIN) })
            addView(discard, FrameLayout.LayoutParams(DISCARD_SIZE, DISCARD_SIZE, Gravity.TOP or Gravity.END).apply { setMargins(0, BADGE_MARGIN, BADGE_MARGIN, 0) })
        }
        loadIcon(app, badge)
        loadPreview(app, picture, badge)
        val label = TextView(context).apply {
            text = app.label
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        return object : LinearLayout(context) {
            override fun dispatchHoverEvent(event: MotionEvent): Boolean {
                discard.visibility = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) View.GONE else View.VISIBLE
                return super.dispatchHoverEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(SPACING / 2, SPACING / 2, SPACING / 2, SPACING / 2)
            selectable(context)
            addView(frame, LinearLayout.LayoutParams(TILE_WIDTH, TILE_HEIGHT))
            addView(label, LinearLayout.LayoutParams(TILE_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
            setOnClickListener { provider.launchApp(app.id) }
            setOnLongClickListener {
                showMenu(this, app)
                true
            }
        }
    }

    /** Without a picture of its window, the app's icon stands in, large, and the badge goes. */
    private fun loadPreview(app: AppCatalog.PhoneApp, into: ImageView, badge: ImageView) {
        val key = "${app.id}@${provider.recents.previewVersion(app.id)}"
        val show = { bitmap: Bitmap? ->
            if (bitmap != null) {
                into.scaleType = ImageView.ScaleType.CENTER_CROP
                into.setImageBitmap(bitmap)
            } else {
                into.scaleType = ImageView.ScaleType.CENTER
                badge.visibility = View.GONE
                loadIcon(app, into)
            }
        }
        previews.get(key)?.let { return show(it) }
        iconLoader.execute {
            val bitmap = provider.recents.preview(app.id)
            main.post {
                if (bitmap != null) previews.put(key, bitmap)
                show(bitmap)
            }
        }
    }

    private fun header(context: Context, text: String) = TextView(context).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(MUTED)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        setPadding(HEADER_INSET, SPACING / 2, 0, SPACING / 2)
    }

    private fun View.selectable(context: Context) {
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) setBackgroundResource(value.resourceId)
    }

    /** Drawn in the panel's own views: a PopupMenu's separate window gets none of the panel's input. */
    private fun showMenu(anchor: View, app: AppCatalog.PhoneApp) {
        dismissMenu()
        val context = anchor.context
        val onHome = app.id in provider.home
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = MENU_WIDTH
            setPadding(0, MENU_PADDING, 0, MENU_PADDING)
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x2a, 0x2d, 0x34))
                cornerRadius = MENU_RADIUS
            }
            elevation = MENU_ELEVATION
            addView(menuItem(context, R.string.menu_open) { provider.launchApp(app.id) })
            addView(menuItem(context, if (onHome) R.string.menu_remove_home else R.string.menu_add_home) { provider.setOnHome(app.id, !onHome) })
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val anchorAt = IntArray(2).also(anchor::getLocationInWindow)
        val rootAt = IntArray(2).also(root::getLocationInWindow)
        val x = anchorAt[0] - rootAt[0] + anchor.width / 2
        val y = anchorAt[1] - rootAt[1] + anchor.height / 2
        val scrim = FrameLayout(context).apply {
            setOnClickListener { dismissMenu() }
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x.coerceAtMost(root.width - list.measuredWidth).coerceAtLeast(0)
                topMargin = y.coerceAtMost(root.height - list.measuredHeight).coerceAtLeast(0)
            })
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menu = scrim
    }

    private fun menuItem(context: Context, text: Int, action: () -> Unit) = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(MENU_ITEM_PADDING, MENU_ITEM_PADDING / 2, MENU_ITEM_PADDING, MENU_ITEM_PADDING / 2)
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) setBackgroundResource(value.resourceId)
        setOnClickListener {
            dismissMenu()
            action()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dismissMenu() {
        menu?.let(root::removeView)
        menu = null
    }

    private fun loadIcon(app: AppCatalog.PhoneApp, into: ImageView) {
        into.tag = app.id
        val cached = icons.get(app.id)
        if (cached != null) return into.setImageBitmap(cached)
        into.setImageDrawable(null)
        iconLoader.execute {
            val bitmap = provider.catalog.icon(app.component, ICON_SIZE) ?: return@execute
            main.post {
                icons.put(app.id, bitmap)
                if (into.tag == app.id) into.setImageBitmap(bitmap)
            }
        }
    }

    private inner class Adapter(private val context: Context) : BaseAdapter() {
        override fun getCount() = shown.size

        override fun getItem(position: Int) = shown[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? LinearLayout ?: newCell()
            val app = shown[position]
            loadIcon(app, cell.getChildAt(0) as ImageView)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }

        private fun newCell() = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(SPACING / 2, SPACING / 2, SPACING / 2, SPACING / 2)
            selectable(context)
            addView(ImageView(context), LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE))
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        }
    }

    private companion object {
        const val PADDING = 20
        const val SPACING = 12
        const val CELL_WIDTH = 136
        const val ICON_SIZE = 72
        const val ICON_CACHE = 300
        const val PREVIEW_CACHE = 16
        const val SEARCH_HEIGHT = 52
        const val SEARCH_PADDING = 24
        const val HEADER_INSET = 4
        const val TILE_WIDTH = 116
        const val TILE_HEIGHT = 180
        const val TILE_RADIUS = 14f
        const val BADGE_SIZE = 32
        const val BADGE_MARGIN = 6
        const val DISCARD_SIZE = 28
        const val DISCARD_PADDING = 6
        val MUTED = Color.rgb(0x9a, 0xa0, 0xaa)
        const val MENU_WIDTH = 200
        const val MENU_PADDING = 8
        const val MENU_ITEM_PADDING = 32
        const val MENU_RADIUS = 12f
        const val MENU_ELEVATION = 8f
    }
}
