package com.mycarhub.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.mycarhub.R
import com.mycarhub.apps.model.LaunchableApp
import com.mycarhub.apps.system.InstalledAppCatalog

/** '+ 앱 추가' 목록. 이미 등록한 앱은 선택할 수 없게 표시한다. */
class AppChoiceAdapter(
    private val catalog: InstalledAppCatalog,
    private val items: List<LaunchableApp>,
    private val isRegistered: (LaunchableApp) -> Boolean,
) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun isEnabled(position: Int) = !isRegistered(items[position])
    override fun areAllItemsEnabled() = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app_choice, parent, false)
        val app = items[position]
        val registered = isRegistered(app)
        val context = parent.context

        view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(catalog.loadIcon(app))
        view.findViewById<TextView>(R.id.appName).text = app.label
        view.findViewById<TextView>(R.id.appBadge).text = listOfNotNull(
            context.getString(R.string.badge_preinstalled).takeIf { app.isPreinstalled },
            context.getString(R.string.badge_registered).takeIf { registered },
        ).joinToString(" · ")
        view.alpha = if (registered) 0.45f else 1f
        return view
    }
}
