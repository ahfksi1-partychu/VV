package com.mycarhub.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.mycarhub.R
import com.mycarhub.apps.model.FavoriteApp
import com.mycarhub.apps.system.InstalledAppCatalog

/** '내 앱' 목록. 행을 누르면 실행, 오른쪽 버튼으로 순서·별칭·삭제를 처리한다. */
class FavoriteAppsAdapter(
    private val catalog: InstalledAppCatalog,
    private val actions: Actions,
) : BaseAdapter() {

    interface Actions {
        fun onMove(app: FavoriteApp, offset: Int)
        fun onEditAlias(app: FavoriteApp)
        fun onRemove(app: FavoriteApp)
    }

    private var items: List<FavoriteApp> = emptyList()
    private var editingEnabled = true

    fun submit(apps: List<FavoriteApp>) {
        items = apps
        notifyDataSetChanged()
    }

    /** 주행 중에는 편집 버튼을 비활성화하고 실행만 허용한다. */
    fun setEditingEnabled(enabled: Boolean) {
        editingEnabled = enabled
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_app, parent, false)
        val app = items[position]
        val installed = catalog.isInstalled(app.packageName)

        view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(catalog.loadIcon(app))
        view.findViewById<TextView>(R.id.appName).text = app.displayName
        view.findViewById<TextView>(R.id.appDetail).text = when {
            !installed -> parent.context.getString(R.string.app_not_installed)
            app.alias != null -> parent.context.getString(R.string.alias_original_name, app.label)
            else -> app.packageName
        }
        view.alpha = if (installed) 1f else 0.5f

        view.findViewById<Button>(R.id.moveUp).apply {
            isEnabled = editingEnabled && position > 0
            setOnClickListener { actions.onMove(app, -1) }
        }
        view.findViewById<Button>(R.id.moveDown).apply {
            isEnabled = editingEnabled && position < items.lastIndex
            setOnClickListener { actions.onMove(app, +1) }
        }
        view.findViewById<Button>(R.id.editAlias).apply {
            isEnabled = editingEnabled
            setOnClickListener { actions.onEditAlias(app) }
        }
        view.findViewById<Button>(R.id.remove).apply {
            isEnabled = editingEnabled
            setOnClickListener { actions.onRemove(app) }
        }
        return view
    }
}
