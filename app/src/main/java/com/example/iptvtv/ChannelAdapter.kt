package com.example.iptvtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val isFav: (Channel) -> Boolean,
    private val onPlay: (Int) -> Unit,
    private val onToggleFav: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<Channel>()
    private var playingUrl: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val main: View = v.findViewById(R.id.main)
        val num: TextView = v.findViewById(R.id.num)
        val name: TextView = v.findViewById(R.id.name)
        val group: TextView = v.findViewById(R.id.group)
        val heart: ImageView = v.findViewById(R.id.btnHeart)
        val star: ImageView = v.findViewById(R.id.btnStar)
    }

    fun submit(list: List<Channel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setPlaying(url: String?) {
        playingUrl = url
        notifyDataSetChanged()
    }

    fun indexOfPlaying(): Int {
        val u = playingUrl ?: return -1
        return items.indexOfFirst { it.url == u }
    }

    fun refresh(pos: Int) = notifyItemChanged(pos)

    fun removeAt(pos: Int) {
        if (pos !in items.indices) return
        items.removeAt(pos)
        notifyItemRemoved(pos)
        notifyItemRangeChanged(pos, items.size - pos) // renumera
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.num.text = (position + 1).toString()
        h.name.text = c.name
        h.group.text = c.group ?: ""
        h.main.isSelected = c.url == playingUrl
        val fav = isFav(c)
        h.heart.isActivated = fav
        h.star.isActivated = fav

        h.main.setOnClickListener { pos(h)?.let(onPlay) }
        h.heart.setOnClickListener { pos(h)?.let(onToggleFav) }
        h.star.setOnClickListener { pos(h)?.let(onToggleFav) }
    }

    private fun pos(h: VH): Int? {
        val p = h.bindingAdapterPosition
        return if (p == RecyclerView.NO_POSITION) null else p
    }

    override fun getItemCount() = items.size
}
