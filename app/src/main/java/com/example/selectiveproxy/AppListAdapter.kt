package com.example.selectiveproxy

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private val apps: List<ApplicationInfo>,
    private val selected: MutableSet<String>,
    private val packageManager: PackageManager
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(android.R.id.text1)
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.name.text = app.loadLabel(packageManager)
        holder.icon.setImageDrawable(app.loadIcon(packageManager))
        holder.checkbox.isChecked = selected.contains(app.packageName)

        holder.itemView.setOnClickListener {
            val isChecked = !holder.checkbox.isChecked
            holder.checkbox.isChecked = isChecked
            if (isChecked) {
                selected.add(app.packageName)
            } else {
                selected.remove(app.packageName)
            }
        }
    }

    override fun getItemCount() = apps.size
}
