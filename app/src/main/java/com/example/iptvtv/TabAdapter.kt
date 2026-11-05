package com.example.iptvtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TabAdapter(private val onSelect: (String) -> Unit) :
    RecyclerView.Adapter<TabAdapter.VH>() {

    private var items: List<Tab> = emptyList()
    private var selected = ""

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v as TextView
    }

    fun submit(list: List<Tab>, sel: String) {
        items = list
        selected = sel
        notifyDataSetChanged()
    }

    /** Cambia la pestaña marcada sin recrear las vistas (así no se pierde el foco del control). */
    fun setSelected(id: String) {
        val old = items.indexOfFirst { it.id == selected }
        val new = items.indexOfFirst { it.id == id }
        selected = id
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        h.title.text = t.title
        h.itemView.isSelected = t.id == selected
        h.itemView.setOnClickListener { onSelect(t.id) }
        // Al mover el foco sobre una pestaña se muestra su lista directamente.
        h.itemView.setOnFocusChangeListener { _, has -> if (has && t.id != selected) onSelect(t.id) }
    }

    override fun getItemCount() = items.size
}
